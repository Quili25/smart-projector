package mx.quili.smartprojector.versioning

/** Runs git with the given arguments; returns trimmed stdout, or null when git fails. */
typealias Git = (List<String>) -> String?

/** A release number `MAJOR.MINOR.PATCH`, as kept in the repository's VERSION file. */
data class SemVer(val major: Int, val minor: Int, val patch: Int) {

    val tag: String get() = "v$this"

    fun nextPatch(): SemVer = SemVer(major, minor, patch + 1)

    override fun toString() = "$major.$minor.$patch"

    companion object {
        private val PATTERN = Regex("""(\d{1,4})\.(\d{1,4})\.(\d{1,4})""")

        fun parse(text: String): SemVer? = PATTERN.matchEntire(text.trim())?.destructured
            ?.let { (major, minor, patch) -> SemVer(major.toInt(), minor.toInt(), patch.toInt()) }
    }
}

/**
 * The version the APKs carry.
 *
 * VERSION is canonical and the release workflow owns it: on `main` it bumps the file and tags
 * `vX.Y.Z` (docs/VERSIONING.md). Gradle only reports what that tag says, so the build never
 * competes with the pipeline over what the next number should be.
 *
 * A build sitting on the release tag *is* that release. Anything after it is a prerelease of
 * the next patch, which keeps the name sorting after the release it builds on.
 */
object ProjectVersion {

    fun of(releasedVersion: String, git: Git): String {
        val released = SemVer.parse(releasedVersion)
            ?: error("VERSION no contiene un número de versión válido: $releasedVersion")

        val headTag = git(listOf("describe", "--tags", "--exact-match", "HEAD"))?.trim()
        if (headTag == released.tag) return released.toString()

        return "${released.nextPatch()}-beta.${commitsSinceRelease(released, git)}"
    }

    private fun commitsSinceRelease(released: SemVer, git: Git): Int {
        // Before the first release there is no tag to count from, so the whole history is
        // "since the release" and the count still grows by one per commit.
        val range = if (git(listOf("rev-parse", "--verify", "--quiet", released.tag)) != null) {
            "${released.tag}..HEAD"
        } else {
            "HEAD"
        }
        val count = git(listOf("rev-list", "--count", range))?.trim()?.toIntOrNull() ?: 0
        return count.coerceAtLeast(1)
    }
}
