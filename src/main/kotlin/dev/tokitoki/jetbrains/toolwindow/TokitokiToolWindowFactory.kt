package dev.tokitoki.jetbrains.toolwindow

import com.intellij.ide.BrowserUtil
import com.intellij.openapi.Disposable
import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.project.DumbAware
import com.intellij.openapi.project.DumbAwareAction
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.ComboBox
import com.intellij.openapi.util.Disposer
import com.intellij.openapi.wm.ToolWindow
import com.intellij.openapi.wm.ToolWindowFactory
import com.intellij.ui.SimpleListCellRenderer
import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.JBPanel
import com.intellij.ui.components.JBScrollPane
import com.intellij.ui.content.ContentFactory
import com.intellij.ui.dsl.builder.AlignX
import com.intellij.ui.dsl.builder.Panel
import com.intellij.ui.dsl.builder.Row
import com.intellij.ui.dsl.builder.TopGap
import com.intellij.ui.dsl.builder.panel
import com.intellij.util.ui.JBFont
import com.intellij.util.ui.JBUI
import com.intellij.util.ui.UIUtil
import dev.tokitoki.jetbrains.BuildConfig
import dev.tokitoki.jetbrains.StatusListener
import dev.tokitoki.jetbrains.TokitokiBundle
import dev.tokitoki.jetbrains.TokitokiService
import dev.tokitoki.jetbrains.cli.StatsDaily
import dev.tokitoki.jetbrains.cli.StatsReport
import dev.tokitoki.jetbrains.cli.TokitokiCli
import dev.tokitoki.jetbrains.project.ProjectNames
import java.awt.BorderLayout
import java.awt.GridLayout
import java.util.concurrent.atomic.AtomicBoolean
import javax.swing.JComponent

/** Days of local history the panel reads, and the only window it ever shows.
 * A week is what a person can hold in their head; anything longer is the
 * dashboard's job, not a sidebar's. */
private const val STATS_DAYS = 7

/** How many rows each ranking shows. The full list is one click away on the
 * dashboard; a sidebar ranks, it does not enumerate. */
private const val TOP_PROJECTS = 5
private const val TOP_MODELS = 3

/** Selector value meaning "no project scope". Cannot collide with a real
 * name: a newline appears in no folder or pinned name. */
private const val ALL = "\nall"

/**
 * The sidebar, one to one with the VS Code extension's stats view: a project
 * selector, today in three numbers, the week as bars, where the time and the
 * tokens went, and the door to the dashboard. Reads `tokitoki stats` — local
 * data, so it shows something real before a key exists.
 */
class TokitokiToolWindowFactory : ToolWindowFactory, DumbAware {
    override fun createToolWindowContent(project: Project, toolWindow: ToolWindow) {
        val panel = TokitokiPanel(project)
        Disposer.register(toolWindow.disposable, panel)
        toolWindow.contentManager.addContent(ContentFactory.getInstance().createContent(panel.component, null, false))
        toolWindow.setTitleActions(listOf(RefreshAction(panel)))
    }

    private class RefreshAction(private val panel: TokitokiPanel) :
        DumbAwareAction(TokitokiBundle.message("toolWindow.refresh"), null, com.intellij.icons.AllIcons.Actions.Refresh) {
        override fun getActionUpdateThread(): ActionUpdateThread = ActionUpdateThread.BGT
        override fun actionPerformed(event: AnActionEvent) = panel.refresh()
    }
}

/** A selector entry. `name` is [ALL] for every project. */
private data class Choice(val name: String, val label: String)

private class State(
    val report: StatsReport?,
    val projectName: String?,
    val windowProject: String?,
    val apiKeyMissing: Boolean,
    val scanned: Boolean,
)

class TokitokiPanel(private val project: Project) : StatusListener, Disposable {
    val component: JComponent = JBPanel<JBPanel<*>>(BorderLayout())
    private val refreshing = AtomicBoolean(false)

    /**
     * The selector's pick. Null follows the window's own project; a name pins
     * one; [ALL] asks for every project. Selecting is a filter, not a
     * preference: it lives as long as the panel does.
     */
    @Volatile
    private var selected: String? = null

