package dev.tokitoki.jetbrains

import dev.tokitoki.jetbrains.cli.SemanticVersion
import dev.tokitoki.jetbrains.project.ProjectFile
import dev.tokitoki.jetbrains.tracking.DEFAULT_THROTTLE_MS
import dev.tokitoki.jetbrains.tracking.HeartbeatThrottler
import dev.tokitoki.jetbrains.tracking.Language
import dev.tokitoki.jetbrains.tracking.LineChanges
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path

class HeartbeatThrottlerTest {
    @Test
    fun `sends the first heartbeat and throttles repeats`() {
        val throttler = HeartbeatThrottler()
        assertTrue(throttler.shouldSend("/a.kt", "coding", 0, false))
        assertFalse(throttler.shouldSend("/a.kt", "coding", 1000, false))
        assertFalse(throttler.shouldSend("/a.kt", "coding", DEFAULT_THROTTLE_MS - 1, false))
        assertTrue(throttler.shouldSend("/a.kt", "coding", DEFAULT_THROTTLE_MS, false))
    }

    @Test
    fun `writes always pass and reset the clock`() {
        val throttler = HeartbeatThrottler()
        assertTrue(throttler.shouldSend("/a.kt", "coding", 0, false))
        assertTrue(throttler.shouldSend("/a.kt", "coding", 1000, true))
        assertFalse(throttler.shouldSend("/a.kt", "coding", 2000, false))
    }

    @Test
    fun `a changed file or category passes immediately`() {
        val throttler = HeartbeatThrottler()
        assertTrue(throttler.shouldSend("/a.kt", "coding", 0, false))
        assertTrue(throttler.shouldSend("/b.kt", "coding", 1, false))
        assertTrue(throttler.shouldSend("/b.kt", "debugging", 2, false))
        assertFalse(throttler.shouldSend("/b.kt", "debugging", 3, false))
    }
}

class LineChangesTest {
    @Test
    fun `typed newlines count, one heartbeat carries them once`() {
        val changes = LineChanges()
        changes.record("/a.kt", "\n", "")
        changes.record("/a.kt", "\n    ", "")
        changes.record("/a.kt", "", "x\ny\n")
        assertEquals(LineChanges.Delta(2, 2), changes.take("/a.kt"))
        assertEquals(LineChanges.Delta(0, 0), changes.take("/a.kt"))
    }

    @Test
    fun `bulk inserts count for nothing`() {
        val changes = LineChanges()
        changes.record("/a.kt", "fun main() {\n}\n", "")
        assertFalse(changes.has("/a.kt"))
    }

    @Test
    fun `a character on an existing line changes no line count`() {
        val changes = LineChanges()
        changes.record("/a.kt", "x", "")
        assertFalse(changes.has("/a.kt"))
    }
}

class LanguageTest {
    @Test
    fun `canonical names pass, IDE spellings translate, unknowns yield nothing`() {
        assertEquals("Kotlin", Language.name("Kotlin"))
        assertEquals("Java", Language.name("JAVA"))
        assertEquals("Bash", Language.name("Shell Script"))
        assertEquals("Text", Language.name("PLAIN_TEXT"))
        assertNull(Language.name("SomeVendorType"))
        assertNull(Language.name(null))
    }
}

class SemanticVersionTest {
    @Test
    fun `parses release versions and nothing else`() {
        assertEquals(SemanticVersion(0, 1, 9), SemanticVersion.parse("0.1.9\n"))
        assertEquals(SemanticVersion(0, 1, 9), SemanticVersion.parse("v0.1.9"))
        assertNull(SemanticVersion.parse("dev"))
        assertNull(SemanticVersion.parse("1.2"))
    }

    @Test
    fun `compares component-wise`() {
        assertTrue(SemanticVersion(0, 1, 9) > SemanticVersion(0, 1, 8))
        assertTrue(SemanticVersion(9999, 0, 1) > SemanticVersion(0, 1, 9))
        assertTrue(SemanticVersion(0, 2, 0) > SemanticVersion(0, 1, 99))
    }
}

class ProjectFileTest {
    @Test
    fun `first line is the name, BOM and whitespace stripped`() {
        assertEquals("tracklm", ProjectFile.firstLine("﻿ tracklm \nbranch"))
        assertEquals("", ProjectFile.firstLine(""))
    }

    @Test
    fun `replaces line one and keeps the rest and the line endings`() {
        assertEquals("new\r\nmain\r\n", ProjectFile.replaceFirstLine("old\r\nmain\r\n", "new"))
        assertEquals("new\n", ProjectFile.replaceFirstLine("", "new"))
        assertEquals("new\nmain", ProjectFile.replaceFirstLine("old\nmain", "new"))
    }

    @Test
    fun `reads and writes the file in a folder`(@TempDir folder: Path) {
        assertEquals("", ProjectFile.readProjectName(folder))
        ProjectFile.writeProjectName(folder, "pinned")
        assertEquals("pinned", ProjectFile.readProjectName(folder))
        Files.writeString(folder.resolve(ProjectFile.NAME), "pinned\nfeature-branch\n")
        ProjectFile.writeProjectName(folder, "renamed")
        assertEquals("renamed\nfeature-branch\n", Files.readString(folder.resolve(ProjectFile.NAME)))
    }
}
