package com.joetr.mira.updates

internal data class SemanticVersion(
    val major: Int,
    val minor: Int,
    val patch: Int,
) : Comparable<SemanticVersion> {
    override fun compareTo(other: SemanticVersion): Int =
        compareValuesBy(this, other, SemanticVersion::major, SemanticVersion::minor, SemanticVersion::patch)

    override fun toString(): String = "$major.$minor.$patch"

    companion object {
        private val versionPattern = Regex("""(?:release/|v)?([0-9]+)\.([0-9]+)\.([0-9]+)(?:[-+].*)?""")

        fun parse(value: String?): SemanticVersion? {
            val match = versionPattern.matchEntire(value?.trim().orEmpty()) ?: return null
            return SemanticVersion(
                major = match.groupValues[1].toIntOrNull() ?: return null,
                minor = match.groupValues[2].toIntOrNull() ?: return null,
                patch = match.groupValues[3].toIntOrNull() ?: return null,
            )
        }
    }
}
