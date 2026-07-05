package com.tracklm.tokitoki.jetbrains.settings

import com.intellij.openapi.components.PersistentStateComponent
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.State
import com.intellij.openapi.components.Storage
import com.intellij.openapi.components.service

@Service(Service.Level.APP)
@State(name = "TokitokiSettings", storages = [Storage("tokitoki.xml")])
class TokitokiSettings : PersistentStateComponent<TokitokiSettings.State> {
    data class State(
        var enabled: Boolean = true,
        var autoSync: Boolean = true,
        var syncOnSave: Boolean = false,
        var syncOnEdit: Boolean = true,
        var syncIntervalMinutes: Int = 5,
        var providerDirs: MutableList<String> = mutableListOf(),
        var baseUrl: String = "http://localhost:9093",
        var showNotifications: Boolean = true,
        var commandTimeoutSeconds: Int = 140,
        var logLevel: String = "info",
    )

    private var state = State()

    override fun getState(): State = state

    override fun loadState(state: State) {
        this.state = state.normalized()
    }

    fun update(block: (State) -> Unit) {
        val copy = state.copy(providerDirs = state.providerDirs.toMutableList())
        block(copy)
        state = copy.normalized()
    }

    private fun State.normalized(): State {
        syncIntervalMinutes = syncIntervalMinutes.coerceAtLeast(1)
        commandTimeoutSeconds = commandTimeoutSeconds.coerceAtLeast(5)
        providerDirs = providerDirs.map { it.trim() }.filter { it.isNotEmpty() }.toMutableList()
        baseUrl = baseUrl.trim().ifEmpty { "http://localhost:9093" }
        logLevel = if (logLevel in setOf("debug", "info", "warn", "error")) logLevel else "info"
        return this
    }

    companion object {
        fun getInstance(): TokitokiSettings = service()
    }
}
