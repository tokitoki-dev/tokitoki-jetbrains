package com.tracklm.tokitoki.jetbrains

import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import kotlin.io.path.isRegularFile

object PathUtil {
    fun expandPath(rawPath: String, env: Map<String, String> = System.getenv()): String {
        var expanded = rawPath.trim()
        if (expanded.isEmpty()) return ""
        val home = System.getProperty("user.home")
        if (expanded == "~") {
            expanded = home
        } else if (expanded.startsWith("~/") || expanded.startsWith("~\\")) {
            expanded = Paths.get(home, expanded.substring(2)).toString()
        }
        val pattern = Regex("""\$(\w+)|\$\{([^}]+)}|%([^%]+)%""")
        return pattern.replace(expanded) {
            val name = it.groups[1]?.value ?: it.groups[2]?.value ?: it.groups[3]?.value.orEmpty()
            env[name] ?: it.value
        }
    }

    fun normalizeProviderDir(rawValue: String): String? {
        val separator = rawValue.indexOf('=')
        if (separator <= 0 || separator == rawValue.length - 1) return null
        val provider = rawValue.substring(0, separator).trim()
        val dir = expandPath(rawValue.substring(separator + 1))
        if (provider.isEmpty() || dir.isEmpty()) return null
        return "$provider=$dir"
    }

    fun isExecutableFile(candidate: Path): Boolean =
        Files.exists(candidate) && candidate.isRegularFile() &&
            (System.getProperty("os.name").lowercase().contains("windows") || Files.isExecutable(candidate))

}
