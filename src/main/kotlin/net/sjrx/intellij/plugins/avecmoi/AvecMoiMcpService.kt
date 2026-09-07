package net.sjrx.intellij.plugins.avecmoi

import com.intellij.openapi.Disposable
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.application.runReadActionBlocking
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.service
import com.intellij.openapi.diagnostic.thisLogger
import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.openapi.fileEditor.FileEditorManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.project.ProjectManager
import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpServer
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.add
import kotlinx.serialization.json.addJsonObject
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject
import java.net.InetSocketAddress
import java.nio.charset.StandardCharsets
import java.util.Properties
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/**
 * Application-level (one per IDE process) MCP server for avec-moi. Hand-rolled JSON-RPC 2.0 over
 * the JDK HttpServer (no Ktor / MCP-SDK — the IDE bundles those and bundling our own collides in
 * the plugin classloader). Serves MCP "Streamable HTTP" as plain request/response JSON at POST /mcp.
 *
 * One server serves ALL open projects in this IDE process; tools that act on a project take a
 * `projectPath` and are routed to the matching open project (see [resolveProject]). Registered once
 * globally with the agent's MCP client (e.g. `claude mcp add --scope user ...`), so every session reaches it.
 *
 * SECURITY: runs inside the IDE process (outside the agent's sandbox), so the tool list IS the trust
 * boundary — every tool stays read-only/metadata or append-only-state, never file-write or exec.
 * Bound to 127.0.0.1 only.
 */
@Service(Service.Level.APP)
class AvecMoiMcpService : Disposable {

  private var server: HttpServer? = null

  /** Whether any agent tool call has targeted any project this IDE session (runtime only). */
  @Volatile
  private var anyContact: Boolean = false

  fun anyContact(): Boolean = anyContact

  /** Reset the (app-level, cross-test) connection flag so a test can observe a clean false→true. */
  @org.jetbrains.annotations.TestOnly
  internal fun resetContactForTest() {
    anyContact = false
  }

  private val buildInfo: Properties by lazy {
    Properties().apply {
      AvecMoiMcpService::class.java.getResourceAsStream("/avecmoi-build-info.properties")?.use { load(it) }
    }
  }

  /** The port the server is actually bound to right now (for the connect instructions). */
  @Volatile
  private var activePort: Int = PORT

  /** Why the last start attempt failed (e.g. the port is in use), or null if the server is running. */
  @Volatile
  private var startError: String? = null

  fun currentPort(): Int = activePort

  fun isRunning(): Boolean = server != null

  /** Non-null when the server isn't running because start failed (e.g. the port is already taken). */
  fun startError(): String? = startError

  @Synchronized
  fun startIfNeeded() {
    if (server != null) return
    val port = service<AvecMoiMcpSettings>().port
    try {
      val s = configureServer(port)
      s.start()
      server = s
      activePort = port
      startError = null
      thisLogger().info("avec-moi MCP server listening on http://127.0.0.1:$port/mcp")
    } catch (t: Throwable) {
      startError = "Port $port is unavailable — ${t.message ?: "already in use"}. " +
        "Another IDE is probably already running the avec-moi server on it."
      activePort = port
      thisLogger().warn("avec-moi: failed to start MCP server on port $port", t)
    }
  }

  /** Stop and rebind on the currently-configured port (call after the port setting changes). */
  @Synchronized
  fun restart() {
    stopServer(server)
    server = null
    startIfNeeded()
  }

  /** Build the server with its `/mcp` and catch-all contexts, bound to [port] (0 = an ephemeral port). */
  private fun configureServer(port: Int): HttpServer {
    val s = HttpServer.create(InetSocketAddress("127.0.0.1", port), 0)
    s.createContext("/mcp") { ex -> handle(ex) }
    // Catch-all: any non-/mcp path (e.g. an MCP client's OAuth "re-authenticate" probe of
    // /.well-known/oauth-*) gets a clean JSON 404 instead of the JDK's default HTML page, so the
    // client's OAuth SDK parses it and treats us as "no auth required" rather than crashing on
    // "Unrecognized token '<'". (We're a no-auth localhost server; there's nothing to authorize.)
    s.createContext("/") { ex ->
      anyContact = true // even an OAuth "re-authenticate" probe means a client is trying to connect
      try {
        respond(ex, 404, "{\"error\":\"not_found\"}")
      } catch (t: Throwable) {
        thisLogger().warn("avec-moi MCP: root handler error", t)
      } finally {
        ex.close()
      }
    }
    // Our own daemon-thread pool (not HttpServer's internal thread) so we can deterministically kill
    // the request threads on unload — a lingering handler thread would pin the plugin classloader
    // and force a restart on reload. Daemon so it never blocks JVM shutdown either.
    s.executor = Executors.newFixedThreadPool(2) { r -> Thread(r, "avec-moi-mcp").apply { isDaemon = true } }
    return s
  }

  /** Stop [s] with a short grace for in-flight exchanges, then hard-stop its worker threads so none
   *  lingers holding the plugin classloader (keeps the plugin dynamically unloadable). */
  private fun stopServer(s: HttpServer?) {
    s ?: return
    s.stop(1)
    (s.executor as? ExecutorService)?.let { pool ->
      pool.shutdownNow()
      runCatching { pool.awaitTermination(1, TimeUnit.SECONDS) }
    }
  }

