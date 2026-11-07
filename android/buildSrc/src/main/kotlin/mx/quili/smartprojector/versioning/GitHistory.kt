package mx.quili.smartprojector.versioning

/** Runs git with the given arguments; returns trimmed stdout, or null when git fails (e.g. no tags yet). */
typealias Git = (List<String>) -> String?

object GitHistory {
    private const val TAG_GLOB = "v[0-9]*.[0-9]*.[0-9]*"

    fun version(git: Git): VersionInfo {
        val tag = git(listOf("describe", "--tags", "--abbrev=0", "--match", TAG_GLOB, "HEAD"))?.let(SemVer::parseTag)
        val range = tag?.let { "${it.tag}..HEAD" } ?: "HEAD"
        val log = git(listOf("log", "--no-merges", "--format=${ReleaseRules.LOG_FORMAT}", range)).orEmpty()
        return VersionInfo(tag, ReleaseRules.parseLog(log))
    }
}