    init {
        ApplicationManager.getApplication().messageBus.connect(this).subscribe(TokitokiService.STATUS_TOPIC, this)
        refresh()
    }

    override fun statusChanged() = refresh()

    fun refresh() {
        if (!refreshing.compareAndSet(false, true)) return
        ApplicationManager.getApplication().executeOnPooledThread {
            val service = TokitokiService.getInstance()
            val windowProject = ProjectNames.of(project)
            val active = selected ?: windowProject
            val projectName = active?.takeIf { it != ALL }
            // One CLI call returns the global report with the project's
            // sub-report nested inside it: the selector lists from the global
            // one while every section reads the scoped one.
            val report = try {
                TokitokiCli().stats(STATS_DAYS, projectName)
            } catch (error: Exception) {
                null
            }
            val state = State(report, projectName, windowProject, service.apiKeyMissing, service.scanned)
            refreshing.set(false)
            UIUtil.invokeLaterIfNeeded {
                if (project.isDisposed) return@invokeLaterIfNeeded
                component.removeAll()
                component.add(JBScrollPane(render(state)).apply { border = JBUI.Borders.empty() }, BorderLayout.CENTER)
                component.revalidate()
                component.repaint()
            }
        }
    }

    private fun render(state: State): JComponent {
        val report = state.report
        return panel {
            when {
                report == null -> row { banner(TokitokiBundle.message("toolWindow.unavailable")) }
                report.totals.events == 0L && !state.scanned -> row { banner(TokitokiBundle.message("toolWindow.scanning")) }
                report.totals.events == 0L -> {
                    row { banner(TokitokiBundle.message("toolWindow.noActivity")) }
                    dashboardCard(state.apiKeyMissing)
                }
                else -> {
                    // The selector sets the scope for the whole panel, so every
                    // section reads one report. Picking a project makes the
                    // projects ranking a chart of one bar, so it only appears
                    // in the all-projects view.
                    val scope = report.project ?: report
                    row { cell(selector(report, state.projectName, state.windowProject)).align(AlignX.FILL) }
                    row { cell(headline(scope)).align(AlignX.FILL) }.topGap(TopGap.SMALL)
                    section(TokitokiBundle.message("toolWindow.week", STATS_DAYS), TokitokiService.formatDuration(scope.daily.sumOf { it.active_seconds }))
                    row { cell(DailyChart(scope.daily)).align(AlignX.FILL) }
                    if (report.project == null) topProjects(report)
                    topModels(scope)
                    dashboardCard(state.apiKeyMissing)
                }
            }
        }.apply { border = JBUI.Borders.empty(10, 12) }
    }

    /** The scope picker. The window's own project heads the list even when
     * it has no recorded time yet, then every other project, most active
     * first. */
    private fun selector(report: StatsReport, active: String?, windowProject: String?): JComponent {
        val ranked = report.projects.filter { it.active_seconds > 0 }.sortedByDescending { it.active_seconds }.map { it.name }
        val names = listOfNotNull(windowProject) + ranked.filter { it != windowProject }
        val choices = listOf(Choice(ALL, TokitokiBundle.message("toolWindow.allProjects"))) + names.map {
            Choice(it, if (it == windowProject) TokitokiBundle.message("toolWindow.thisWindow", it) else it)
        }
        val current = active ?: ALL
        return ComboBox(choices.toTypedArray()).apply {
            renderer = SimpleListCellRenderer.create("") { it.label }
            selectedItem = choices.firstOrNull { it.name == current } ?: choices.first()
            addActionListener {
                val pick = selectedItem as? Choice ?: return@addActionListener
                if (pick.name != current) {
                    selected = pick.name
                    refresh()
                }
            }
        }
    }

