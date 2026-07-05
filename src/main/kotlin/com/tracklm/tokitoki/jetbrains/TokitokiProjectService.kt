package com.tracklm.tokitoki.jetbrains

import com.intellij.AppTopics
import com.intellij.openapi.Disposable
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.service
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.editor.EditorFactory
import com.intellij.openapi.editor.event.BulkAwareDocumentListener
import com.intellij.openapi.editor.event.DocumentEvent
import com.intellij.openapi.fileEditor.FileDocumentManagerListener
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.Disposer
import com.intellij.util.concurrency.AppExecutorUtil
import com.tracklm.tokitoki.jetbrains.settings.TokitokiSettings
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

@Service(Service.Level.PROJECT)
class TokitokiProjectService(private val project: Project) : Disposable {
    private val log = Logger.getInstance(TokitokiProjectService::class.java)
    private val running = AtomicBoolean(false)
    private var timer: ScheduledFuture<*>? = null
    private var lastActivitySyncAt = 0L
    private var started = false

    fun start() {
        if (started) return
        started = true
        log.info("Starting TokiToki integration for ${project.name}")
        registerListeners()
        restartTimer()
        val state = TokitokiSettings.getInstance().state
        if (state.enabled && state.autoSync) {
            sync("startup", userInitiated = false)
        }
    }

    fun sync(reason: String, userInitiated: Boolean) {
        val state = TokitokiSettings.getInstance().state
        if (!state.enabled) {
            if (userInitiated) TokitokiNotifier.info(project, "TokiToki is disabled.")
            return
        }
        if (!running.compareAndSet(false, true)) {
            if (userInitiated) TokitokiNotifier.info(project, "TokiToki sync is already running.")
            return
        }
        AppExecutorUtil.getAppExecutorService().execute {
            try {
                log.info("Running TokiToki sync ($reason)")
                val result = TokitokiCli(project).sync()
                logCommandOutput(result)
                if (userInitiated) TokitokiNotifier.info(project, "TokiToki sync completed.")
            } catch (error: Throwable) {
                handleError("TokiToki sync failed.", error)
            } finally {
                running.set(false)
            }
        }
    }

    fun runCliCommand(successMessage: String, command: (TokitokiCli) -> CommandResult) {
        AppExecutorUtil.getAppExecutorService().execute {
            try {
                val result = command(TokitokiCli(project))
                logCommandOutput(result)
                TokitokiNotifier.info(project, successMessage)
            } catch (error: Throwable) {
                handleError(successMessage.removeSuffix(".") + " failed.", error)
            }
        }
    }

    fun restartTimer() {
        timer?.cancel(false)
        timer = null
        val state = TokitokiSettings.getInstance().state
        if (!state.enabled || !state.autoSync) return
        timer = AppExecutorUtil.getAppScheduledExecutorService().scheduleWithFixedDelay(
            { sync("interval", userInitiated = false) },
            state.syncIntervalMinutes.toLong(),
            state.syncIntervalMinutes.toLong(),
            TimeUnit.MINUTES,
        )
    }

    override fun dispose() {
        timer?.cancel(false)
    }

    private fun registerListeners() {
        val connection = ApplicationManager.getApplication().messageBus.connect(this)
        connection.subscribe(
            AppTopics.FILE_DOCUMENT_SYNC,
            object : FileDocumentManagerListener {
                override fun beforeAllDocumentsSaving() {
                    handleActivity("save-all")
                }

                override fun beforeDocumentSaving(document: com.intellij.openapi.editor.Document) {
                    if (TokitokiSettings.getInstance().state.syncOnSave) {
                        handleActivity("save")
                    }
                }
            },
        )

        val listenerDisposable = Disposer.newDisposable("TokitokiEditorListeners")
        Disposer.register(this, listenerDisposable)
        EditorFactory.getInstance().eventMulticaster.addDocumentListener(
            object : BulkAwareDocumentListener.Simple {
                override fun documentChangedNonBulk(event: DocumentEvent) {
                    if (TokitokiSettings.getInstance().state.syncOnEdit) {
                        handleActivity("edit")
                    }
                }
            },
            listenerDisposable,
        )
    }

    private fun handleActivity(reason: String) {
        val now = System.currentTimeMillis()
        if (now - lastActivitySyncAt < 60_000) return
        lastActivitySyncAt = now
        sync(reason, userInitiated = false)
    }

    private fun logCommandOutput(result: CommandResult) {
        if (result.stdout.trim().isNotEmpty()) log.debug("tokitoki stdout: ${result.stdout.trim()}")
        if (result.stderr.trim().isNotEmpty()) log.warn("tokitoki stderr: ${result.stderr.trim()}")
    }

    private fun handleError(message: String, error: Throwable) {
        if (error is TokitokiCliException) {
            log.warn("$message ${error.message}; command=${error.command}; stdout=${error.stdout.trim()}; stderr=${error.stderr.trim()}")
        } else {
            log.warn(message, error)
        }
        TokitokiNotifier.error(project, message)
    }

    companion object {
        fun getInstance(project: Project): TokitokiProjectService = project.service()
    }
}
