package dev.tokitoki.jetbrains

import com.intellij.openapi.project.Project
import com.intellij.openapi.startup.ProjectActivity

/** The first project window to open starts the plugin; later ones find it
 * running. */
class TokitokiStartup : ProjectActivity {
    override suspend fun execute(project: Project) {
        TokitokiService.getInstance().start()
    }
}