  /** Start a throwaway server on an ephemeral port for an HTTP-level test; returns the bound port. */
  @org.jetbrains.annotations.TestOnly
  internal fun startOnEphemeralPortForTest(disposable: Disposable): Int {
    val s = configureServer(0)
    s.start()
    com.intellij.openapi.util.Disposer.register(disposable) { stopServer(s) }
    return s.address.port
  }

  private fun handle(ex: HttpExchange) {
    anyContact = true // any MCP request (initialize, tools/list, reconnect, …) = a client is here
    try {
      // Defend the loopback server against browser-driven CSRF / DNS-rebinding: a real MCP client
      // (claude-code) sends no Origin and a localhost Host; a webpage sends a real Origin, and a
      // rebound request sends a non-local Host. Reject those. (Per the MCP Streamable-HTTP spec.)
      if (!isLocalRequest(ex)) return respond(ex, 403, "{\"error\":\"forbidden\"}")
      when (ex.requestMethod) {
        "POST" -> handlePost(ex)
        else -> respond(ex, 405, null)
      }
    } catch (t: Throwable) {
      thisLogger().warn("avec-moi MCP: handler error", t)
      runCatching { respond(ex, 500, null) }
    } finally {
      ex.close()
    }
  }

  private fun isLocalRequest(ex: HttpExchange): Boolean =
    isLocalOriginAndHost(ex.requestHeaders.getFirst("Origin"), ex.requestHeaders.getFirst("Host"))

  private fun handlePost(ex: HttpExchange) {
    val body = ex.requestBody.readBytes().toString(StandardCharsets.UTF_8)
    val r = handleMcpPost(body)
    respond(ex, r.status, r.body)
  }

  /** The HTTP status + JSON body to send back for a POST /mcp. */
  internal data class McpHttpResult(val status: Int, val body: String?)

  /**
   * Transport-independent handler for one POST /mcp request body: parse the JSON-RPC envelope, route
   * the method, and return the status + response body. Both [handlePost] (the real HttpServer) and the
   * tests call this, so the dispatch — and the "a client is present" signal — is exercised without
   * binding a socket.
   */
  internal fun handleMcpPost(body: String): McpHttpResult {
    anyContact = true // a POST to /mcp = a client is here (initialize, tools/list, reconnect, …)
    val req = runCatching { Json.parseToJsonElement(body).jsonObject }.getOrNull()
      ?: return McpHttpResult(400, null)

    val method = req["method"]?.jsonPrimitive?.contentOrNull
    val id = req["id"]
    if (id == null) return McpHttpResult(202, null) // notification

    val result: JsonElement = when (method) {
      "initialize" -> initializeResult(req)
      "tools/list" -> toolsListResult()
      "tools/call" -> toolsCallResult(req)
      else -> return McpHttpResult(200, errorResponse(id, -32601, "Method not found: $method"))
    }
    return McpHttpResult(200, buildJsonObject {
      put("jsonrpc", "2.0")
      put("id", id)
      put("result", result)
    }.toString())
  }

  // --- MCP methods -------------------------------------------------------------------------------

  private fun initializeResult(req: JsonObject): JsonObject {
    val clientProto = req["params"]?.jsonObject?.get("protocolVersion")?.jsonPrimitive?.contentOrNull
    return buildJsonObject {
      put("protocolVersion", clientProto ?: "2025-06-18")
      putJsonObject("capabilities") { putJsonObject("tools") {} }
      putJsonObject("serverInfo") {
        put("name", "avec-moi")
        put("version", buildInfo.getProperty("version", "0.1.0"))
      }
      put("instructions", INSTRUCTIONS)
    }
  }

