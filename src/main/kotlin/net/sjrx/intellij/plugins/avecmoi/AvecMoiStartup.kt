package net.sjrx.intellij.plugins.avecmoi

import com.intellij.openapi.components.service
import com.intellij.openapi.project.Project
import com.intellij.openapi.startup.ProjectActivity

/**
 * Boots the app-level avec-moi MCP server. Fires per project open, but the server is a single
 * application service, so the first open starts it and later opens are no-ops. The server outlives
 * individual projects and serves them all.
 */
class AvecMoiStartup : ProjectActivity {
  override suspend fun execute(project: Project) {
    service<AvecMoiMcpService>().startIfNeeded()
    project.service<AvecMoiRingService>().materializeMarkers()
  }
}
