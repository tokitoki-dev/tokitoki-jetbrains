package dev.tokitoki.jetbrains.statusbar

import com.intellij.openapi.Disposable
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.IconLoader
import com.intellij.openapi.wm.CustomStatusBarWidget
import com.intellij.openapi.wm.StatusBar
import com.intellij.openapi.wm.StatusBarWidget
import com.intellij.openapi.wm.StatusBarWidgetFactory
import com.intellij.ui.components.JBLabel
import com.intellij.util.ui.JBUI
import com.intellij.util.ui.UIUtil
import dev.tokitoki.jetbrains.StatusListener
import dev.tokitoki.jetbrains.TokitokiBundle
import dev.tokitoki.jetbrains.TokitokiService
import dev.tokitoki.jetbrains.settings.TokitokiSettings
import java.awt.event.MouseAdapter
import java.awt.event.MouseEvent
import javax.swing.JComponent

/** Today's active time in the status bar. Click to open the dashboard. */
class TokitokiStatusBarWidgetFactory : StatusBarWidgetFactory {
    override fun getId(): String = ID

    override fun getDisplayName(): String = TokitokiBundle.message("statusBar.displayName")

    // Always installed; the plugin's own switch hides the component, so
    // flipping it needs no re-registration and no internal widget manager.
    override fun isAvailable(project: Project): Boolean = true

    override fun createWidget(project: Project): StatusBarWidget = TokitokiStatusBarWidget(project)

    override fun canBeEnabledOn(statusBar: StatusBar): Boolean = true

    companion object {
        const val ID = "tokitoki.today"
    }
}

class TokitokiStatusBarWidget(private val project: Project) : CustomStatusBarWidget, StatusListener, Disposable {
    private val label = JBLabel().apply {
        icon = IconLoader.getIcon("/icons/tokitoki.svg", TokitokiStatusBarWidget::class.java)
        border = JBUI.Borders.empty(0, 4)
        addMouseListener(
            object : MouseAdapter() {
                override fun mouseClicked(event: MouseEvent) {
                    TokitokiService.getInstance().openDashboard(project)
                }
            },
        )
    }

    override fun ID(): String = TokitokiStatusBarWidgetFactory.ID

    override fun getComponent(): JComponent = label

    override fun install(statusBar: StatusBar) {
        ApplicationManager.getApplication().messageBus.connect(this).subscribe(TokitokiService.STATUS_TOPIC, this)
        statusChanged()
    }

    override fun statusChanged() {
        val service = TokitokiService.getInstance()
        val text = service.statusText()
        val tooltip = service.statusTooltip()
        val visible = TokitokiSettings.getInstance().state.statusBarEnabled
        UIUtil.invokeLaterIfNeeded {
            label.text = text
            label.toolTipText = tooltip
            label.isVisible = visible
            label.revalidate()
            label.repaint()
        }
    }

    // The status bar disposes the widget through Disposer, which tears down
    // the message bus connection registered on it; nothing else to release.
    override fun dispose() = Unit
}
