package dev.tokitoki.jetbrains.tracking

import com.intellij.diff.editor.DiffVirtualFileBase
import com.intellij.openapi.Disposable
import com.intellij.openapi.editor.Document
import com.intellij.openapi.editor.Editor
import com.intellij.openapi.editor.EditorFactory
import com.intellij.openapi.editor.event.BulkAwareDocumentListener
import com.intellij.openapi.editor.event.CaretEvent
import com.intellij.openapi.editor.event.CaretListener
import com.intellij.openapi.editor.event.DocumentEvent
import com.intellij.openapi.editor.event.EditorMouseEvent
import com.intellij.openapi.editor.event.EditorMouseListener
import com.intellij.openapi.editor.event.VisibleAreaEvent
import com.intellij.openapi.editor.event.VisibleAreaListener
import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.openapi.fileEditor.FileEditorManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.Disposer
import com.intellij.openapi.util.io.FileUtil
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.util.Alarm
import java.util.concurrent.atomic.AtomicInteger

private const val DEBOUNCE_MS = 50

/**
 * Where activity is credited: a file on disk. What the IDE calls the file's
 * type rides along for the language, and whether the file is being read in a
 * diff decides the category.
 */
data class Target(
    val entity: String,
    val fileTypeName: String?,
    val line: Int,
    val column: Int,
    val lineCount: Int,
    val project: Project?,
    val reviewing: Boolean,
)

/**
 * Watches IDE activity and emits throttled activity heartbeats.
 * Bursty events (typing, caret moves) coalesce through a short debounce; the
 * throttler then lets one through per interval unless the file, category, or
 * write flag forces it.
 *
 * Activity is anything the user does in the IDE, not only edits: reading
 * (scrolling, clicking), coming back to the window, switching tabs, opening a
 * tool window, running or debugging, and creating, renaming or deleting files
 * all count. Every one of these is a signal that the user is here; the
 * throttler keeps them to one heartbeat per file per interval, so more
 * sources mean better coverage, not more events. Activity with no text editor
 * behind it — a tool window, the terminal, a diff of virtual documents — is
 * credited to the file the user was last in.
 *
 * Events arrive on the EDT and the flush runs there too, because reading an
 * editor's state requires it; the heartbeat itself is handed off.
 */