  private fun toolsListResult(): JsonObject = buildJsonObject {
    putJsonArray("tools") {
      tool("ping", "Health check for the avec-moi MCP server. Returns pong.")
      tool("list_projects", "List the IntelliJ projects currently open in this IDE, by directory.")
      tool(
        "resolve_project",
        "Given a working directory, report which open IntelliJ project it belongs to.",
        requiredString("projectPath", "Absolute path to a directory inside the project (e.g. your cwd)."),
      )
      tool(
        "get_avecmoi_guidance",
        "How the human wants to work in THIS project: their goal, self-reported familiarity (per " +
          "language/framework/area), preferred review depth, and any extra context. Call this before " +
          "starting work in a project (and again after a context reset) and let it shape how much you " +
          "scaffold vs hand off, and how you review.",
        requiredString("projectPath", "Absolute path inside the target project (e.g. your cwd)."),
      )
      tool(
        "register_handoffs",
        "Register the spots where you left work for the human to implement, so the IDE can navigate " +
          "them. Call after writing compiling stubs. Replaces any previously registered handoffs.",
        requiredString("projectPath", "Absolute path inside the target project (e.g. your cwd)."),
        "handoffs" to buildJsonObject {
          put("type", "array")
          put("description", "Handoff points, in the order the human should work through them.")
          putJsonObject("items") {
            put("type", "object")
            putJsonObject("properties") {
              putJsonObject("file") {
                put("type", "string")
                put("description", "File path (absolute, or relative to the project root).")
              }
              putJsonObject("line") {
                put("type", "integer")
                put("description", "1-based line of the stub to implement.")
              }
              putJsonObject("note") {
                put("type", "string")
                put("description", "What the human should implement here. Use real newlines and " +
                  "hard-wrap at ~80 columns — it's shown as a multi-line inlay above the code and long " +
                  "lines aren't wrapped for you.")
              }
            }
            putJsonArray("required") { add("file"); add("note") }
          }
        },
      )
      addJsonObject {
        put("name", "add_handoff")
        put(
          "description",
          "Append ONE handoff without disturbing existing ones or their done-state (unlike " +
            "register_handoffs, which replaces the whole set). Optionally insert at a 1-based position.",
        )
        putJsonObject("inputSchema") {
          put("type", "object")
          putJsonObject("properties") {
            putJsonObject("projectPath") {
              put("type", "string"); put("description", "Absolute path inside the target project (e.g. your cwd).")
            }
            putJsonObject("file") {
              put("type", "string"); put("description", "File path (absolute, or relative to the project root).")
            }
            putJsonObject("line") { put("type", "integer"); put("description", "1-based line of the stub.") }
            putJsonObject("note") {
              put("type", "string")
              put("description", "What the human should implement here. Use real newlines, hard-wrap " +
                "~80 columns (shown as a multi-line editor inlay; long lines aren't wrapped for you).")
            }
            putJsonObject("position") {
              put("type", "integer"); put("description", "1-based position to insert at; default appends to the end.")
            }
          }
          putJsonArray("required") { add("projectPath"); add("file"); add("note") }
        }
      }
      addJsonObject {
        put("name", "update_handoff")
        put(
          "description",
          "Edit an existing handoff in place (by its 1-based number from list_handoffs), keeping its " +
            "position and done-state. Pass only the fields to change — note to reword, and/or file/line " +
            "to re-point it (e.g. the stub moved). Prefer this over remove+add, which renumbers and " +
            "drops done-state.",
        )
        putJsonObject("inputSchema") {
          put("type", "object")
          putJsonObject("properties") {
            putJsonObject("projectPath") {
              put("type", "string"); put("description", "Absolute path inside the target project (e.g. your cwd).")
            }
            putJsonObject("handoff") {
              put("type", "integer"); put("description", "1-based handoff number (as shown by list_handoffs).")
            }
            putJsonObject("note") {
              put("type", "string")
              put("description", "New note. Use real newlines, hard-wrap ~80 columns (multi-line inlay; " +
                "long lines aren't wrapped for you).")
            }
            putJsonObject("file") {
              put("type", "string"); put("description", "New file path (absolute, or relative to the project root).")
            }
            putJsonObject("line") { put("type", "integer"); put("description", "New 1-based line.") }
          }
          putJsonArray("required") { add("projectPath"); add("handoff") }
        }
      }
      tool(
        "navigate_to",
        "Open a file in the IDE editor and move the caret to a line — e.g. to start the human at " +
          "the first handoff.",
        requiredString("projectPath", "Absolute path inside the target project (e.g. your cwd)."),
        requiredString("file", "File path (absolute, or relative to the project root)."),
        "line" to buildJsonObject {
          put("type", "integer")
          put("description", "1-based line to navigate to.")
        },
      )
      tool(
        "get_editor_context",
        "Report where the human's cursor is (file, line, column) and any selected text in the active " +
          "editor — e.g. to see what they're looking at when they ask for help.",
        requiredString("projectPath", "Absolute path inside the target project (e.g. your cwd)."),
      )
      tool(
        "list_handoffs",
        "List the handoffs currently registered for a project — call after a context reset/resume " +
          "to recover state before re-registering.",
        requiredString("projectPath", "Absolute path inside the target project (e.g. your cwd)."),
      )
      tool(
        "clear_handoffs",
        "Clear all registered handoffs for a project (e.g. the plan is finished).",
        requiredString("projectPath", "Absolute path inside the target project (e.g. your cwd)."),
      )
      addJsonObject {
        put("name", "mark_handoff_done")
        put(
          "description",
          "Mark a handoff complete (by its 1-based number from list_handoffs) — e.g. after the human " +
            "implemented it and you reviewed their work. It stays in the list, shown as done. Pass " +
            "done=false to un-mark one.",
        )
        putJsonObject("inputSchema") {
          put("type", "object")
          putJsonObject("properties") {
            putJsonObject("projectPath") {
              put("type", "string"); put("description", "Absolute path inside the target project (e.g. your cwd).")
            }
            putJsonObject("handoff") {
              put("type", "integer"); put("description", "1-based handoff number (as shown by list_handoffs).")
            }
            putJsonObject("done") {
              put("type", "boolean"); put("description", "true to mark done (default), false to un-mark.")
            }
          }
          putJsonArray("required") { add("projectPath"); add("handoff") }
        }
      }
      tool(
        "remove_handoff",
        "Remove a handoff entirely (by its 1-based number from list_handoffs) — e.g. it no longer " +
          "applies. Renumbers the rest, so re-list afterwards.",
        requiredString("projectPath", "Absolute path inside the target project (e.g. your cwd)."),
        "handoff" to buildJsonObject {
          put("type", "integer")
          put("description", "1-based handoff number (as shown by list_handoffs).")
        },
      )
      tool(
        "create_session",
        "Start a new named session (container of handoffs) and make it active — for a distinct piece " +
          "of work, or a second agent on the same project. Handoff tools act on the active session.",
        requiredString("projectPath", "Absolute path inside the target project (e.g. your cwd)."),
        requiredString("name", "A short default name for the session (the human can rename it)."),
      )
      tool(
        "list_sessions",
        "List this project's sessions (active / open / historical) with names, branch, and progress.",
        requiredString("projectPath", "Absolute path inside the target project (e.g. your cwd)."),
      )
      tool(
        "switch_session",
        "Make an existing open session active (by its id from list_sessions).",
        requiredString("projectPath", "Absolute path inside the target project (e.g. your cwd)."),
        requiredString("session", "Session id (e.g. s1), as shown by list_sessions."),
      )
      tool(
        "rename_session",
        "Rename a session (by its id from list_sessions).",
        requiredString("projectPath", "Absolute path inside the target project (e.g. your cwd)."),
        requiredString("session", "Session id (e.g. s1)."),
        requiredString("name", "The new name."),
      )
      tool(
        "complete_session",
        "Mark a session done — moves it to Historical (by its id from list_sessions).",
        requiredString("projectPath", "Absolute path inside the target project (e.g. your cwd)."),
        requiredString("session", "Session id (e.g. s1)."),
      )
    }
  }

