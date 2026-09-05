package net.sjrx.intellij.plugins.avecmoi

import com.intellij.openapi.Disposable
import com.intellij.openapi.application.ReadAction
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.service
import com.intellij.openapi.diagnostic.thisLogger
import com.intellij.openapi.project.Project
import com.intellij.openapi.project.ProjectManager
import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpServer
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.add
import kotlinx.serialization.json.addJsonObject
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

  private val buildInfo: Properties by lazy {
    Properties().apply {
      AvecMoiMcpService::class.java.getResourceAsStream("/avecmoi-build-info.properties")?.use { load(it) }
    }
  }

  @Synchronized
  fun startIfNeeded() {
    if (server != null) return
    try {
      val s = HttpServer.create(InetSocketAddress("127.0.0.1", PORT), 0)
      s.createContext("/mcp") { ex -> handle(ex) }
      // Catch-all: any non-/mcp path (e.g. an MCP client's OAuth "re-authenticate" probe of
      // /.well-known/oauth-*) gets a clean JSON 404 instead of the JDK's default HTML page, so the
      // client's OAuth SDK parses it and treats us as "no auth required" rather than crashing on
      // "Unrecognized token '<'". (We're a no-auth localhost server; there's nothing to authorize.)
      s.createContext("/") { ex ->
        try {
          respond(ex, 404, "{\"error\":\"not_found\"}")
        } catch (t: Throwable) {
          thisLogger().warn("avec-moi MCP: root handler error", t)
        } finally {
          ex.close()
        }
      }
      s.executor = null
      s.start()
      server = s
      thisLogger().info("avec-moi MCP seurver listening on http://127.0.0.1:$PORT/mcp")
    } catch (t: Throwable) {
      thisLogger().warn("avec-moi: failed to start MCP server on port $PORT", t)
    }
  }

  private fun handle(ex: HttpExchange) {
    try {
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

  private fun handlePost(ex: HttpExchange) {
    val body = ex.requestBody.readBytes().toString(StandardCharsets.UTF_8)
    val req = runCatching { Json.parseToJsonElement(body).jsonObject }.getOrNull()
      ?: return respond(ex, 400, null)

    val method = req["method"]?.jsonPrimitive?.contentOrNull
    val id = req["id"]
    if (id == null) return respond(ex, 202, null) // notification

    val result: JsonElement = when (method) {
      "initialize" -> initializeResult(req)
      "tools/list" -> toolsListResult()
      "tools/call" -> toolsCallResult(req)
      else -> return respond(ex, 200, errorResponse(id, -32601, "Method not found: $method"))
    }
    respond(ex, 200, buildJsonObject {
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
                put("description", "What the human should implement here.")
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
            putJsonObject("note") { put("type", "string"); put("description", "What the human should implement here.") }
            putJsonObject("position") {
              put("type", "integer"); put("description", "1-based position to insert at; default appends to the end.")
            }
          }
          putJsonArray("required") { add("projectPath"); add("file"); add("note") }
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
      tool(
        "mark_handoff_done",
        "Mark a handoff complete (by its 1-based number from list_handoffs) — e.g. after the human " +
          "implemented it and you reviewed their work. It stays in the list, shown as done.",
        requiredString("projectPath", "Absolute path inside the target project (e.g. your cwd)."),
        "handoff" to buildJsonObject {
          put("type", "integer")
          put("description", "1-based handoff number (as shown by list_handoffs).")
        },
      )
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
      "register_handoffs" -> registerHandoffs(args)
      "add_handoff" -> addHandoff(args)
      "navigate_to" -> navigateTo(args)
      "list_handoffs" -> listHandoffs(args)
      "clear_handoffs" -> clearHandoffs(args)
      "mark_handoff_done" -> markHandoffDone(args)
      "remove_handoff" -> removeHandoff(args)
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
    if (path.isNullOrBlank()) return null
    val norm = path.trimEnd('/')
    return ProjectManager.getInstance().openProjects
      .mapNotNull { p -> p.basePath?.trimEnd('/')?.let { base -> p to base } }
      .filter { (_, base) -> norm == base || norm.startsWith("$base/") }
      .maxByOrNull { (_, base) -> base.length }
      ?.first
  }

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
    return textContent(
      "Registered ${list.size} handoff(s) for project '${project.name}'. " +
        "The human can now step through them in the IDE.",
      isError = false,
    )
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
    return textContent("Added handoff #$n in '${project.name}'.", isError = false)
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

  private fun listHandoffs(args: JsonObject?): JsonObject {
    val path = args?.get("projectPath")?.jsonPrimitive?.contentOrNull
    val project = resolveProject(path)
      ?: return textContent("No open project matches path: $path", isError = true)
    val ring = project.service<AvecMoiRingService>()
    // reading .line touches the marker's document, so compute under a read lock (we're off the EDT).
    val text = ReadAction.compute<String, RuntimeException> {
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
    return if (project.service<AvecMoiRingService>().markDone(n - 1, true)) {
      textContent("Marked handoff #$n done in '${project.name}'.", isError = false)
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
    server?.stop(0)
    server = null
  }

  companion object {
    const val PORT = 8282

    /**
     * Names of the tools registered in [toolsListResult] — keep in sync. Surfaced by `ping` so an
     * agent can detect it's on a stale tool list after a rebuild ("server has N, I see fewer").
     */
    val TOOL_NAMES = listOf(
      "ping", "list_projects", "resolve_project", "register_handoffs", "add_handoff", "navigate_to",
      "list_handoffs", "clear_handoffs", "mark_handoff_done", "remove_handoff",
    )

    /**
     * Server-level guidance surfaced to the model on connect (MCP InitializeResult.instructions).
     * This is where avec-moi's conventions live — it travels with the server, so every session
     * gets it with no per-project setup. Guidance, not a rigid workflow: the model decides specifics.
     */
    private val INSTRUCTIONS = """
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
      - Every handoff needs a specific note: what to implement here and why it matters.
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
