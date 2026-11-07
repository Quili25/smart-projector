package mx.quili.smartprojector.versioning

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Test

class SemVerTest {

    @Test
    fun `parses the VERSION file`() {
        assertEquals(SemVer(1, 4, 2), SemVer.parse("1.4.2\n"))
    }

    @Test
    fun `rejects anything that is not a release number`() {
        for (text in listOf("v1.4.2", "1.4", "1.4.2-beta.1", "", "uno.dos.tres")) {
            assertNull(SemVer.parse(text))
        }
    }

    @Test
    fun `names its release tag`() {
        assertEquals("v1.4.2", SemVer(1, 4, 2).tag)
    }

    @Test
    fun `the next patch leaves the rest alone`() {
        assertEquals(SemVer(1, 4, 3), SemVer(1, 4, 2).nextPatch())
    }
}

class ProjectVersionTest {

    /** Stubs the handful of git calls [ProjectVersion] makes. */
    private fun git(headTag: String? = null, existingTags: Set<String> = emptySet(), count: Int = 0): Git = { args ->
        when (args.first()) {
            "describe" -> headTag
            "rev-parse" -> args.last().takeIf { it in existingTags }
            "rev-list" -> count.toString()
            else -> null
        }
    }

    @Test
    fun `a build on the release tag is that release`() {
        val version = ProjectVersion.of("1.2.0", git(headTag = "v1.2.0", existingTags = setOf("v1.2.0")))

        assertEquals("1.2.0", version)
    }

    @Test
    fun `commits after the release are a prerelease of the next patch`() {
        val version = ProjectVersion.of("1.2.0", git(existingTags = setOf("v1.2.0"), count = 3))

        assertEquals("1.2.1-beta.3", version)
    }

    @Test
    fun `a different tag on HEAD is not the release`() {
        val version = ProjectVersion.of("1.2.0", git(headTag = "nightly", existingTags = setOf("v1.2.0"), count = 1))

        assertEquals("1.2.1-beta.1", version)
    }

    @Test
    fun `counts the whole history before the first release is tagged`() {
        val version = ProjectVersion.of("1.0.0", git(count = 7))

        assertEquals("1.0.1-beta.7", version)
    }

    @Test
    fun `never reports a zeroth prerelease`() {
        val version = ProjectVersion.of("1.0.0", git(existingTags = setOf("v1.0.0"), count = 0))

        assertEquals("1.0.1-beta.1", version)
    }

    @Test
    fun `survives git being unavailable`() {
        val version = ProjectVersion.of("1.2.0") { null }

        assertEquals("1.2.1-beta.1", version)
    }

    @Test
    fun `refuses a VERSION file it cannot read`() {
        val error = assertThrows(IllegalStateException::class.java) {
            ProjectVersion.of("no-es-una-version") { null }
        }

        assertEquals(true, error.message?.contains("VERSION"))
    }
}