  private fun toolsCallResult(req: JsonObject): JsonObject {
    val params = req["params"]?.jsonObject
    val name = params?.get("name")?.jsonPrimitive?.contentOrNull
    val args = params?.get("arguments")?.jsonObject
    return when (name) {
      "ping" -> textContent(pingText(), isError = false)
      "list_projects" -> textContent(listProjectsText(), isError = false)
      "resolve_project" -> {
        val path = args?.get("projectPath")?.jsonPrimitive?.contentOrNull
        val proj = resolveProject(path)
        if (proj == null) textContent("No open project matches path: $path", isError = false)
        else textContent("Matched project '${proj.name}' at ${proj.basePath}", isError = false)
      }
      "get_avecmoi_guidance" -> getGuidance(args)
      "register_handoffs" -> registerHandoffs(args)
      "add_handoff" -> addHandoff(args)
      "update_handoff" -> updateHandoff(args)
      "navigate_to" -> navigateTo(args)
      "get_editor_context" -> getEditorContext(args)
      "list_handoffs" -> listHandoffs(args)
      "clear_handoffs" -> clearHandoffs(args)
      "mark_handoff_done" -> markHandoffDone(args)
      "remove_handoff" -> removeHandoff(args)
      "create_session" -> createSession(args)
      "list_sessions" -> listSessions(args)
      "switch_session" -> switchSession(args)
      "rename_session" -> renameSession(args)
      "complete_session" -> completeSession(args)
      else -> textContent("Unknown tool: $name", isError = true)
    }
  }

  // --- project routing (by directory) ------------------------------------------------------------

  private fun pingText(): String = buildString {
    appendLine("pong from avec-moi 🎸")
    appendLine("version: ${buildInfo.getProperty("version", "unknown")}")
    append("tools (${TOOL_NAMES.size}): ${TOOL_NAMES.joinToString(", ")}")
  }

  private fun listProjectsText(): String {
    val projects = ProjectManager.getInstance().openProjects
    if (projects.isEmpty()) return "No IntelliJ projects are currently open."
    return projects.joinToString("\n") { p -> "- ${p.name}: ${p.basePath ?: "(no base path)"}" }
  }

  /** Match a directory (e.g. the agent's cwd) to the open project whose base dir contains it; deepest wins. */
  private fun resolveProject(path: String?): Project? {
    val candidates = ProjectManager.getInstance().openProjects
      .mapNotNull { p -> p.basePath?.let { base -> p to base.trimEnd('/') } }
    val winner = bestMatchingBasePath(candidates.map { it.second }, path) ?: return null
    return candidates.firstOrNull { it.second == winner }?.first
      ?.also { it.service<AvecMoiRingService>().recordContact() }
  }

  private fun getGuidance(args: JsonObject?): JsonObject {
    val path = args?.get("projectPath")?.jsonPrimitive?.contentOrNull
    val project = resolveProject(path)
      ?: return textContent("No open project matches path: $path", isError = true)
    return textContent(project.service<AvecMoiSettingsService>().guidance(), isError = false)
  }

  /**
   * The handoff files that don't resolve to a real file under the project — the agent should fix
   * these. Empty is the happy path. Since the agent scaffolds stubs BEFORE registering handoffs into
   * them, a miss here almost always means the path is wrong (e.g. relative to a subdirectory in a
   * multi-project repo), not that the file is legitimately absent.
   */
  private fun unresolvedFiles(project: Project, files: List<String>): List<String> =
    runReadActionBlocking { files.distinct().filter { AvecMoiNavigation.resolveFile(project, it) == null } }

