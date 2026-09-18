package dev.tokitoki.jetbrains.cli

import com.google.gson.Gson
import com.google.gson.JsonSyntaxException
import com.intellij.execution.configurations.GeneralCommandLine
import com.intellij.execution.process.CapturingProcessHandler
import com.intellij.openapi.application.PathManager
import com.intellij.openapi.diagnostic.logger
import com.intellij.openapi.util.SystemInfo
import dev.tokitoki.jetbrains.BuildConfig
import dev.tokitoki.jetbrains.tracking.Heartbeat
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import java.nio.file.StandardCopyOption
import java.util.Locale

/** A single CLI call has no business running longer than this. Long enough
 * for a slow first sync on a bad network, short enough that a wedged process
 * does not hang the plugin forever. */
private const val COMMAND_TIMEOUT_MS = 140_000

/** Exit code the CLI reserves for "no API key is configured". Any other
 * non-zero exit is a transient failure. */
private const val EXIT_NO_API_KEY = 3

class CommandResult(val stdout: String, val stderr: String)

class TokitokiCliException(
    message: String,
    val command: String,
    val exitCode: Int?,
    val stdout: String,
    val stderr: String,
) : RuntimeException(message) {
    /** True only when the CLI reports that no key is configured. Every other
     * failure — offline, server down, timeout — is transient and must not
     * send the user to the key prompt: their key is fine. */
    val isMissingApiKey: Boolean get() = exitCode == EXIT_NO_API_KEY
}

/** The JSON of `tokitoki today` (tokitoki-cli internal/statusbar). */
class TodayReport(
    val date: String = "",
    val timezone: String = "",
    val scope: String = "personal",
    val team_name: String? = null,
    val active_seconds: Long = 0,
    val total_tokens: Long = 0,
    val text: String = "",
    val project: TodayProject? = null,
    val stale: Boolean = false,
)

class TodayProject(
    val name: String = "",
    val active_seconds: Long = 0,
    val total_tokens: Long = 0,
    val text: String = "",
)

/**
 * The shared CLI every Tokitoki client on this machine invokes, and the
 * bundled copy that seeds it. Stateless: every call resolves the binary
 * afresh, so an update landing mid-session is picked up by the next call.
 */
class TokitokiCli {
    private val log = logger<TokitokiCli>()
    private val gson = Gson()

    companion object {
        /**
         * The CLI shared by every Tokitoki client on this machine. The
         * directory is the one this build was stamped with (`.tokitoki` for a
         * release, `.tokitoki-dev` for a dev build), which is also the directory
         * the bundled CLI owns — a dev build never runs, seeds or updates the
         * installed production CLI.
         */
        fun sharedBinaryPath(): Path =
            Paths.get(System.getProperty("user.home"), BuildConfig.DATA_DIR, "bin", executableName())

        fun bundledPlatform(): String {
            val os = when {
                SystemInfo.isMac -> "darwin"
                SystemInfo.isWindows -> "windows"
                SystemInfo.isLinux -> "linux"
                else -> throw IllegalStateException("Unsupported OS for the bundled Tokitoki CLI: ${SystemInfo.OS_NAME}")
            }
            val arch = when (val raw = System.getProperty("os.arch").lowercase()) {
                "x86_64", "amd64" -> "amd64"
                "aarch64", "arm64" -> "arm64"
                else -> throw IllegalStateException("Unsupported architecture for the bundled Tokitoki CLI: $raw")
            }
            return "$os-$arch"
        }

        fun executableName(): String = if (SystemInfo.isWindows) "tokitoki.exe" else "tokitoki"
    }

    /**
     * The bundled CLI, extracted from the plugin's resources into the IDE's
     * system directory, once per plugin version. Resources cannot be run in
     * place and carry no executable bit.
     */
    fun bundledBinaryPath(): Path {
        val platform = bundledPlatform()
        val target = Paths.get(PathManager.getSystemPath(), "tokitoki", BuildConfig.PLUGIN_VERSION, platform, executableName())
        val resource = "/cli/$platform/${executableName()}"
        val stream = javaClass.getResourceAsStream(resource)
            ?: throw IllegalStateException("Bundled Tokitoki CLI is missing: $resource")
        stream.use { input ->
            val size = input.available().toLong()
            if (Files.isRegularFile(target) && size > 0 && Files.size(target) == size) return target
            Files.createDirectories(target.parent)
            val staging = target.resolveSibling("${target.fileName}.extract")
            Files.copy(input, staging, StandardCopyOption.REPLACE_EXISTING)
            markExecutable(staging)
            Files.move(staging, target, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE)
        }
        return target
    }

    /** Shared first, bundled as the fallback. */
    fun resolveExecutable(): Path {
        val shared = sharedBinaryPath()
        if (isExecutable(shared)) return shared
        return bundledBinaryPath()
    }

