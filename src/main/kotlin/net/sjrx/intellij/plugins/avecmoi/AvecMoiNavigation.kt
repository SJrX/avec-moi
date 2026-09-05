package net.sjrx.intellij.plugins.avecmoi

import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.fileEditor.OpenFileDescriptor
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.io.FileUtil
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.openapi.vfs.VirtualFile

/** Shared file resolution + editor navigation, used by both the nav action and the navigate_to tool. */
object AvecMoiNavigation {

  fun resolveFile(project: Project, file: String): VirtualFile? {
    val path = if (FileUtil.isAbsolute(file)) file else "${project.basePath}/$file"
    return LocalFileSystem.getInstance().findFileByPath(path)
  }

  /**
   * Opens [file] at [line1Based] in the editor and moves the caret there. Safe to call from any
   * thread (navigation is dispatched to the EDT). Returns false if the file can't be resolved.
   */
  fun openAt(project: Project, file: String, line1Based: Int): Boolean {
    val vf = resolveFile(project, file) ?: return false
    val line0 = (line1Based - 1).coerceAtLeast(0)
    ApplicationManager.getApplication().invokeLater {
      if (!project.isDisposed) OpenFileDescriptor(project, vf, line0, 0).navigate(true)
    }
    return true
  }
}
