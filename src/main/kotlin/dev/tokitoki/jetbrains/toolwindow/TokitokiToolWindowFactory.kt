package dev.tokitoki.jetbrains.toolwindow

import com.intellij.ide.BrowserUtil
import com.intellij.openapi.Disposable
import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.project.DumbAware
import com.intellij.openapi.project.DumbAwareAction
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.Disposer
import com.intellij.openapi.wm.ToolWindow
import com.intellij.openapi.wm.ToolWindowFactory
import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.JBPanel
import com.intellij.ui.components.JBScrollPane
import com.intellij.ui.content.ContentFactory
import com.intellij.ui.dsl.builder.AlignX
import com.intellij.ui.dsl.builder.panel
import com.intellij.util.ui.JBFont
import com.intellij.util.ui.JBUI
import com.intellij.util.ui.UIUtil
import dev.tokitoki.jetbrains.BuildConfig
import dev.tokitoki.jetbrains.StatusListener
import dev.tokitoki.jetbrains.TokitokiBundle
import dev.tokitoki.jetbrains.TokitokiService
import dev.tokitoki.jetbrains.cli.StatsReport
import dev.tokitoki.jetbrains.cli.TokitokiCli
import dev.tokitoki.jetbrains.project.ProjectNames
import java.awt.BorderLayout
import java.util.concurrent.atomic.AtomicBoolean
import javax.swing.JComponent
import javax.swing.SwingConstants

/** Days of local history the panel reads. A week is what a person can hold
 * in their head; anything longer is the dashboard's job. */
private const val STATS_DAYS = 7

/**
 * A small panel that points the way: for a new user, what Tokitoki does and
 * the one thing to do next (set a key); for a returning one, today and the
 * week in two numbers and the door to the dashboard, where everything else
 * lives. Reads `tokitoki stats` — local data, so it shows something real
 * before a key exists.
 */
class TokitokiToolWindowFactory : ToolWindowFactory, DumbAware {
    override fun createToolWindowContent(project: Project, toolWindow: ToolWindow) {
        val panel = TokitokiPanel(project)
        Disposer.register(toolWindow.disposable, panel)
        val content = ContentFactory.getInstance().createContent(panel.component, null, false)
        toolWindow.contentManager.addContent(content)
        toolWindow.setTitleActions(listOf(RefreshAction(panel)))
    }

    private class RefreshAction(private val panel: TokitokiPanel) :
        DumbAwareAction(TokitokiBundle.message("toolWindow.refresh"), null, com.intellij.icons.AllIcons.Actions.Refresh) {
        override fun getActionUpdateThread(): ActionUpdateThread = ActionUpdateThread.BGT
        override fun actionPerformed(event: AnActionEvent) = panel.refresh()
    }
}

class TokitokiPanel(private val project: Project) : StatusListener, Disposable {
    val component: JComponent = JBPanel<JBPanel<*>>(BorderLayout())
    private val refreshing = AtomicBoolean(false)

    init {
        ApplicationManager.getApplication().messageBus.connect(this).subscribe(TokitokiService.STATUS_TOPIC, this)
        refresh()
    }

    /** Something the service knows changed (key, sync, today's figure). */
    override fun statusChanged() = refresh()

    fun refresh() {
        if (!refreshing.compareAndSet(false, true)) return
        ApplicationManager.getApplication().executeOnPooledThread {
            val service = TokitokiService.getInstance()
            val apiKeyMissing = service.apiKeyMissing
            val report = try {
                TokitokiCli().stats(STATS_DAYS, ProjectNames.of(project))
            } catch (error: Exception) {
                null
            }
            val scanned = service.scanned
            refreshing.set(false)
            UIUtil.invokeLaterIfNeeded {
                if (project.isDisposed) return@invokeLaterIfNeeded
                component.removeAll()
                component.add(JBScrollPane(render(apiKeyMissing, scanned, report)).apply { border = JBUI.Borders.empty() }, BorderLayout.CENTER)
                component.revalidate()
                component.repaint()
            }
        }
    }

    private fun render(apiKeyMissing: Boolean, scanned: Boolean, report: StatsReport?): JComponent = panel {
        row {
            text(TokitokiBundle.message("toolWindow.pitch"))
        }
        if (apiKeyMissing) {
            row {
                text(TokitokiBundle.message("toolWindow.noKey", BuildConfig.BASE_URL))
            }
            row {
                button(TokitokiBundle.message("action.setApiKey")) { TokitokiService.getInstance().setApiKey(project) }
                link(TokitokiBundle.message("toolWindow.getKey")) { BrowserUtil.browse(BuildConfig.BASE_URL) }
            }
        }
        separator()
        when {
            report == null && !scanned -> row { comment(TokitokiBundle.message("toolWindow.scanning")) }
            report == null -> row { comment(TokitokiBundle.message("toolWindow.unavailable")) }
            else -> {
                // The window's project when the report has one, the account
                // otherwise — the same choice the status bar makes.
                val shown = report.project ?: report
                val today = shown.daily.lastOrNull()
                row {
                    cell(tile(TokitokiBundle.message("toolWindow.today"), TokitokiService.formatDuration(today?.active_seconds ?: 0)))
                    cell(tile(TokitokiBundle.message("toolWindow.week", STATS_DAYS), TokitokiService.formatDuration(shown.totals.active_seconds)))
                    cell(tile(TokitokiBundle.message("toolWindow.tokens"), TokitokiService.formatTokens(today?.total_tokens ?: 0)))
                }
            }
        }
        separator()
        row {
            comment(TokitokiBundle.message("toolWindow.more"))
        }
        row {
            button(TokitokiBundle.message("toolWindow.openDashboard")) { TokitokiService.getInstance().openDashboard(project) }
                .align(AlignX.LEFT)
        }
    }.apply { border = JBUI.Borders.empty(12) }

    private fun tile(caption: String, value: String): JComponent =
        JBPanel<JBPanel<*>>(BorderLayout()).apply {
            border = JBUI.Borders.empty(4, 0, 4, 16)
            add(JBLabel(value).apply { font = JBFont.h1().asBold(); horizontalAlignment = SwingConstants.LEFT }, BorderLayout.CENTER)
            add(JBLabel(caption).apply { foreground = UIUtil.getContextHelpForeground(); font = JBFont.small() }, BorderLayout.SOUTH)
        }

    override fun dispose() = Unit
}
