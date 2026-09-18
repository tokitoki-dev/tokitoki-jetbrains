package dev.tokitoki.jetbrains

import com.intellij.ide.BrowserUtil
import com.intellij.notification.NotificationAction
import com.intellij.notification.NotificationGroupManager
import com.intellij.notification.NotificationType
import com.intellij.openapi.Disposable
import com.intellij.openapi.application.ApplicationInfo
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.application.ApplicationNamesInfo
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.service
import com.intellij.openapi.diagnostic.logger
import com.intellij.openapi.project.Project
import com.intellij.openapi.project.ProjectManager
import com.intellij.openapi.ui.Messages
import com.intellij.openapi.wm.WindowManager
import com.intellij.util.concurrency.AppExecutorUtil
import com.intellij.util.messages.Topic
import dev.tokitoki.jetbrains.cli.TodayReport
import dev.tokitoki.jetbrains.cli.TokitokiCli
import dev.tokitoki.jetbrains.cli.TokitokiCliException
import dev.tokitoki.jetbrains.project.ProjectFile
import dev.tokitoki.jetbrains.project.ProjectNames
import dev.tokitoki.jetbrains.settings.TokitokiSettings
import dev.tokitoki.jetbrains.statusbar.TokitokiStatusBarWidgetFactory
import dev.tokitoki.jetbrains.tracking.ActivityTracker
import dev.tokitoki.jetbrains.tracking.Heartbeat
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

private const val SYNC_INTERVAL_MINUTES = 5L
private const val UPDATE_CHECK_INTERVAL_MS = 24 * 60 * 60 * 1000L

/** A heartbeat lands about every two minutes per file; the figure it moves
 * does not need re-reading more often than this. Fresh uploads (a sync)
 * bypass it. */
private const val TODAY_REFRESH_MIN_MS = 60 * 1000L

/** Listeners the status bar widgets hang on: something they show changed. */
interface StatusListener {
    fun statusChanged()
}

/**
 * The plugin, as one application-level object: the activity tracker, the
 * sync timer, the API key state, and today's figure for the status bar. One
 * instance serves every project window, the way one CLI and one key serve
 * every Tokitoki client on the machine.
 */
@Service(Service.Level.APP)
class TokitokiService : Disposable {
    private val log = logger<TokitokiService>()
    val tracker = ActivityTracker(this) { heartbeat, project -> sendHeartbeat(heartbeat, project) }

    // Heartbeats are serialized so a slow upload never piles up parallel CLI
    // processes; the CLI queues events locally either way, so order is not
    // correctness.
    private val heartbeats = AppExecutorUtil.createBoundedApplicationPoolExecutor("Tokitoki heartbeats", 1)
    private val started = AtomicBoolean(false)
    private val syncRunning = AtomicBoolean(false)
    private val todayRefreshing = AtomicBoolean(false)
    private var syncTimer: ScheduledFuture<*>? = null
    private var promptedForApiKey = false

    @Volatile
    var apiKeyMissing = false
        private set

    /** Today's figure as the server last reported it. */
    @Volatile
    var today: TodayReport? = null
        private set

    @Volatile
    private var todayFetchedAt = 0L

    fun start() {
        if (!started.compareAndSet(false, true)) return
        log.info("Tokitoki ${BuildConfig.PLUGIN_VERSION} starting: server ${BuildConfig.BASE_URL}, data dir ~/${BuildConfig.DATA_DIR}")
        ApplicationManager.getApplication().executeOnPooledThread {
            // Seed the shared CLI before the first invocation so everything
            // binds to the shared copy, then let the CLI update itself.
            try {
                TokitokiCli().bootstrapSharedCli()
            } catch (error: Exception) {
                log.warn("Failed to seed shared CLI: ${error.message}")
            }
            try {
                log.info("CLI binary: ${TokitokiCli().resolveExecutable()}")
            } catch (error: Exception) {
                log.warn("No usable CLI binary: ${error.message}")
            }
            checkApiKey()
            if (apiKeyMissing) promptForApiKeyOnce()
            updateSharedCliDaily()
        }
        ApplicationManager.getApplication().invokeLater { tracker.start() }
        syncTimer = AppExecutorUtil.getAppScheduledExecutorService()
            .scheduleWithFixedDelay({ syncNow(null) }, 0, SYNC_INTERVAL_MINUTES, TimeUnit.MINUTES)
    }

