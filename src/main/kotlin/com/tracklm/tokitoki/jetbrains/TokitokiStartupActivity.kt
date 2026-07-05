package com.tracklm.tokitoki.jetbrains

import com.intellij.openapi.project.Project
import com.intellij.openapi.startup.StartupActivity

class TokitokiStartupActivity : StartupActivity.Background {
    override fun runActivity(project: Project) {
        TokitokiProjectService.getInstance(project).start()
    }
}
