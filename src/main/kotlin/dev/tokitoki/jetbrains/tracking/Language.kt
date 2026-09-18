package dev.tokitoki.jetbrains.tracking

/**
 * IDE file type names -> the language names the rest of Tokitoki uses (the
 * CLI's langdetect vocabulary, itself WakaTime's). JetBrains file types are
 * mostly named the way people say them — "Kotlin", "TypeScript" — so most
 * pass straight through; the table covers the ones spelled differently.
 *
 * A name neither known nor translatable yields null and the heartbeat carries
 * no language: the CLI then detects one from the path, exactly what happened
 * before the plugin started reporting types. Sending an unknown spelling
 * would put "PLAIN_TEXT" on the dashboard beside "Text".
 */
object Language {
    private val canonical = setOf(
        "ABAP", "Ada", "Agda", "Apache Config", "AppleScript", "Astro", "Awk", "Bash", "Batchfile", "C", "C#", "C++",
        "CMake", "CSS", "CSV", "Clojure", "ClojureScript", "CoffeeScript", "Crystal", "Dart", "Docker", "Elixir", "Elm",
        "Erlang", "F#", "Go", "GraphQL", "Groovy", "HTML", "Haml", "Haskell", "INI", "JSON", "JSX", "Java", "JavaScript",
        "Julia", "Kotlin", "Less", "Liquid", "Lua", "MDX", "Makefile", "Markdown", "Nix", "OCaml", "Objective-C", "PHP",
        "Perl", "PowerShell", "Prisma", "Prolog", "Protocol Buffer", "Pug", "Python", "R", "ReStructuredText", "Ruby",
        "Rust", "SCSS", "SQL", "Sass", "Scala", "Scheme", "Svelte", "Swift", "TOML", "Terraform", "Text", "Twig",
        "TypeScript", "Vue.js", "XML", "YAML", "Zig",
    )

    private val translations = mapOf(
        "JAVA" to "Java",
        "PLAIN_TEXT" to "Text",
        "Shell Script" to "Bash",
        "Dockerfile" to "Docker",
        "Properties" to "INI",
        "ObjectiveC" to "Objective-C",
        "Protocol Buffers" to "Protocol Buffer",
        "Batch" to "Batchfile",
        "JSON5" to "JSON",
        "TypeScript JSX" to "TypeScript",
        "JSX Harmony" to "JSX",
        "ECMAScript 6" to "JavaScript",
        "HCL" to "Terraform",
        "Vue" to "Vue.js",
        "Jupyter" to "Python",
    )

    fun name(fileTypeName: String?): String? {
        if (fileTypeName.isNullOrBlank()) return null
        if (fileTypeName in canonical) return fileTypeName
        return translations[fileTypeName]
    }
}
