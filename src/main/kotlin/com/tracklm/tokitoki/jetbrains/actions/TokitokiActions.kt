package com.tracklm.tokitoki.jetbrains.actions

import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.ui.Messages
import com.tracklm.tokitoki.jetbrains.CommandResult
import com.tracklm.tokitoki.jetbrains.TokitokiCli
import com.tracklm.tokitoki.jetbrains.TokitokiNotifier
import com.tracklm.tokitoki.jetbrains.TokitokiProjectService

class SyncNowAction : AnAction() {
    override fun actionPerformed(event: AnActionEvent) {
        val project = event.project ?: return
        TokitokiProjectService.getInstance(project).sync("manual", userInitiated = true)
    }
}

class SetApiKeyAction : AnAction() {
    override fun actionPerformed(event: AnActionEvent) {
        val project = event.project
        val apiKey = Messages.showPasswordDialog(project, "TokiToki API key:", "Set TokiToki API Key", null, null)
            ?.trim()
            ?: return
        if (apiKey.isEmpty()) {
            TokitokiNotifier.error(project, "TokiToki API key is required.")
            return
        }
        val service = project?.let { TokitokiProjectService.getInstance(it) }
        if (service != null) {
            service.runCliCommand("TokiToki API key saved.") { it.setApiKey(apiKey) }
        } else {
            ApplicationManager.getApplication().executeOnPooledThread {
                TokitokiCli(null).setApiKey(apiKey)
            }
        }
    }
}

class ShowApiKeyStatusAction : AnAction() {
    override fun actionPerformed(event: AnActionEvent) {
        val project = event.project ?: return
        TokitokiProjectService.getInstance(project).runCliCommand("TokiToki API key is configured.") {
            val result = it.getApiKey()
            val masked = maskApiKey(result.stdout)
            CommandResult(masked, result.stderr)
        }
    }
}

abstract class ServiceAction(private val action: String, private val label: String) : AnAction() {
    override fun actionPerformed(event: AnActionEvent) {
        val project = event.project ?: return
        TokitokiProjectService.getInstance(project).runCliCommand("TokiToki service $label completed.") {
            it.service(action)
        }
    }
}

class ServiceInstallAction : ServiceAction("install", "install")
class ServiceStartAction : ServiceAction("start", "start")
class ServiceStopAction : ServiceAction("stop", "stop")
class ServiceRestartAction : ServiceAction("restart", "restart")
class ServiceStatusAction : ServiceAction("status", "status")

private fun maskApiKey(apiKey: String): String {
    val trimmed = apiKey.trim()
    if (trimmed.length <= 8) return "configured"
    return "${trimmed.take(4)}...${trimmed.takeLast(4)}"
}