  /** Shared guidance appended to the tool reply when some handoff files can't be found. */
  private fun missingFilesWarning(project: Project, missing: List<String>): String =
    "\n\nWARNING: ${missing.size} of these point to files that don't exist under the project root " +
      "(${project.basePath}):\n" +
      missing.joinToString("\n") { "  - $it" } +
      "\n\nHandoff paths must be absolute, or relative to the PROJECT ROOT above — not to a " +
      "subdirectory or the agent's cwd. In a multi-project repo this usually means a missing " +
      "subdirectory prefix. Find the real paths (they're stored as-is, so the human sees the same " +
      "broken links) and fix them with update_handoff, or re-run register_handoffs."

  private fun registerHandoffs(args: JsonObject?): JsonObject {
    val path = args?.get("projectPath")?.jsonPrimitive?.contentOrNull
    val project = resolveProject(path)
      ?: return textContent("No open project matches path: $path", isError = true)
    val arr = args?.get("handoffs")?.jsonArray
      ?: return textContent("Missing 'handoffs' array.", isError = true)
    val list = arr.mapNotNull { el ->
      val o = el.jsonObject
      val file = o["file"]?.jsonPrimitive?.contentOrNull ?: return@mapNotNull null
      val line = o["line"]?.jsonPrimitive?.intOrNull ?: 1
      val note = o["note"]?.jsonPrimitive?.contentOrNull ?: ""
      AvecMoiRingService.HandoffSpec(file, line, note)
    }
    project.service<AvecMoiRingService>().set(list)
    val base = "Registered ${list.size} handoff(s) for project '${project.name}'. " +
      "The human can now step through them in the IDE."
    val missing = unresolvedFiles(project, list.map { it.file })
    return if (missing.isEmpty()) textContent(base, isError = false)
    else textContent(base + missingFilesWarning(project, missing), isError = true)
  }

  private fun addHandoff(args: JsonObject?): JsonObject {
    val path = args?.get("projectPath")?.jsonPrimitive?.contentOrNull
    val project = resolveProject(path)
      ?: return textContent("No open project matches path: $path", isError = true)
    val file = args?.get("file")?.jsonPrimitive?.contentOrNull
      ?: return textContent("Missing 'file'.", isError = true)
    val note = args?.get("note")?.jsonPrimitive?.contentOrNull
      ?: return textContent("Missing 'note'.", isError = true)
    val line = args?.get("line")?.jsonPrimitive?.intOrNull ?: 1
    val at = args?.get("position")?.jsonPrimitive?.intOrNull?.let { it - 1 } // 1-based -> 0-based; null appends
    val n = project.service<AvecMoiRingService>().add(AvecMoiRingService.HandoffSpec(file, line, note), at)
    val base = "Added handoff #$n in '${project.name}'."
    val missing = unresolvedFiles(project, listOf(file))
    return if (missing.isEmpty()) textContent(base, isError = false)
    else textContent(base + missingFilesWarning(project, missing), isError = true)
  }

  private fun updateHandoff(args: JsonObject?): JsonObject {
    val path = args?.get("projectPath")?.jsonPrimitive?.contentOrNull
    val project = resolveProject(path)
      ?: return textContent("No open project matches path: $path", isError = true)
    val n = args?.get("handoff")?.jsonPrimitive?.intOrNull
      ?: return textContent("Missing 'handoff' number.", isError = true)
    val note = args["note"]?.jsonPrimitive?.contentOrNull
    val file = args["file"]?.jsonPrimitive?.contentOrNull
    val line = args["line"]?.jsonPrimitive?.intOrNull
    if (note == null && file == null && line == null) {
      return textContent("Nothing to update — pass at least one of note, file, line.", isError = true)
    }
    return if (project.service<AvecMoiRingService>().update(n - 1, note, file, line)) {
      val base = "Updated handoff #$n in '${project.name}'."
      val missing = if (file != null) unresolvedFiles(project, listOf(file)) else emptyList()
      if (missing.isEmpty()) textContent(base, isError = false)
      else textContent(base + missingFilesWarning(project, missing), isError = true)
    } else {
      textContent("No handoff #$n in '${project.name}'.", isError = true)
    }
  }

  private fun navigateTo(args: JsonObject?): JsonObject {
    val path = args?.get("projectPath")?.jsonPrimitive?.contentOrNull
    val project = resolveProject(path)
      ?: return textContent("No open project matches path: $path", isError = true)
    val file = args?.get("file")?.jsonPrimitive?.contentOrNull
      ?: return textContent("Missing 'file'.", isError = true)
    val line = args?.get("line")?.jsonPrimitive?.intOrNull ?: 1
    return if (AvecMoiNavigation.openAt(project, file, line)) {
      textContent("Navigating '${project.name}' to $file:$line", isError = false)
    } else {
      textContent("File not found in project '${project.name}': $file", isError = true)
    }
  }