class ActivityTracker(
    parent: Disposable,
    private val emit: (Heartbeat, Project?) -> Unit,
) : Disposable {
    private val throttler = HeartbeatThrottler()
    private val lineChanges = LineChanges()
    private val alarm = Alarm(Alarm.ThreadToUse.SWING_THREAD, this)
    private val debugSessions = AtomicInteger()

    // Written by events, read by the flush; all on the EDT.
    private var pendingWrite = false
    private var pendingEditor: Editor? = null
    private var pendingProject: Project? = null
    private var lastTarget: Target? = null

    init {
        Disposer.register(parent, this)
    }

    /** Hooks the editor-level events the platform multicasts. The rest —
     * saves, focus, tab switches, VFS, run — are declared in plugin.xml and
     * call in through [onEvent]. */
    fun start() {
        val multicaster = EditorFactory.getInstance().eventMulticaster
        multicaster.addDocumentListener(
            object : BulkAwareDocumentListener.Simple {
                override fun documentChangedNonBulk(event: DocumentEvent) = onDocumentChanged(event)
            },
            this,
        )
        multicaster.addCaretListener(
            object : CaretListener {
                override fun caretPositionChanged(event: CaretEvent) = onEvent(event.editor.project, event.editor, false)
            },
            this,
        )
        multicaster.addVisibleAreaListener(
            object : VisibleAreaListener {
                override fun visibleAreaChanged(event: VisibleAreaEvent) {
                    val old = event.oldRectangle ?: return
                    val new = event.newRectangle
                    if (new.x != old.x || new.y != old.y) onEvent(event.editor.project, event.editor, false)
                }
            },
            this,
        )
        multicaster.addEditorMouseListener(
            object : EditorMouseListener {
                override fun mousePressed(event: EditorMouseEvent) = onEvent(event.editor.project, event.editor, false)
            },
            this,
        )
    }

    /** Any sign of the user: schedules a flush. `editor` and `project` say
     * where it happened when the event knows; a null pair credits the last
     * target. */
    fun onEvent(project: Project?, editor: Editor?, isWrite: Boolean) {
        // A write within the debounce window must not be downgraded by a
        // trailing caret event, so the flag is sticky until the flush.
        pendingWrite = pendingWrite || isWrite
        if (editor != null) pendingEditor = editor
        if (project != null) pendingProject = project
        alarm.cancelAllRequests()
        alarm.addRequest({ flush() }, DEBOUNCE_MS)
    }

    /** A document's own event, with or without an editor: a save, or an edit
     * whose fragments say whether a person typed it. */
    fun onDocument(document: Document, isWrite: Boolean) {
        val editor = EditorFactory.getInstance().getEditors(document).firstOrNull()
        onEvent(editor?.project, editor, isWrite)
    }

    fun debugStarted(project: Project?) {
        debugSessions.incrementAndGet()
        onEvent(project, null, false)
    }

    fun debugStopped(project: Project?) {
        debugSessions.updateAndGet { maxOf(0, it - 1) }
        onEvent(project, null, false)
    }

    private fun onDocumentChanged(event: DocumentEvent) {
        val entity = localPath(FileDocumentManager.getInstance().getFile(event.document))
        if (entity != null) lineChanges.record(entity, event.newFragment, event.oldFragment)
        onDocument(event.document, false)
    }

    private fun flush() {
        val write = pendingWrite
        pendingWrite = false
        val target = resolveTarget() ?: return
        lastTarget = target

        val category = when {
            debugSessions.get() > 0 -> "debugging"
            target.reviewing -> "code reviewing"
            else -> "coding"
        }
        val now = System.currentTimeMillis()
        if (!throttler.shouldSend(target.entity, category, now, write)) return

        val delta = lineChanges.take(target.entity)
        emit(
            Heartbeat(
                entity = target.entity,
                timeSeconds = now / 1000.0,
                project = null,
                projectFolder = null,
                language = Language.name(target.fileTypeName),
                category = category,
                isWrite = write,
                lineNumber = target.line,
                cursorPosition = target.column,
                linesInFile = target.lineCount,
                linesAdded = delta.added,
                linesRemoved = delta.removed,
            ),
            target.project,
        )
    }

    /**
     * What the user is working on right now: the editor the event came
     * from, else the project's selected text editor, else whatever the last
     * heartbeat went to. Only a file on the local disk qualifies.
     */
    private fun resolveTarget(): Target? {
        val editor = pendingEditor?.takeIf { !it.isDisposed }
            ?: pendingProject?.takeIf { !it.isDisposed }?.let { FileEditorManager.getInstance(it).selectedTextEditor }
        pendingEditor = null
        val project = editor?.project ?: pendingProject?.takeIf { !it.isDisposed }
        val fromEditor = editor?.let { editorTarget(it, project) }
        if (fromEditor != null) return fromEditor
        val last = lastTarget ?: return null
        val lastProject = last.project?.takeIf { !it.isDisposed }
        return last.copy(project = lastProject, reviewing = isReviewing(project ?: lastProject))
    }

    private fun editorTarget(editor: Editor, project: Project?): Target? {
        val document = editor.document
        val entity = localPath(FileDocumentManager.getInstance().getFile(document)) ?: return null
        val file = FileDocumentManager.getInstance().getFile(document)
        val position = editor.caretModel.logicalPosition
        return Target(
            entity = entity,
            fileTypeName = file?.fileType?.name,
            line = position.line + 1,
            column = position.column + 1,
            lineCount = document.lineCount,
            project = project,
            reviewing = isReviewing(project),
        )
    }

    /** Reading a change rather than making one: the selected editor is a
     * diff (git, an agent's proposed edit). The tab is the tell, not the
     * document — the focused side of a git diff is the plain file. */
    private fun isReviewing(project: Project?): Boolean {
        if (project == null || project.isDisposed) return false
        return FileEditorManager.getInstance(project).selectedEditor?.file is DiffVirtualFileBase
    }

    private fun localPath(file: VirtualFile?): String? {
        if (file == null || !file.isInLocalFileSystem || !file.isValid) return null
        return FileUtil.toSystemDependentName(file.path)
    }

    override fun dispose() {
        lineChanges.clear()
        lastTarget = null
    }
}
