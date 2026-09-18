package dev.tokitoki.jetbrains.project

import com.intellij.openapi.project.Project
import java.nio.file.Path

/**
 * The name a project reports on its heartbeats: the pinned `.tokitoki` name
 * when set, the project's own name otherwise — so every reader (the status
 * bar, Set Project Name) asks the server about the same project the
 * heartbeats file under. Touches disk; not for the EDT.
 */
object ProjectNames {
    fun folder(project: Project?): Path? = project?.basePath?.let { Path.of(it) }

    fun of(project: Project?): String? {
        if (project == null || project.isDisposed) return null
        val folder = folder(project) ?: return project.name
        return try {
            ProjectFile.readProjectName(folder).ifEmpty { project.name }
        } catch (error: Exception) {
            project.name
        }
    }
}
