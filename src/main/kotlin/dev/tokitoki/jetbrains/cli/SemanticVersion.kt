package dev.tokitoki.jetbrains.cli

/** A release version as `tokitoki version` prints it. Anything else — "dev",
 * an empty line — is not a version, and a build without one never outranks
 * a build with one. */
data class SemanticVersion(val major: Int, val minor: Int, val patch: Int) : Comparable<SemanticVersion> {
    override fun compareTo(other: SemanticVersion): Int =
        compareValuesBy(this, other, { it.major }, { it.minor }, { it.patch })

    override fun toString(): String = "$major.$minor.$patch"

    companion object {
        fun parse(text: String): SemanticVersion? {
            val parts = text.trim().removePrefix("v").split('.')
            if (parts.size != 3) return null
            val numbers = parts.map { it.toIntOrNull() ?: return null }
            return SemanticVersion(numbers[0], numbers[1], numbers[2])
        }
    }
}
