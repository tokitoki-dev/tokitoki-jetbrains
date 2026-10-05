package dev.tokitoki.jetbrains.tracking

/** One activity sample, as the CLI's `heartbeat` command takes it. */
data class Heartbeat(
    val entity: String,
    val timeSeconds: Double,
    /** The IDE's name for the project, offered for the CLI to fall back on.
     * Never `--project`: that would override the repository the CLI detects,
     * and is left to plugin builds released before it detected any. */
    val alternateProject: String?,
    val projectFolder: String?,
    /** The shared language name, when the IDE's file type translates to one. */
    val language: String?,
    val category: String,
    val isWrite: Boolean,
    val lineNumber: Int,
    val cursorPosition: Int,
    val linesInFile: Int,
    val linesAdded: Int,
    val linesRemoved: Int,
)
