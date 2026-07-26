package com.tracklm.tokitoki.jetbrains.settings

import com.intellij.openapi.options.Configurable
import com.intellij.openapi.ui.ComboBox
import com.intellij.ui.components.JBCheckBox
import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.JBTextArea
import com.intellij.ui.components.JBTextField
import com.intellij.util.ui.JBUI
import java.awt.GridBagConstraints
import java.awt.GridBagLayout
import javax.swing.JComponent
import javax.swing.JPanel
import javax.swing.JScrollPane
import javax.swing.JSpinner
import javax.swing.SpinnerNumberModel

class TokitokiConfigurable : Configurable {
    private val settings = TokitokiSettings.getInstance()
    private var panel: JPanel? = null
    private val enabled = JBCheckBox("Enable Tokitoki integration")
    private val autoSync = JBCheckBox("Sync on startup and interval")
    private val syncOnSave = JBCheckBox("Sync after file saves")
    private val syncOnEdit = JBCheckBox("Sync after editor activity")
    private val showNotifications = JBCheckBox("Show command notifications")
    private val interval = JSpinner(SpinnerNumberModel(5, 1, 24 * 60, 1))
    private val timeout = JSpinner(SpinnerNumberModel(140, 5, 3600, 5))
    private val baseUrl = JBTextField()
    private val providerDirs = JBTextArea(5, 42)
    private val logLevel = ComboBox(arrayOf("debug", "info", "warn", "error"))

    override fun getDisplayName(): String = "Tokitoki"

    override fun createComponent(): JComponent {
        val root = JPanel(GridBagLayout()).apply { border = JBUI.Borders.empty(12) }
        var row = 0
        fun constraints(x: Int, fillMode: Int = GridBagConstraints.HORIZONTAL): GridBagConstraints =
            GridBagConstraints().apply {
                gridx = x
                gridy = row
                fill = fillMode
                anchor = GridBagConstraints.WEST
                insets = JBUI.insets(4)
                weightx = if (x == 1) 1.0 else 0.0
            }

        fun field(label: String, component: JComponent) {
            root.add(JBLabel(label), constraints(0))
            root.add(component, constraints(1))
            row++
        }

        root.add(enabled, constraints(0).apply { gridwidth = 2 })
        row++
        root.add(autoSync, constraints(0).apply { gridwidth = 2 })
        row++
        root.add(syncOnSave, constraints(0).apply { gridwidth = 2 })
        row++
        root.add(syncOnEdit, constraints(0).apply { gridwidth = 2 })
        row++
        root.add(showNotifications, constraints(0).apply { gridwidth = 2 })
        row++
        field("Sync interval minutes:", interval)
        field("Command timeout seconds:", timeout)
        field("Base URL:", baseUrl)
        field("Log level:", logLevel)
        root.add(JBLabel("Provider dirs:"), constraints(0, GridBagConstraints.NONE))
        root.add(JScrollPane(providerDirs), constraints(1, GridBagConstraints.BOTH).apply { weighty = 1.0 })
        row++
        panel = root
        reset()
        return root
    }

    override fun isModified(): Boolean {
        val state = settings.state
        return enabled.isSelected != state.enabled ||
            autoSync.isSelected != state.autoSync ||
            syncOnSave.isSelected != state.syncOnSave ||
            syncOnEdit.isSelected != state.syncOnEdit ||
            showNotifications.isSelected != state.showNotifications ||
            interval.value as Int != state.syncIntervalMinutes ||
            timeout.value as Int != state.commandTimeoutSeconds ||
            baseUrl.text.trim() != state.baseUrl ||
            providerDirs.text.lines().map { it.trim() }.filter { it.isNotEmpty() } != state.providerDirs ||
            logLevel.selectedItem as String != state.logLevel
    }

    override fun apply() {
        settings.update {
            it.enabled = enabled.isSelected
            it.autoSync = autoSync.isSelected
            it.syncOnSave = syncOnSave.isSelected
            it.syncOnEdit = syncOnEdit.isSelected
            it.showNotifications = showNotifications.isSelected
            it.syncIntervalMinutes = interval.value as Int
            it.commandTimeoutSeconds = timeout.value as Int
            it.baseUrl = baseUrl.text
            it.providerDirs = providerDirs.text.lines().map { line -> line.trim() }.filter { line -> line.isNotEmpty() }.toMutableList()
            it.logLevel = logLevel.selectedItem as String
        }
    }

    override fun reset() {
        val state = settings.state
        enabled.isSelected = state.enabled
        autoSync.isSelected = state.autoSync
        syncOnSave.isSelected = state.syncOnSave
        syncOnEdit.isSelected = state.syncOnEdit
        showNotifications.isSelected = state.showNotifications
        interval.value = state.syncIntervalMinutes
        timeout.value = state.commandTimeoutSeconds
        baseUrl.text = state.baseUrl
        providerDirs.text = state.providerDirs.joinToString("\n")
        logLevel.selectedItem = state.logLevel
    }
}
