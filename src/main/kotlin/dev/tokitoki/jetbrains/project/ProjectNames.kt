package dev.tokitoki.jetbrains.project

import com.intellij.openapi.project.Project
import dev.tokitoki.jetbrains.cli.TokitokiCli
import java.nio.file.Path

/**
 * Which project a JetBrains project's work is filed under. The CLI decides —
 * a pinned `.tokitoki` name, the repository around the folder, or the folder
 * itself — and every reader (the status bar, the tool window, Set Project
 * Name) asks it, so they all name the project the heartbeats land in.
 * Touches disk and runs the CLI; not for the EDT.
 */
object ProjectNames {
    fun folder(project: Project?): Path? = project?.basePath?.let { Path.of(it) }

    /** The IDE's own name for the project, sent on heartbeats for the CLI to
     * fall back on. The CLI reads `.tokitoki` itself; repeating it here would
     * only be a second copy of its rules. */
    fun hint(project: Project?): String? = project?.takeIf { !it.isDisposed }?.name

    fun of(project: Project?): String? {
        if (project == null || project.isDisposed) return null
        val folder = folder(project) ?: return project.name
        return TokitokiCli().project(folder.toString(), project.name).project
    }
}
