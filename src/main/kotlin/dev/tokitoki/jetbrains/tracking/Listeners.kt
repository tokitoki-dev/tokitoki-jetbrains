package dev.tokitoki.jetbrains.tracking

import com.intellij.execution.ExecutionListener
import com.intellij.execution.executors.DefaultDebugExecutor
import com.intellij.execution.process.ProcessHandler
import com.intellij.execution.runners.ExecutionEnvironment
import com.intellij.openapi.application.ApplicationActivationListener
import com.intellij.openapi.editor.Document
import com.intellij.openapi.fileEditor.FileDocumentManagerListener
import com.intellij.openapi.fileEditor.FileEditorManagerEvent
import com.intellij.openapi.fileEditor.FileEditorManagerListener
import com.intellij.openapi.fileEditor.TextEditor
import com.intellij.openapi.vfs.newvfs.BulkFileListener
import com.intellij.openapi.vfs.newvfs.events.VFileCreateEvent
import com.intellij.openapi.vfs.newvfs.events.VFileDeleteEvent
import com.intellij.openapi.vfs.newvfs.events.VFileEvent
import com.intellij.openapi.vfs.newvfs.events.VFileMoveEvent
import com.intellij.openapi.vfs.newvfs.events.VFilePropertyChangeEvent
import com.intellij.openapi.wm.IdeFrame
import com.intellij.openapi.wm.ToolWindow
import com.intellij.openapi.wm.ex.ToolWindowManagerListener
import dev.tokitoki.jetbrains.TokitokiService

// The listeners plugin.xml declares. The platform instantiates each on its
// first event, so none of them cost anything at startup, and each is one
// line: name the event, hand it to the tracker.

private val tracker get() = TokitokiService.getInstance().tracker

/** Coming back to the IDE window. */
class WindowActivationListener : ApplicationActivationListener {
    override fun applicationActivated(ideFrame: IdeFrame) {
        tracker.onEvent(ideFrame.project, null, false)
    }
}

/** Saving a file: always a write heartbeat. */
class SaveListener : FileDocumentManagerListener {
    override fun beforeDocumentSaving(document: Document) {
        tracker.onDocument(document, true)
    }
}

/** Switching editor tabs, including to a diff. */
class EditorSelectionListener : FileEditorManagerListener {
    override fun selectionChanged(event: FileEditorManagerEvent) {
        tracker.onEvent(event.manager.project, (event.newEditor as? TextEditor)?.editor, false)
    }
}

/** Creating, renaming, moving or deleting files from the IDE. Changes the
 * IDE merely noticed on disk (`isFromRefresh`) are somebody else's work. */
class FileOperationsListener : BulkFileListener {
    override fun after(events: List<VFileEvent>) {
        val own = events.any { !it.isFromRefresh && it.isUserOperation() }
        if (own) tracker.onEvent(null, null, true)
    }

    private fun VFileEvent.isUserOperation(): Boolean =
        this is VFileCreateEvent || this is VFileDeleteEvent || this is VFileMoveEvent ||
            (this is VFilePropertyChangeEvent && propertyName == VirtualFilePropertyName.NAME)

    private object VirtualFilePropertyName {
        const val NAME = "name"
    }
}

/** Running and debugging. A debug session flips the category while it lasts. */
class RunListener : ExecutionListener {
    override fun processStarted(executorId: String, env: ExecutionEnvironment, handler: ProcessHandler) {
        if (executorId == DefaultDebugExecutor.EXECUTOR_ID) tracker.debugStarted(env.project) else tracker.onEvent(env.project, null, false)
    }

    override fun processTerminated(executorId: String, env: ExecutionEnvironment, handler: ProcessHandler, exitCode: Int) {
        if (executorId == DefaultDebugExecutor.EXECUTOR_ID) tracker.debugStopped(env.project)
    }
}

/** Opening a tool window — the terminal, a run console, version control.
 * There is no per-keystroke terminal event; the window opening is the tell. */
class ToolWindowListener : ToolWindowManagerListener {
    override fun toolWindowShown(toolWindow: ToolWindow) {
        tracker.onEvent(toolWindow.project, null, false)
    }
}
