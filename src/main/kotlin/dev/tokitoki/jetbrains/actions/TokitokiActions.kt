package dev.tokitoki.jetbrains.actions

import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.project.DumbAwareAction
import dev.tokitoki.jetbrains.TokitokiService

// Tools > Tokitoki. Each action is a verb the service already knows.

abstract class TokitokiAction : DumbAwareAction() {
    override fun getActionUpdateThread(): ActionUpdateThread = ActionUpdateThread.BGT
}

class SetApiKeyAction : TokitokiAction() {
    override fun actionPerformed(event: AnActionEvent) = TokitokiService.getInstance().setApiKey(event.project)
}

class OpenDashboardAction : TokitokiAction() {
    override fun actionPerformed(event: AnActionEvent) = TokitokiService.getInstance().openDashboard(event.project)
}

class ShowApiKeyStatusAction : TokitokiAction() {
    override fun actionPerformed(event: AnActionEvent) = TokitokiService.getInstance().showApiKeyStatus(event.project)
}

class SetProjectNameAction : TokitokiAction() {
    override fun update(event: AnActionEvent) {
        event.presentation.isEnabled = event.project?.basePath != null
    }

    override fun actionPerformed(event: AnActionEvent) {
        val project = event.project ?: return
        TokitokiService.getInstance().setProjectName(project)
    }
}

class SyncNowAction : TokitokiAction() {
    override fun actionPerformed(event: AnActionEvent) =
        TokitokiService.getInstance().syncNow(event.project, userInitiated = true)
}
