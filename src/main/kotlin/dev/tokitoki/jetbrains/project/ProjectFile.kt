package dev.tokitoki.jetbrains.project

import java.io.IOException
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path

/**
 * Tokitoki's per-project identity file, read by the CLI from the nearest
 * ancestor of the edited file (tokitoki-cli/internal/projectfile). Line 1 is
 * the project name, line 2 an optional branch override — this plugin owns
 * line 1 and leaves the rest of the file exactly as the user wrote it.
 */
object ProjectFile {
    const val NAME = ".tokitoki"

    fun path(folder: Path): Path = folder.resolve(NAME)

    /** The pinned project name, or "" when the file is absent, unreadable, or
     * its first line is blank. All three mean the same thing to the CLI: no
     * override, fall back to the folder name. */
    fun readProjectName(folder: Path): String = firstLine(read(folder))

    /** Rewrites line 1 in place, creating the file when it does not exist. */
    fun writeProjectName(folder: Path, name: String) {
        Files.writeString(path(folder), replaceFirstLine(read(folder), name), StandardCharsets.UTF_8)
    }

    private fun read(folder: Path): String =
        try {
            Files.readString(path(folder), StandardCharsets.UTF_8)
        } catch (error: IOException) {
            ""
        }

    fun firstLine(content: String): String =
        content.split(LINE_BREAK).firstOrNull().orEmpty().removePrefix(BOM).trim()

    /** Everything after line 1 survives untouched. Existing CRLF endings are
     * kept so the file does not churn in a repository that uses them. */
    fun replaceFirstLine(content: String, name: String): String {
        val eol = if (content.contains(CRLF)) CRLF else "\n"
        val lines = content.split(LINE_BREAK).toMutableList()
        if (lines.size == 1) return name + eol
        lines[0] = name
        return lines.joinToString(eol)
    }

    private val LINE_BREAK = Regex("\r?\n")
    private const val CRLF = "\r\n"
    private const val BOM = "﻿"
}
