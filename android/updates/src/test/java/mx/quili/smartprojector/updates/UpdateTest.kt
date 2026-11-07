package mx.quili.smartprojector.updates

import kotlinx.coroutines.launch
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException

private const val MANIFEST_URL = "http://192.168.0.165:8088/smart-projector/receiver/latest.json"
private const val COMMIT = "3f2a9c1b4d5e6f70"
private const val SHA = "c5dce5c29ab52157bdaa406e485f9bfaab7d8d59c523d93622a33b253a28f410"

private fun manifestJson(
    schemaVersion: Int = 1,
    versionCode: Int = 42,
    versionName: String = "1.2.0",
    displayVersion: String? = "1.2.0-beta.3",
    commit: String = COMMIT,
    apk: String = "smartcast-receiver-1.2.0-beta.3-42.apk",
    sha256: String = SHA,
    sizeBytes: Long = 6_012_345,
): String = buildString {
    append("""{"schemaVersion":$schemaVersion,"versionCode":$versionCode,"versionName":"$versionName",""")
    if (displayVersion != null) append(""""displayVersion":"$displayVersion",""")
    append(""""commit":"$commit","apk":"$apk","sha256":"$sha256","sizeBytes":$sizeBytes,""")
    append(""""publishedAt":"2026-11-07T18:00:00Z"}""")
}

class UpdateManifestParserTest {

    @Test
    fun `parses a published manifest`() {
        val update = UpdateManifestParser.parse(manifestJson(), MANIFEST_URL)

        assertEquals(42, update.versionCode)
        assertEquals("1.2.0-beta.3", update.versionName)
        assertEquals(COMMIT, update.commit)
        assertEquals(6_012_345L, update.sizeBytes)
    }

    @Test
    fun `resolves the apk next to the manifest`() {
        val update = UpdateManifestParser.parse(manifestJson(), MANIFEST_URL)

        assertEquals(
            "http://192.168.0.165:8088/smart-projector/receiver/smartcast-receiver-1.2.0-beta.3-42.apk",
            update.apkUrl,
        )
    }

    @Test
    fun `falls back to versionName when there is no display version`() {
        val update = UpdateManifestParser.parse(manifestJson(displayVersion = null), MANIFEST_URL)

        assertEquals("1.2.0", update.versionName)
    }

    @Test
    fun `rejects an apk pointing at another host`() {
        val attacks = listOf(
            "http://evil.example/payload.apk",
            "//evil.example/payload.apk",
            "../../../etc/passwd.apk",
            "/other-app/latest.apk",
        )

        for (apk in attacks) {
            assertThrows(UpdateFormatException::class.java) {
                UpdateManifestParser.parse(manifestJson(apk = apk), MANIFEST_URL)
            }
        }
    }

    @Test
    fun `rejects an apk name that is not an apk`() {
        assertThrows(UpdateFormatException::class.java) {
            UpdateManifestParser.parse(manifestJson(apk = "payload.sh"), MANIFEST_URL)
        }
    }

    @Test
    fun `rejects a schema version it does not understand`() {
        assertThrows(UpdateFormatException::class.java) {
            UpdateManifestParser.parse(manifestJson(schemaVersion = 2), MANIFEST_URL)
        }
    }

    @Test
    fun `rejects malformed fields`() {
        val malformed = listOf(
            manifestJson(versionCode = 0),
            manifestJson(versionName = "uno.dos.tres", displayVersion = null),
            manifestJson(displayVersion = "9.9.9"),
            manifestJson(commit = "no-es-un-hash"),
            manifestJson(sha256 = "abc"),
            manifestJson(sizeBytes = 0),
            manifestJson(sizeBytes = 500L * 1024 * 1024),
        )

        for (body in malformed) {
            assertThrows(UpdateFormatException::class.java) { UpdateManifestParser.parse(body, MANIFEST_URL) }
        }
    }

    @Test
    fun `rejects a body that is not json`() {
        for (body in listOf("", "<html>404</html>", "{", "null")) {
            assertThrows(UpdateFormatException::class.java) { UpdateManifestParser.parse(body, MANIFEST_URL) }
        }
    }