  private fun getEditorContext(args: JsonObject?): JsonObject {
    val path = args?.get("projectPath")?.jsonPrimitive?.contentOrNull
    val project = resolveProject(path)
      ?: return textContent("No open project matches path: $path", isError = true)
    var result = "No active editor in '${project.name}'."
    ApplicationManager.getApplication().invokeAndWait {
      val editor = FileEditorManager.getInstance(project).selectedTextEditor ?: return@invokeAndWait
      val doc = editor.document
      val file = FileDocumentManager.getInstance().getFile(doc)?.path ?: "(unknown file)"
      val caret = editor.caretModel.logicalPosition
      val sb = StringBuilder("File: $file\nCursor: line ${caret.line + 1}, column ${caret.column + 1}")
      val sel = editor.selectionModel
      if (sel.hasSelection()) {
        val startLine = doc.getLineNumber(sel.selectionStart) + 1
        val endLine = doc.getLineNumber(sel.selectionEnd) + 1
        sb.append("\nSelection (lines $startLine-$endLine):\n${sel.selectedText}")
      } else {
        sb.append("\nNo selection.")
      }
      result = sb.toString()
    }
    return textContent(result, isError = false)
  }

  private fun listHandoffs(args: JsonObject?): JsonObject {
    val path = args?.get("projectPath")?.jsonPrimitive?.contentOrNull
    val project = resolveProject(path)
      ?: return textContent("No open project matches path: $path", isError = true)
    val ring = project.service<AvecMoiRingService>()
    // reading .line touches the marker's document, so compute under a read lock (we're off the EDT).
    val text = runReadActionBlocking {
      ring.all().mapIndexed { i, h ->
        val mark = if (h.done) "[done]" else "[todo]"
        "${i + 1}. $mark ${h.note}  (${h.file}:${h.line})"
      }.joinToString("\n")
    }
    return if (text.isBlank()) {
      textContent("No handoffs registered for project '${project.name}'.", isError = false)
    } else {
      textContent("Handoffs for '${project.name}':\n$text", isError = false)
    }
  }

  private fun clearHandoffs(args: JsonObject?): JsonObject {
    val path = args?.get("projectPath")?.jsonPrimitive?.contentOrNull
    val project = resolveProject(path)
      ?: return textContent("No open project matches path: $path", isError = true)
    project.service<AvecMoiRingService>().clear()
    return textContent("Cleared all handoffs for project '${project.name}'.", isError = false)
  }

  private fun markHandoffDone(args: JsonObject?): JsonObject {
    val path = args?.get("projectPath")?.jsonPrimitive?.contentOrNull
    val project = resolveProject(path)
      ?: return textContent("No open project matches path: $path", isError = true)
    val n = args?.get("handoff")?.jsonPrimitive?.intOrNull
      ?: return textContent("Missing 'handoff' number.", isError = true)
    val done = args["done"]?.jsonPrimitive?.booleanOrNull ?: true
    return if (project.service<AvecMoiRingService>().markDone(n - 1, done)) {
      val what = if (done) "done" else "not done"
      textContent("Marked handoff #$n $what in '${project.name}'.", isError = false)
    } else {
      textContent("No handoff #$n in '${project.name}'.", isError = true)
    }
  }

  private fun removeHandoff(args: JsonObject?): JsonObject {
    val path = args?.get("projectPath")?.jsonPrimitive?.contentOrNull
    val project = resolveProject(path)
      ?: return textContent("No open project matches path: $path", isError = true)
    val n = args?.get("handoff")?.jsonPrimitive?.intOrNull
      ?: return textContent("Missing 'handoff' number.", isError = true)
    return if (project.service<AvecMoiRingService>().remove(n - 1)) {
      textContent("Removed handoff #$n from '${project.name}'.", isError = false)
    } else {
      textContent("No handoff #$n in '${project.name}'.", isError = true)
    }
  }

  // --- sessions ----------------------------------------------------------------------------------

  private fun createSession(args: JsonObject?): JsonObject {
    val path = args?.get("projectPath")?.jsonPrimitive?.contentOrNull
    val project = resolveProject(path)
      ?: return textContent("No open project matches path: $path", isError = true)
    val name = args?.get("name")?.jsonPrimitive?.contentOrNull
      ?: return textContent("Missing 'name'.", isError = true)
    val branch = currentBranch(project)
    val id = project.service<AvecMoiRingService>().createSession(name, branch)
    return textContent(
      "Created session '$name' ($id)${branch?.let { " on branch $it" } ?: ""} and made it active.",
      isError = false,
    )
  }

  private fun listSessions(args: JsonObject?): JsonObject {
    val path = args?.get("projectPath")?.jsonPrimitive?.contentOrNull
    val project = resolveProject(path)
      ?: return textContent("No open project matches path: $path", isError = true)
    val ring = project.service<AvecMoiRingService>()
    val active = ring.activeId()
    val sessions = ring.listSessions()
    if (sessions.isEmpty()) return textContent("No sessions yet in '${project.name}'.", isError = false)
    val text = sessions.joinToString("\n") { s ->
      val tag = when {
        s.historical -> "historical"
        s.id == active -> "ACTIVE"
        else -> "open"
      }
      val done = s.handoffs.count { it.done }
      "[$tag] ${s.id}: ${s.name}${s.branch?.let { " (branch $it)" } ?: ""} — $done/${s.handoffs.size} handoffs done"
    }
    return textContent(text, isError = false)
  }