    /** One AI usage scan-and-upload run. Runs with or without a key: the CLI
     * scans locally and skips only the upload when no key is set. */
    fun syncNow(project: Project?, userInitiated: Boolean = false) {
        if (!syncRunning.compareAndSet(false, true)) {
            if (userInitiated) notify(project, TokitokiBundle.message("notify.sync.running"), NotificationType.INFORMATION)
            return
        }
        ApplicationManager.getApplication().executeOnPooledThread {
            try {
                checkApiKey()
                TokitokiCli().sync()
                if (userInitiated) notify(project, TokitokiBundle.message("notify.sync.done"), NotificationType.INFORMATION)
                // The sync just uploaded the AI events; the figure moved.
                refreshToday(project, force = true)
            } catch (error: Exception) {
                log.warn("Tokitoki sync failed: ${error.message}")
                if (userInitiated) notify(project, TokitokiBundle.message("notify.sync.failed"), NotificationType.WARNING)
            } finally {
                syncRunning.set(false)
            }
        }
    }

    private fun sendHeartbeat(heartbeat: Heartbeat, project: Project?) {
        heartbeats.execute {
            try {
                val complete = heartbeat.copy(
                    project = ProjectNames.of(project),
                    projectFolder = ProjectNames.folder(project)?.toString(),
                )
                TokitokiCli().heartbeat(complete, editorName(), pluginUserAgent())
                log.debug("Heartbeat sent: ${heartbeat.entity} (${heartbeat.category})")
                refreshToday(project, force = false)
            } catch (error: TokitokiCliException) {
                if (error.isMissingApiKey) {
                    apiKeyMissing = true
                    publishStatus()
                    promptForApiKeyOnce()
                } else {
                    log.warn("Heartbeat failed: ${error.message}")
                }
            } catch (error: Exception) {
                log.warn("Heartbeat failed: ${error.message}")
            }
        }
    }

    /**
     * Re-reads today's figure from the server through the CLI, for the
     * project the window has open. Rate-limited to once a minute unless
     * `force`. Runs only with a key: without one there is no account to ask
     * about, and the tooltip says so instead.
     */
    fun refreshToday(project: Project?, force: Boolean) {
        if (apiKeyMissing || !todayRefreshing.compareAndSet(false, true)) return
        val now = System.currentTimeMillis()
        if (!force && now - todayFetchedAt < TODAY_REFRESH_MIN_MS) {
            todayRefreshing.set(false)
            return
        }
        todayFetchedAt = now
        ApplicationManager.getApplication().executeOnPooledThread {
            try {
                val projectName = ProjectNames.of(project ?: ProjectManager.getInstance().openProjects.firstOrNull())
                today = TokitokiCli().today(projectName)
            } catch (error: TokitokiCliException) {
                if (error.isMissingApiKey) {
                    apiKeyMissing = true
                    today = null
                } else {
                    // An old shared CLI without `today`, or the server and the
                    // cache both unavailable: the item keeps what it last showed.
                    log.debug("Today unavailable: ${error.message}")
                }
            } catch (error: Exception) {
                log.debug("Today unavailable: ${error.message}")
            } finally {
                todayRefreshing.set(false)
                publishStatus()
            }
        }
    }

    /** The status bar's text: this window's project today, the account total
     * in a window with no folder, the name alone before any figure. */
    fun statusText(): String {
        val report = today?.takeIf { !apiKeyMissing }
        val figure = report?.project?.text ?: report?.text
        return if (figure != null && TokitokiSettings.getInstance().state.statusBarShowTime) figure else "Tokitoki"
    }

