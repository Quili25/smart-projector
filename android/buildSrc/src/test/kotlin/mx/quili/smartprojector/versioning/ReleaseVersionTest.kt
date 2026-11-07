package mx.quili.smartprojector.versioning

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SemVerTest {

    @Test
    fun `parses a release tag`() {
        assertEquals(SemVer(1, 4, 2), SemVer.parseTag("v1.4.2"))
    }

    @Test
    fun `rejects tags that are not releases`() {
        assertNull(SemVer.parseTag("1.4.2"))
        assertNull(SemVer.parseTag("v1.4"))
        assertNull(SemVer.parseTag("v1.4.2-rc1"))
    }

    @Test
    fun `bumping resets the lower components`() {
        val version = SemVer(1, 4, 2)
        assertEquals(SemVer(2, 0, 0), version.bump(Bump.MAJOR))
        assertEquals(SemVer(1, 5, 0), version.bump(Bump.MINOR))
        assertEquals(SemVer(1, 4, 3), version.bump(Bump.PATCH))
    }
}

class ReleaseRulesTest {

    @Test
    fun `a breaking change outranks a feature`() {
        val commits = listOf(Commit("feat: espejo de audio"), Commit("refactor!: nuevo formato de cable"))
        assertEquals(Bump.MAJOR, ReleaseRules.bumpFor(commits))
    }

    @Test
    fun `a breaking change footer counts as breaking`() {
        val commits = listOf(Commit("refactor: nuevo handshake", "BREAKING CHANGE: el PIN ahora es de 8 digitos"))
        assertEquals(Bump.MAJOR, ReleaseRules.bumpFor(commits))
    }

    @Test
    fun `a feature outranks a fix`() {
        val commits = listOf(Commit("fix: reconectar tras suspension"), Commit("feat: descubrimiento manual"))
        assertEquals(Bump.MINOR, ReleaseRules.bumpFor(commits))
    }

    @Test
    fun `commits that are not conventional still bump the patch`() {
        assertEquals(Bump.PATCH, ReleaseRules.bumpFor(listOf(Commit("arreglo rapido"))))
    }

    @Test
    fun `no commits means no release`() {
        assertNull(ReleaseRules.bumpFor(emptyList()))
    }

    @Test
    fun `parses subject and body out of the log format`() {
        val raw = "feat: algo\u001fcuerpo largo\u001e" + "fix: otra cosa\u001f\u001e"
        assertEquals(
            listOf(Commit("feat: algo", "cuerpo largo"), Commit("fix: otra cosa", "")),
            ReleaseRules.parseLog(raw),
        )
    }

    @Test
    fun `an empty log has no commits`() {
        assertTrue(ReleaseRules.parseLog("").isEmpty())
    }
}

class VersionInfoTest {

    @Test
    fun `a tagged commit is the release itself`() {
        val info = VersionInfo(SemVer(1, 2, 0), emptyList())
        assertTrue(info.isRelease)
        assertEquals("1.2.0", info.versionName)
    }

    @Test
    fun `commits after a tag become a beta of the next version`() {
        val info = VersionInfo(SemVer(1, 2, 0), listOf(Commit("feat: algo"), Commit("fix: otra")))
        assertFalse(info.isRelease)
        assertEquals("1.3.0-beta.2", info.versionName)
    }

    @Test
    fun `a repository without tags starts below the first release`() {
        val info = VersionInfo(null, listOf(Commit("feat: primer commit")))
        assertEquals("0.1.0-beta.1", info.versionName)
    }
}

class GitHistoryTest {

    @Test
    fun `reads the last tag and the commits after it`() {
        val git: Git = { args ->
            when {
                args.first() == "describe" -> "v1.2.0"
                args.first() == "log" -> "feat: algo\u001f\u001e"
                else -> null
            }
        }

        val info = GitHistory.version(git)

        assertEquals(SemVer(1, 2, 0), info.baseTag)
        assertEquals("1.3.0-beta.1", info.versionName)
    }

    @Test
    fun `survives a repository that has no tags yet`() {
        val git: Git = { args -> if (args.first() == "log") "fix: algo\u001f\u001e" else null }

        val info = GitHistory.version(git)

        assertNull(info.baseTag)
        assertEquals("0.0.1-beta.1", info.versionName)
    }
}
