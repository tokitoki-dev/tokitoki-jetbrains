plugins {
    id("java")
    id("org.jetbrains.kotlin.jvm") version "1.9.25"
    id("org.jetbrains.intellij") version "1.17.4"
}

group = "com.tracklm.tokitoki"
version = "0.1.0"

repositories {
    mavenCentral()
}

java {
    toolchain {
        languageVersion.set(JavaLanguageVersion.of(17))
    }
}

kotlin {
    jvmToolchain(17)
}

intellij {
    version.set("2023.3.8")
    type.set("IC")
    plugins.set(listOf("java"))
    updateSinceUntilBuild.set(false)
}

dependencies {
    testImplementation("org.junit.jupiter:junit-jupiter:5.10.3")
}

val goagentDir = file("../tracklm-goagent")
val generatedCliResources = layout.buildDirectory.dir("generated/tokitoki-cli")

val buildTokitokiCli by tasks.registering(Exec::class) {
    workingDir = goagentDir
    commandLine("make", "cross")
}

val cleanGoagentCrossArtifacts by tasks.registering(Delete::class) {
    delete(
        goagentDir.resolve("dist/tokitoki-darwin-amd64"),
        goagentDir.resolve("dist/tokitoki-darwin-arm64"),
        goagentDir.resolve("dist/tokitoki-linux-amd64"),
        goagentDir.resolve("dist/tokitoki-linux-arm64"),
        goagentDir.resolve("dist/tokitoki-windows-amd64.exe"),
    )
}

val packageTokitokiCli by tasks.registering(Copy::class) {
    dependsOn(buildTokitokiCli)
    from(goagentDir.resolve("dist/tokitoki-darwin-amd64")) {
        into("cli/darwin-amd64")
        rename { "tokitoki" }
    }
    from(goagentDir.resolve("dist/tokitoki-darwin-arm64")) {
        into("cli/darwin-arm64")
        rename { "tokitoki" }
    }
    from(goagentDir.resolve("dist/tokitoki-linux-amd64")) {
        into("cli/linux-amd64")
        rename { "tokitoki" }
    }
    from(goagentDir.resolve("dist/tokitoki-linux-arm64")) {
        into("cli/linux-arm64")
        rename { "tokitoki" }
    }
    from(goagentDir.resolve("dist/tokitoki-windows-amd64.exe")) {
        into("cli/windows-amd64")
        rename { "tokitoki.exe" }
    }
    into(generatedCliResources)
    finalizedBy(cleanGoagentCrossArtifacts)
}

tasks {
    patchPluginXml {
        sinceBuild.set("233")
        changeNotes.set(
            """
            <ul>
              <li>Initial MVP for syncing JetBrains IDE activity with the local Tokitoki CLI.</li>
            </ul>
            """.trimIndent(),
        )
    }

    withType<org.jetbrains.kotlin.gradle.tasks.KotlinCompile>().configureEach {
        kotlinOptions {
            jvmTarget = "17"
            freeCompilerArgs += listOf("-Xjsr305=strict")
        }
    }

    test {
        useJUnitPlatform()
    }

    processResources {
        dependsOn(packageTokitokiCli)
        from(generatedCliResources)
    }
}