    /**
     * Seeds the shared CLI from the bundled copy when the shared one is
     * missing or older, then lets `tokitoki update` keep it fresh. Never a
     * downgrade: a bundled CLI older than the shared one leaves the shared one
     * alone, and a bundled build that cannot report a version only fills a
     * hole. Staged and renamed into place so no invocation ever sees a
     * half-written binary.
     */
    fun bootstrapSharedCli() {
        val bundled = bundledBinaryPath()
        val shared = sharedBinaryPath()
        if (isExecutable(shared)) {
            val bundledVersion = binaryVersion(bundled) ?: return
            val sharedVersion = binaryVersion(shared)
            if (sharedVersion != null && sharedVersion >= bundledVersion) return
        }
        Files.createDirectories(shared.parent)
        val staging = shared.resolveSibling("${shared.fileName}.seed")
        Files.copy(bundled, staging, StandardCopyOption.REPLACE_EXISTING)
        markExecutable(staging)
        Files.move(staging, shared, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE)
        log.info("Seeded shared Tokitoki CLI at $shared")
    }

    fun update(): CommandResult = run(listOf("update"))

    /** One AI usage scan-and-upload run. Spelled out as `sync`: a bare
     * `tokitoki` prints usage and exits 0. */
    fun sync(): CommandResult = run(listOf("sync"))

    fun heartbeat(heartbeat: Heartbeat, editor: String, plugin: String): CommandResult {
        val args = mutableListOf(
            "heartbeat",
            "--entity", heartbeat.entity,
            "--time", String.format(Locale.ROOT, "%.3f", heartbeat.timeSeconds),
            "--editor", editor,
            "--plugin", plugin,
            "--category", heartbeat.category,
        )
        heartbeat.project?.let { args += listOf("--project", it) }
        heartbeat.projectFolder?.let { args += listOf("--project-folder", it) }
        heartbeat.language?.let { args += listOf("--language", it) }
        if (heartbeat.isWrite) args += "--write"
        if (heartbeat.lineNumber > 0) args += listOf("--lineno", heartbeat.lineNumber.toString())
        if (heartbeat.cursorPosition > 0) args += listOf("--cursorpos", heartbeat.cursorPosition.toString())
        if (heartbeat.linesInFile > 0) args += listOf("--lines-in-file", heartbeat.linesInFile.toString())
        if (heartbeat.linesAdded > 0) args += listOf("--lines-added", heartbeat.linesAdded.toString())
        if (heartbeat.linesRemoved > 0) args += listOf("--lines-removed", heartbeat.linesRemoved.toString())
        return run(args)
    }

    fun setApiKey(apiKey: String): CommandResult = run(listOf("set", "key", apiKey))

    fun getApiKey(): String = run(listOf("get", "key")).stdout.trim()

    /** True is valid, false is rejected. A check that cannot run throws. */
    fun verifyApiKey(): Boolean =
        parse(run(listOf("verify", "key")).stdout, VerifyResponse::class.java, "verify key").valid

    fun dashboardUrl(): String = run(listOf("get", "dashboard-url")).stdout.trim()

    /** Today's figure from the server, narrowed to `project` when given, or
     * the CLI's cached answer marked stale when offline. */
    fun today(project: String?): TodayReport {
        val args = mutableListOf("today")
        if (!project.isNullOrBlank()) args += listOf("--project", project)
        return parse(run(args).stdout, TodayReport::class.java, "today")
    }

    private class VerifyResponse(val valid: Boolean = false)

    private fun <T> parse(stdout: String, type: Class<T>, command: String): T =
        try {
            gson.fromJson(stdout, type) ?: throw IllegalStateException("Empty response from 'tokitoki $command'")
        } catch (error: JsonSyntaxException) {
            throw IllegalStateException("Unreadable response from 'tokitoki $command': ${stdout.trim().ifEmpty { "(empty)" }}", error)
        }

    private fun binaryVersion(executable: Path): SemanticVersion? =
        try {
            SemanticVersion.parse(runBinary(executable, listOf("version")).stdout)
        } catch (error: Exception) {
            null
        }

    private fun run(args: List<String>): CommandResult = runBinary(resolveExecutable(), args)

    /**
     * Nothing about where the CLI reports or keeps state is passed here: the
     * binary carries both as build stamps and reads neither from the
     * environment, so no setting and no inherited variable can redirect where
     * the API key and usage data are sent. No working directory either: the
     * CLI resolves everything it touches from the home directory.
     */
    private fun runBinary(executable: Path, args: List<String>): CommandResult {
        val commandLine = GeneralCommandLine(listOf(executable.toString()) + args)
            .withCharset(StandardCharsets.UTF_8)
        val output = CapturingProcessHandler(commandLine).runProcess(COMMAND_TIMEOUT_MS)
        val command = commandLine.commandLineString
        if (output.isTimeout) {
            throw TokitokiCliException("tokitoki command timed out", command, null, output.stdout, output.stderr)
        }
        if (output.exitCode != 0) {
            val detail = output.stderr.trim().ifEmpty { output.stdout.trim().ifEmpty { "exit code ${output.exitCode}" } }
            throw TokitokiCliException("tokitoki command failed: $detail", command, output.exitCode, output.stdout, output.stderr)
        }
        return CommandResult(output.stdout, output.stderr)
    }

    private fun isExecutable(path: Path): Boolean =
        Files.isRegularFile(path) && (SystemInfo.isWindows || Files.isExecutable(path))

    private fun markExecutable(path: Path) {
        if (!SystemInfo.isWindows) path.toFile().setExecutable(true, false)
    }
}
