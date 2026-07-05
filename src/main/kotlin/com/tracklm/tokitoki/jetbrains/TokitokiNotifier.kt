package com.tracklm.tokitoki.jetbrains

import com.intellij.notification.NotificationGroupManager
import com.intellij.notification.NotificationType
import com.intellij.openapi.project.Project
import com.tracklm.tokitoki.jetbrains.settings.TokitokiSettings

object TokitokiNotifier {
    fun info(project: Project?, message: String) {
        notify(project, message, NotificationType.INFORMATION)
    }

    fun error(project: Project?, message: String) {
        notify(project, message, NotificationType.ERROR)
    }

    private fun notify(project: Project?, message: String, type: NotificationType) {
        if (!TokitokiSettings.getInstance().state.showNotifications) return
        NotificationGroupManager.getInstance()
            .getNotificationGroup("TokiToki")
            .createNotification(message, type)
            .notify(project)
    }
}