    /** The tooltip is the numbers and nothing else: one line per figure, then
     * only what changes their meaning (a team scope, an outage). */
    fun statusTooltip(): String {
        val lines = mutableListOf<String>()
        val report = today?.takeIf { !apiKeyMissing }
        if (report != null) {
            report.project?.let {
                lines += "${it.name} · ${it.text} · ${TokitokiBundle.message("status.tokens", formatTokens(it.total_tokens))}"
            }
            lines += "${TokitokiBundle.message("status.allProjects")} · ${report.text} · ${TokitokiBundle.message("status.tokens", formatTokens(report.total_tokens))}"
            if (report.scope == "team") lines += TokitokiBundle.message("status.team", report.team_name ?: "")
            if (report.stale) lines += TokitokiBundle.message("status.offline")
        } else if (apiKeyMissing) {
            lines += TokitokiBundle.message("status.noKey")
        } else {
            lines += TokitokiBundle.message("status.tracking")
        }
        return lines.joinToString("<br>", prefix = "<html>", postfix = "</html>")
    }

    fun settingsChanged() = publishStatus()

    // ----- commands ---------------------------------------------------------

    fun setApiKey(project: Project?) {
        ApplicationManager.getApplication().invokeLater {
            val apiKey = Messages.showPasswordDialog(
                project,
                TokitokiBundle.message("dialog.apiKey.message", BuildConfig.BASE_URL),
                TokitokiBundle.message("dialog.apiKey.title"),
                Messages.getQuestionIcon(),
            )?.trim()
            if (apiKey.isNullOrEmpty()) return@invokeLater
            ApplicationManager.getApplication().executeOnPooledThread {
                try {
                    TokitokiCli().setApiKey(apiKey)
                    apiKeyMissing = false
                    publishStatus()
                    notify(project, TokitokiBundle.message("notify.apiKey.saved"), NotificationType.INFORMATION)
                    // A user who just set a key wants data flowing now.
                    syncNow(project)
                } catch (error: Exception) {
                    log.warn("Unable to save API key: ${error.message}")
                    notify(project, TokitokiBundle.message("notify.apiKey.saveFailed"), NotificationType.ERROR)
                }
            }
        }
    }

    fun showApiKeyStatus(project: Project?) {
        ApplicationManager.getApplication().executeOnPooledThread {
            val masked: String
            try {
                masked = maskApiKey(TokitokiCli().getApiKey())
            } catch (error: Exception) {
                notifyMissingKey(project)
                return@executeOnPooledThread
            }
            val valid = try {
                TokitokiCli().verifyApiKey()
            } catch (error: Exception) {
                notify(project, TokitokiBundle.message("notify.apiKey.unverifiable"), NotificationType.WARNING)
                return@executeOnPooledThread
            }
            if (valid) {
                notify(project, TokitokiBundle.message("notify.apiKey.valid", masked), NotificationType.INFORMATION)
            } else {
                notify(project, TokitokiBundle.message("notify.apiKey.rejected", masked), NotificationType.WARNING, setKeyAction(project))
            }
        }
    }

    /** The dashboard, signed in through a one-time login URL when a key is
     * set; the site otherwise. */
    fun openDashboard(project: Project?) {
        ApplicationManager.getApplication().executeOnPooledThread {
            val url = try {
                TokitokiCli().dashboardUrl()
            } catch (error: Exception) {
                BuildConfig.BASE_URL
            }
            BrowserUtil.browse(url)
        }
    }

    /** Pins the project name the CLI reports for this project by writing the
     * first line of its `.tokitoki` file. Nothing to restart: every heartbeat
     * resolves that file from disk. */
    fun setProjectName(project: Project) {
        val folder = ProjectNames.folder(project) ?: return
        ApplicationManager.getApplication().executeOnPooledThread {
            val current = ProjectNames.of(project) ?: project.name
            ApplicationManager.getApplication().invokeLater {
                val name = Messages.showInputDialog(
                    project,
                    TokitokiBundle.message("dialog.projectName.message", ProjectFile.NAME),
                    TokitokiBundle.message("dialog.projectName.title"),
                    Messages.getQuestionIcon(),
                    current,
                    null,
                )?.trim()
                if (name.isNullOrEmpty() || name == current) return@invokeLater
                ApplicationManager.getApplication().executeOnPooledThread {
                    try {
                        ProjectFile.writeProjectName(folder, name)
                        notify(project, TokitokiBundle.message("notify.projectName.saved", name), NotificationType.INFORMATION)
                        refreshToday(project, force = true)
                    } catch (error: Exception) {
                        log.warn("Unable to write ${ProjectFile.NAME}: ${error.message}")
                        notify(project, TokitokiBundle.message("notify.projectName.failed"), NotificationType.ERROR)
                    }
                }
            }
        }
    }