    @Test
    fun `ignores fields it does not know`() {
        val body = manifestJson().dropLast(1) + ""","channel":"staging","notes":"algo"}"""

        assertEquals(42, UpdateManifestParser.parse(body, MANIFEST_URL).versionCode)
    }
}

class UpdateCheckerTest {

    private fun checker(installed: Int, source: UpdateSource) =
        UpdateChecker(UpdateConfig(MANIFEST_URL, installed), source)

    @Test
    fun `offers a newer build`() = runTest {
        val result = checker(41) { manifestJson() }.check()

        assertTrue(result is UpdateCheckResult.Available)
        assertEquals(42, (result as UpdateCheckResult.Available).update.versionCode)
    }

    @Test
    fun `stays quiet on the same build`() = runTest {
        assertEquals(UpdateCheckResult.UpToDate, checker(42) { manifestJson() }.check())
    }

    @Test
    fun `stays quiet on a newer build than the server has`() = runTest {
        assertEquals(UpdateCheckResult.UpToDate, checker(99) { manifestJson() }.check())
    }

    @Test
    fun `an unreachable server is not an error`() = runTest {
        val offline = UpdateSource { throw IOException("connect timed out") }

        assertEquals(UpdateCheckResult.Unreachable, checker(1, offline).check())
    }

    @Test
    fun `a corrupt manifest is reported as invalid`() = runTest {
        assertEquals(UpdateCheckResult.Invalid, checker(1) { "<html>502</html>" }.check())
    }

    @Test
    fun `an empty url disables the check`() = runTest {
        val source = UpdateSource { error("No debería consultarse") }

        assertEquals(UpdateCheckResult.Disabled, UpdateChecker(UpdateConfig("", 1), source).check())
    }
}

class UpdateMonitorTest {

    @Test
    fun `publishes the newer build`() = runTest {
        val monitor = UpdateMonitor(UpdateChecker(UpdateConfig(MANIFEST_URL, 1)) { manifestJson() })

        monitor.refresh()

        assertEquals(42, monitor.available.value?.versionCode)
    }

    @Test
    fun `stays quiet when the published build is already installed`() = runTest {
        val monitor = UpdateMonitor(UpdateChecker(UpdateConfig(MANIFEST_URL, 42)) { manifestJson() })

        monitor.refresh()

        assertNull(monitor.available.value)
    }

    @Test
    fun `an outage keeps the pending notice on screen`() = runTest {
        var online = true
        val source = UpdateSource { if (online) manifestJson() else throw IOException("red caída") }
        val monitor = UpdateMonitor(UpdateChecker(UpdateConfig(MANIFEST_URL, 1), source))
        monitor.refresh()

        online = false
        assertEquals(UpdateCheckResult.Unreachable, monitor.refresh())

        assertEquals(42, monitor.available.value?.versionCode)
    }

    @Test
    fun `polling stops when the feature is disabled`() = runTest {
        val monitor = UpdateMonitor(UpdateChecker(UpdateConfig("", 1)) { manifestJson() })

        monitor.poll()

        assertNull(monitor.available.value)
    }

    @Test
    fun `polling checks again after the interval`() = runTest {
        var calls = 0
        val source = UpdateSource { calls++; manifestJson() }
        val monitor = UpdateMonitor(UpdateChecker(UpdateConfig(MANIFEST_URL, 1), source))

        val polling = launch { monitor.poll(intervalMs = 1_000) }
        advanceTimeBy(2_500)
        polling.cancel()

        assertEquals(3, calls)
    }

    @Test
    fun `rejects a non-positive interval`() = runTest {
        val monitor = UpdateMonitor(UpdateChecker(UpdateConfig(MANIFEST_URL, 1)) { manifestJson() })

        val thrown = runCatching { monitor.poll(intervalMs = 0) }.exceptionOrNull()

        assertTrue(thrown is IllegalArgumentException)
    }
}
