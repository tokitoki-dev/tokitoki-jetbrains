package com.tracklm.tokitoki.jetbrains

import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.project.Project
import com.intellij.openapi.application.PathManager
import com.intellij.openapi.util.text.StringUtil
import com.tracklm.tokitoki.jetbrains.settings.TokitokiSettings
import java.io.File
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import java.nio.file.StandardCopyOption
import java.util.concurrent.TimeUnit

data class CommandResult(val stdout: String, val stderr: String)

class TokitokiCliException(
    message: String,
    val command: String,
    val exitCode: Int? = null,
    val stdout: String = "",
    val stderr: String = "",
) : RuntimeException(message)

class TokitokiCli(private val project: Project?) {
    private val log = Logger.getInstance(TokitokiCli::class.java)

    fun resolveExecutable(): Path {
        val platform = bundledPlatform()
        val executableName = if (platform.startsWith("windows-")) "tokitoki.exe" else "tokitoki"
        val resourcePath = "/cli/$platform/$executableName"
        val resource = javaClass.getResourceAsStream(resourcePath)
            ?: throw TokitokiCliException("Bundled Tokitoki CLI is missing for $platform.", resourcePath)
        val target = Paths.get(PathManager.getSystemPath(), "tokitoki-jetbrains", platform, executableName)
        Files.createDirectories(target.parent)
        resource.use { Files.copy(it, target, StandardCopyOption.REPLACE_EXISTING) }
        if (!platform.startsWith("windows-")) {
            target.toFile().setExecutable(true, true)
        }
        if (!PathUtil.isExecutableFile(target)) {
            throw TokitokiCliException("Bundled Tokitoki CLI is not executable after extraction: $target", target.toString())
        }
        return target
    }

    fun sync(): CommandResult = run(providerArgs())

    fun setApiKey(apiKey: String): CommandResult = run(listOf("set", "key", apiKey))

    fun getApiKey(): CommandResult = run(listOf("get", "key"))

    fun service(action: String): CommandResult {
        val state = TokitokiSettings.getInstance().state
        val args = mutableListOf("service", action)
        args += providerArgs()
        if (action == "install" || action == "restart") {
            args += listOf("--interval", "${state.syncIntervalMinutes}m")
        }
        return run(args)
    }

    private fun run(args: List<String>): CommandResult {
        val state = TokitokiSettings.getInstance().state
        val executable = resolveExecutable()
        val command = listOf(executable.toString()) + args
        val process = ProcessBuilder(command)
            .directory(File(project?.basePath ?: System.getProperty("user.dir")))
            .redirectErrorStream(false)
            .apply {
                environment()["TOKITOKI_BASE_URL"] = state.baseUrl.ifBlank { "http://localhost:9093" }
            }
            .start()

        val finished = process.waitFor(state.commandTimeoutSeconds.toLong(), TimeUnit.SECONDS)
        val stdout = process.inputStream.bufferedReader().use { it.readText() }
        val stderr = process.errorStream.bufferedReader().use { it.readText() }
        if (!finished) {
            process.destroyForcibly()
            throw TokitokiCliException("tokitoki command timed out after ${state.commandTimeoutSeconds}s", printable(command), null, stdout, stderr)
        }
        if (process.exitValue() != 0) {
            val detail = stderr.trim().ifEmpty { stdout.trim().ifEmpty { "exit code ${process.exitValue()}" } }
            throw TokitokiCliException("tokitoki command failed: $detail", printable(command), process.exitValue(), stdout, stderr)
        }
        log.debug("tokitoki command completed: ${printable(command)}")
        return CommandResult(stdout, stderr)
    }

    private fun providerArgs(): List<String> =
        TokitokiSettings.getInstance().state.providerDirs
            .mapNotNull { PathUtil.normalizeProviderDir(it) }
            .flatMap { listOf("--provider-dir", it) }

    private fun printable(command: List<String>): String = command.joinToString(" ") { StringUtil.wrapWithDoubleQuote(it) }

    private fun bundledPlatform(): String {
        val os = System.getProperty("os.name").lowercase()
        val arch = System.getProperty("os.arch").lowercase()
        val osPart = when {
            os.contains("mac") || os.contains("darwin") -> "darwin"
            os.contains("win") -> "windows"
            os.contains("linux") -> "linux"
            else -> throw TokitokiCliException("Unsupported OS for bundled Tokitoki CLI: $os", "bundled-cli")
        }
        val archPart = when (arch) {
            "x86_64", "amd64" -> "amd64"
            "aarch64", "arm64" -> "arm64"
            else -> throw TokitokiCliException("Unsupported architecture for bundled Tokitoki CLI: $arch", "bundled-cli")
        }
        return "$osPart-$archPart"
    }
}