    /** Today, in three numbers. Which project they describe is the
     * selector's business, so the headline never repeats it. */
    private fun headline(report: StatsReport): JComponent {
        val today = report.daily.lastOrNull()
        val streak = codingStreak(report.daily)
        val streakText = if (streak >= STATS_DAYS) "$STATS_DAYS+" else streak.toString()
        return JBPanel<JBPanel<*>>(GridLayout(1, 3, JBUI.scale(6), 0)).apply {
            add(Tile(TokitokiService.formatDuration(today?.active_seconds ?: 0), TokitokiBundle.message("toolWindow.today"), TIME_COLOR))
            add(Tile(TokitokiService.formatTokens(today?.total_tokens ?: 0), TokitokiBundle.message("toolWindow.tokens"), AI_COLOR))
            add(Tile("$streakText${TokitokiBundle.message("toolWindow.streakUnit")}", TokitokiBundle.message("toolWindow.streak"), null))
        }
    }

    /** Consecutive days with coding activity, counting back from the most
     * recent day. Today not having started yet must not break a run. */
    private fun codingStreak(daily: List<StatsDaily>): Int {
        var streak = 0
        for (i in daily.indices.reversed()) {
            if (daily[i].active_seconds > 0) streak++ else if (i != daily.lastIndex) break
        }
        return streak
    }

    /** Where the time went, ranked, two bars per project: time in green,
     * tokens in blue. The total on the right counts every project touched,
     * not the visible five. */
    private fun Panel.topProjects(report: StatsReport) {
        val projects = report.projects.filter { it.active_seconds > 0 }.sortedByDescending { it.active_seconds }
        if (projects.size < 2) return
        val top = projects.take(TOP_PROJECTS)
        val maxTime = top.maxOf { it.active_seconds }.coerceAtLeast(1).toDouble()
        val maxTokens = top.maxOf { it.total_tokens }.coerceAtLeast(1).toDouble()
        section(TokitokiBundle.message("toolWindow.projects"), TokitokiBundle.message("toolWindow.total", projects.size))
        for (group in top) {
            row {
                cell(
                    RankedRow(
                        group.name,
                        TokitokiService.formatDuration(group.active_seconds),
                        group.active_seconds / maxTime,
                        TIME_COLOR,
                        group.total_tokens / maxTokens,
                    ),
                ).align(AlignX.FILL)
            }
        }
    }

    /** Which models the tokens went to — the fact users cannot get from
     * their editor. */
    private fun Panel.topModels(report: StatsReport) {
        val models = report.models.filter { it.total_tokens > 0 }.sortedByDescending { it.total_tokens }.take(TOP_MODELS)
        if (models.isEmpty()) return
        val max = models.maxOf { it.total_tokens }.coerceAtLeast(1).toDouble()
        section(TokitokiBundle.message("toolWindow.topModels"), TokitokiService.formatTokens(report.totals.total_tokens))
        for (group in models) {
            row {
                cell(RankedRow(group.name, TokitokiService.formatTokens(group.total_tokens), group.total_tokens / max, AI_COLOR)).align(AlignX.FILL)
            }
        }
    }

    /** A section heading with its own total on the right, so each block
     * states its scale without spending a tile on it. */
    private fun Panel.section(title: String, total: String) {
        row {
            cell(JBLabel(title).apply { font = JBFont.label().asBold() })
            cell(JBLabel(total).apply { font = JBFont.small(); foreground = UIUtil.getContextHelpForeground() }).align(AlignX.RIGHT)
        }.topGap(TopGap.MEDIUM)
    }

    /** The panel's one exit. Without a key the same card carries a second,
     * quieter action; the primary one still opens the site, where a keyless
     * visitor can actually start. */
    private fun Panel.dashboardCard(apiKeyMissing: Boolean) {
        row { banner(TokitokiBundle.message("toolWindow.seeMore")) }.topGap(TopGap.MEDIUM)
        row {
            button(TokitokiBundle.message("toolWindow.openDashboard")) {
                if (apiKeyMissing) BrowserUtil.browse(BuildConfig.BASE_URL) else TokitokiService.getInstance().openDashboard(project)
            }.align(AlignX.FILL)
        }
        if (apiKeyMissing) {
            row {
                link(TokitokiBundle.message("toolWindow.haveKey")) { TokitokiService.getInstance().setApiKey(project) }
            }
        }
    }

    private fun Row.banner(text: String) = comment(text)

    override fun dispose() = Unit
}