    // ----- internals --------------------------------------------------------

    private fun checkApiKey() {
        val missing = try {
            TokitokiCli().getApiKey()
            false
        } catch (error: Exception) {
            true
        }
        if (missing != apiKeyMissing) {
            apiKeyMissing = missing
            publishStatus()
        }
    }

    /** One prompt per session: the notification stays until acted on, and a
     * user who dismissed it has answered. */
    private fun promptForApiKeyOnce() {
        if (promptedForApiKey) return
        promptedForApiKey = true
        val project = ProjectManager.getInstance().openProjects.firstOrNull()
        notifyMissingKey(project)
    }

    private fun notifyMissingKey(project: Project?) {
        notify(project, TokitokiBundle.message("notify.apiKey.missing", BuildConfig.BASE_URL), NotificationType.WARNING, setKeyAction(project))
    }

    private fun setKeyAction(project: Project?) =
        NotificationAction.createSimpleExpiring(TokitokiBundle.message("action.setApiKey")) { setApiKey(project) }

    private fun updateSharedCliDaily() {
        val settings = TokitokiSettings.getInstance().state
        val now = System.currentTimeMillis()
        if (now - settings.lastUpdateCheckAt < UPDATE_CHECK_INTERVAL_MS) return
        settings.lastUpdateCheckAt = now
        try {
            TokitokiCli().update()
        } catch (error: Exception) {
            log.debug("CLI update check failed: ${error.message}")
        }
    }

    private fun notify(project: Project?, content: String, type: NotificationType, action: NotificationAction? = null) {
        val notification = NotificationGroupManager.getInstance()
            .getNotificationGroup("Tokitoki")
            .createNotification(content, type)
        if (action != null) notification.addAction(action)
        notification.notify(project?.takeIf { !it.isDisposed })
    }

    private fun publishStatus() {
        ApplicationManager.getApplication().messageBus.syncPublisher(STATUS_TOPIC).statusChanged()
        for (project in ProjectManager.getInstance().openProjects) {
            WindowManager.getInstance().getStatusBar(project)?.updateWidget(TokitokiStatusBarWidgetFactory.ID)
        }
    }

    private fun editorName(): String = ApplicationNamesInfo.getInstance().fullProductName

    private fun pluginUserAgent(): String =
        "${editorName()}/${ApplicationInfo.getInstance().fullVersion} tokitoki-jetbrains/${BuildConfig.PLUGIN_VERSION}"

    override fun dispose() {
        syncTimer?.cancel(false)
        syncTimer = null
    }

    companion object {
        val STATUS_TOPIC: Topic<StatusListener> = Topic.create("Tokitoki status", StatusListener::class.java)

        fun getInstance(): TokitokiService = service()

        fun maskApiKey(apiKey: String): String {
            val trimmed = apiKey.trim()
            if (trimmed.length <= 8) return "configured"
            return "${trimmed.take(4)}...${trimmed.takeLast(4)}"
        }

        /** "1.2M" / "812.0K" / "947" — the spelling the VS Code extension uses,
         * so a number never changes shape between clients. */
        fun formatTokens(tokens: Long): String = when {
            tokens >= 1_000_000_000 -> String.format(java.util.Locale.ROOT, "%.1fB", tokens / 1_000_000_000.0)
            tokens >= 1_000_000 -> String.format(java.util.Locale.ROOT, "%.1fM", tokens / 1_000_000.0)
            tokens >= 1_000 -> String.format(java.util.Locale.ROOT, "%.1fK", tokens / 1_000.0)
            else -> tokens.toString()
        }
    }
}