  private fun switchSession(args: JsonObject?): JsonObject {
    val path = args?.get("projectPath")?.jsonPrimitive?.contentOrNull
    val project = resolveProject(path)
      ?: return textContent("No open project matches path: $path", isError = true)
    val id = args?.get("session")?.jsonPrimitive?.contentOrNull
      ?: return textContent("Missing 'session' id.", isError = true)
    return if (project.service<AvecMoiRingService>().switchSession(id)) {
      textContent("Active session is now $id in '${project.name}'.", isError = false)
    } else {
      textContent("No open session '$id' in '${project.name}'.", isError = true)
    }
  }

  private fun renameSession(args: JsonObject?): JsonObject {
    val path = args?.get("projectPath")?.jsonPrimitive?.contentOrNull
    val project = resolveProject(path)
      ?: return textContent("No open project matches path: $path", isError = true)
    val id = args?.get("session")?.jsonPrimitive?.contentOrNull
      ?: return textContent("Missing 'session' id.", isError = true)
    val name = args?.get("name")?.jsonPrimitive?.contentOrNull
      ?: return textContent("Missing 'name'.", isError = true)
    return if (project.service<AvecMoiRingService>().renameSession(id, name)) {
      textContent("Renamed session $id to '$name'.", isError = false)
    } else {
      textContent("No session '$id' in '${project.name}'.", isError = true)
    }
  }

  private fun completeSession(args: JsonObject?): JsonObject {
    val path = args?.get("projectPath")?.jsonPrimitive?.contentOrNull
    val project = resolveProject(path)
      ?: return textContent("No open project matches path: $path", isError = true)
    val id = args?.get("session")?.jsonPrimitive?.contentOrNull
      ?: return textContent("Missing 'session' id.", isError = true)
    return if (project.service<AvecMoiRingService>().completeSession(id)) {
      textContent("Completed session $id (moved to Historical) in '${project.name}'.", isError = false)
    } else {
      textContent("No session '$id' in '${project.name}'.", isError = true)
    }
  }

  private fun currentBranch(project: Project): String? = try {
    val base = project.basePath ?: return null
    val p = ProcessBuilder("git", "rev-parse", "--abbrev-ref", "HEAD")
      .directory(java.io.File(base))
      .redirectErrorStream(true)
      .start()
    p.inputStream.bufferedReader().readText().trim().takeIf { p.waitFor() == 0 && it.isNotBlank() }
  } catch (_: Exception) {
    null
  }

  // --- JSON helpers ------------------------------------------------------------------------------

  private fun kotlinx.serialization.json.JsonArrayBuilder.tool(
    name: String,
    description: String,
    vararg properties: Pair<String, JsonObject>,
  ) = addJsonObject {
    put("name", name)
    put("description", description)
    putJsonObject("inputSchema") {
      put("type", "object")
      putJsonObject("properties") { properties.forEach { (k, v) -> put(k, v) } }
      putJsonArray("required") { properties.forEach { (k, _) -> add(k) } }
    }
  }

  private fun requiredString(name: String, description: String): Pair<String, JsonObject> =
    name to buildJsonObject {
      put("type", "string")
      put("description", description)
    }

  private fun textContent(text: String, isError: Boolean): JsonObject = buildJsonObject {
    putJsonArray("content") {
      addJsonObject {
        put("type", "text")
        put("text", text)
      }
    }
    put("isError", isError)
  }

  private fun errorResponse(id: JsonElement, code: Int, message: String): String = buildJsonObject {
    put("jsonrpc", "2.0")
    put("id", id)
    putJsonObject("error") {
      put("code", code)
      put("message", message)
    }
  }.toString()

  private fun respond(ex: HttpExchange, code: Int, body: String?) {
    val bytes = body?.toByteArray(StandardCharsets.UTF_8)
    if (bytes != null) ex.responseHeaders.add("Content-Type", "application/json")
    ex.sendResponseHeaders(code, bytes?.size?.toLong() ?: -1L)
    if (bytes != null) ex.responseBody.use { it.write(bytes) }
  }

  override fun dispose() {
    stopServer(server)
    server = null
  }

