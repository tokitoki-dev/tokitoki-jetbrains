package dev.tokitoki.jetbrains.settings

import com.intellij.openapi.components.BaseState
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.SimplePersistentStateComponent
import com.intellij.openapi.components.State
import com.intellij.openapi.components.Storage
import com.intellij.openapi.components.service

/**
 * The plugin's few settings. Tracking and uploading need no configuration —
 * they start with the IDE and run for the session — and neither the server
 * nor the CLI's location is a setting: both are build stamps.
 */
@Service(Service.Level.APP)
@State(name = "dev.tokitoki.jetbrains", storages = [Storage("tokitoki.xml")])
class TokitokiSettings : SimplePersistentStateComponent<TokitokiSettings.State>(State()) {
    class State : BaseState() {
        var statusBarEnabled by property(true)

        /** Show today's active time in the status bar item; off keeps the
         * icon only and moves the figure to its tooltip. */
        var statusBarShowTime by property(true)

        /** When the shared CLI last checked for an update, epoch millis. */
        var lastUpdateCheckAt by property(0L)
    }

    companion object {
        fun getInstance(): TokitokiSettings = service()
    }
}
