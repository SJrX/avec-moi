package net.sjrx.intellij.plugins.avecmoi

import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.fileEditor.OpenFileDescriptor
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.io.FileUtil
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.openapi.vfs.VirtualFile

/** Shared file resolution + editor navigation, used by both the nav action and the navigate_to tool. */
object AvecMoiNavigation {

  fun resolveFile(project: Project, file: String): VirtualFile? =
    LocalFileSystem.getInstance().findFileByPath(absolutePath(project, file))

  private fun absolutePath(project: Project, file: String): String =
    if (FileUtil.isAbsolute(file)) file else "${project.basePath}/$file"

  /**
   * Bring the IDE's view of [files] up to date with the disk, synchronously. The agent writes files
   * outside the IDE and registers handoffs on them milliseconds later; until a refresh, the VFS doesn't
   * know a new file exists, and an open document still holds a changed file's old text. The IDE
   * refreshes on regaining focus, but working in its own terminal never loses focus (#45). Refreshes
   * only these paths: a known file is re-read (unmodified open documents reload), an unknown one is
   * looked up on disk. Must not be called inside a read action.
   */
  fun refreshFromDisk(project: Project, files: Collection<String>) {
    val lfs = LocalFileSystem.getInstance()
    for (path in files.map { absolutePath(project, it) }.distinct()) {
      val known = lfs.findFileByPath(path)
      if (known != null) known.refresh(false, false) else lfs.refreshAndFindFileByPath(path)
    }
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