  companion object {
    const val PORT = 8282

    /**
     * Accept a request only if it isn't browser-driven: a real MCP client sends no `Origin` and a
     * loopback `Host`. A webpage sends its site `Origin`; a DNS-rebound request sends a non-local
     * `Host`. Reject those (defends the loopback server against CSRF / DNS rebinding). Pure/testable.
     */
    internal fun isLocalOriginAndHost(origin: String?, host: String?): Boolean {
      if (origin != null && !isLoopbackAuthority(origin.substringAfter("://", origin))) return false
      if (host != null && !isLoopbackAuthority(host)) return false
      return true
    }

    private fun isLoopbackAuthority(authority: String): Boolean {
      val a = authority.substringBefore('/')
      val name = if (a.startsWith("[")) a.substringAfter('[').substringBefore(']') else a.substringBefore(':')
      return name == "localhost" || name == "127.0.0.1" || name == "::1"
    }

    /**
     * From a set of project base directories, pick the one that best contains [path]: the path must
     * equal a base or sit strictly beneath it (on a `/` boundary, so `/x/proj` does NOT match the
     * directory `/x/proj-other`), and the deepest such base wins (nested projects). Base paths are
     * matched with any trailing slash ignored. Returns the winning base (trimmed), or null for no
     * match / a null-or-blank path. Pure and platform-free so the routing rule is unit-testable.
     */
    internal fun bestMatchingBasePath(basePaths: List<String>, path: String?): String? {
      if (path.isNullOrBlank()) return null
      val norm = path.trimEnd('/')
      return basePaths
        .map { it.trimEnd('/') }
        .filter { base -> norm == base || norm.startsWith("$base/") }
        .maxByOrNull { it.length }
    }

    /**
     * Names of the tools registered in [toolsListResult] — keep in sync. Surfaced by `ping` so an
     * agent can detect it's on a stale tool list after a rebuild ("server has N, I see fewer").
     */
    val TOOL_NAMES = listOf(
      "ping", "list_projects", "resolve_project", "get_avecmoi_guidance", "register_handoffs",
      "add_handoff", "update_handoff", "navigate_to",
      "get_editor_context", "list_handoffs", "clear_handoffs", "mark_handoff_done", "remove_handoff",
      "create_session", "list_sessions", "switch_session", "rename_session", "complete_session",
    )

    /**
     * Server-level guidance surfaced to the model on connect (MCP InitializeResult.instructions).
     * This is where avec-moi's conventions live — it travels with the server, so every session
     * gets it with no per-project setup. Guidance, not a rigid workflow: the model decides specifics.
     */
    internal val INSTRUCTIONS = """
      avec-moi is a collaborative-coding server: you (the agent) design and scaffold, the human
      implements the load-bearing logic. The point is to keep the human engaged and fluent in
      their own codebase — "with me," not "for me." Work WITH the human, not around them.

      Serve the developer's desired level of engagement for THIS project — which may shift from
      session to session, even task to task. avec-moi exists to serve the developer and their
      changing whims; it is NOT here to force them to code or toil. Some things they'll want to own
      fully; others they'll happily hand back to you — when unsure, ask how involved they want to be
      and hand off more or less accordingly. The goal is to help them enjoy coding and shipping
      features, staying as sharp and engaged as they choose to be.

      How to use it:
      - Before starting work in a project, call `get_avecmoi_guidance(projectPath)` to learn how the
        human wants to work here — their goal, self-reported familiarity, and preferred review depth —
        and let it shape how much you scaffold vs hand off, and how you review. Re-fetch it after a
        context reset. (It may be empty if they haven't set anything; that's fine.)
      - Design the change and write stubs that at least parse / compile — no syntax errors (e.g.
        Kotlin `TODO("...")`, Python `raise NotImplementedError`) — so the human implements into
        working scaffolding rather than debugging yours. It need NOT be a fully green build: failing
        tests (including placeholder tests meant to fail until implemented) and linter gripes are
        fine. The bar is "no syntax errors", not "everything passes".
      - Call `register_handoffs` with the (file, line, note) spots where the human writes the real
        logic. Hand off the load-bearing, instructive parts — the actual thinking — and stub the
        rote yourself (imports, boilerplate, wiring). Resist implementing the interesting part.
      - To add a spot mid-flight, use `add_handoff` (appends one, preserving existing progress);
        register_handoffs replaces the whole set and resets done-state.
      - Handoffs live in a SESSION; the active one is used automatically, so you usually needn't
        think about it. For a distinct piece of work (or a second agent on this project), call
        `create_session("<name>")` to start a fresh one; `list_sessions` / `switch_session` /
        `rename_session` manage them; `complete_session` moves a finished one to Historical.
      - Every handoff needs a specific note: what to implement here and why it matters. Format notes
        as several short lines — put in real newlines and hard-wrap at a reasonable editor width
        (about 80 columns). The IDE floats the note above the code as a multi-line inlay and does NOT
        wrap long lines for you, so a single long line will run off-screen.
      - Optionally call `navigate_to` to place the human at the first handoff to get them started.
      - The human steps through the handoffs and implements them.

      How the human signals you: the IDE gives the human quick actions (buttons/keys) that inject
      specific messages into THIS conversation — that is their channel back to you (there is no way
      for the IDE to push to you otherwise; you only ever hear from them through these). Treat the
      injected messages as deliberate signals, not stray input:
        - "Step done — review my implementation": they finished the current handoff; read what they
          wrote (open the files, or `git diff` for just their change) and review or continue.
        - a hint request: they're stuck and want a nudge, NOT for you to implement it.
      Review happens only when the human asks for it via these actions — it is not automatic, not
      required, and you should not nag. More quick actions may appear over time.
      - As the human completes a handoff (and you review it), mark it with mark_handoff_done, or
        remove_handoff if one no longer applies. The IDE shows todo vs done per row.
      - After a context reset or resume, call `list_handoffs` to recover what's currently registered
        rather than guessing. The ring lives in the IDE and is NOT saved across IDE restarts, so if
        list_handoffs comes back empty when you expected handoffs (the IDE restarted, or the human
        cleared them), just re-register from your plan — it's not an error. (register_handoffs
        replaces the whole set; clear_handoffs empties it.)

      All tools take a `projectPath` (the human's project directory, i.e. your cwd) so the right
      open IDE project is targeted.
    """.trimIndent()
  }
}
