package mx.quili.smartprojector.versioning

/** Semantic version of a release tag `vMAJOR.MINOR.PATCH`. */
data class SemVer(val major: Int, val minor: Int, val patch: Int) : Comparable<SemVer> {
    fun bump(level: Bump): SemVer = when (level) {
        Bump.MAJOR -> SemVer(major + 1, 0, 0)
        Bump.MINOR -> SemVer(major, minor + 1, 0)
        Bump.PATCH -> SemVer(major, minor, patch + 1)
    }

    override fun compareTo(other: SemVer): Int = compareValuesBy(this, other, SemVer::major, SemVer::minor, SemVer::patch)

    override fun toString() = "$major.$minor.$patch"

    val tag: String get() = "v$this"

    companion object {
        val ZERO = SemVer(0, 0, 0)
        private val TAG = Regex("""v(\d{1,4})\.(\d{1,4})\.(\d{1,4})""")

        fun parseTag(tag: String): SemVer? = TAG.matchEntire(tag.trim())?.destructured?.let { (major, minor, patch) ->
            SemVer(major.toInt(), minor.toInt(), patch.toInt())
        }
    }
}

enum class Bump { PATCH, MINOR, MAJOR }

data class Commit(val subject: String, val body: String = "")

/** A Conventional Commits header: `type(scope)!: description`. */
data class ConventionalCommit(val type: String, val scope: String?, val breaking: Boolean, val description: String) {
    companion object {
        private val HEADER = Regex("""^([a-z]+)(?:\(([^)]+)\))?(!)?: (.+)$""")
        private val BREAKING_FOOTER = Regex("""(?m)^BREAKING[ -]CHANGE: """)

        fun parse(commit: Commit): ConventionalCommit? = HEADER.matchEntire(commit.subject.trim())?.let { match ->
            val (type, scope, bang, description) = match.destructured
            ConventionalCommit(
                type = type,
                scope = scope.ifEmpty { null },
                breaking = bang.isNotEmpty() || BREAKING_FOOTER.containsMatchIn(commit.body),
                description = description.trim(),
            )
        }
    }
}

/** Version of HEAD: the last release tag plus the commits made since. */
data class VersionInfo(val baseTag: SemVer?, val commitsSinceTag: List<Commit>) {
    val isRelease: Boolean get() = baseTag != null && commitsSinceTag.isEmpty()

    /** Version the next release will get (the tag itself when HEAD is already tagged). */
    val releaseVersion: SemVer
        get() {
            val base = baseTag ?: SemVer.ZERO
            return ReleaseRules.bumpFor(commitsSinceTag)?.let(base::bump) ?: base
        }

    /** `1.1.0` on a tagged commit, `1.1.0-beta.3` for the third commit after the last tag. */
    val versionName: String
        get() = if (isRelease) releaseVersion.toString() else "$releaseVersion-beta.${commitsSinceTag.size.coerceAtLeast(1)}"
}

object ReleaseRules {
    const val LOG_FORMAT = "%s%x1f%b%x1e"
    private const val FIELD = '\u001f'
    private const val RECORD = '\u001e'

    fun bumpFor(commits: List<Commit>): Bump? {
        if (commits.isEmpty()) return null
        val parsed = commits.map(ConventionalCommit::parse)
        return when {
            parsed.any { it?.breaking == true } -> Bump.MAJOR
            parsed.any { it?.type == "feat" } -> Bump.MINOR
            else -> Bump.PATCH
        }
    }

    /** Parses `git log --format=LOG_FORMAT` output. */
    fun parseLog(raw: String): List<Commit> = raw.split(RECORD)
        .map { it.trim('\n', '\r') }
        .filter { it.isNotBlank() }
        .map { record ->
            val subject = record.substringBefore(FIELD).trim()
            val body = if (FIELD in record) record.substringAfter(FIELD).trim() else ""
            Commit(subject, body)
        }
}
