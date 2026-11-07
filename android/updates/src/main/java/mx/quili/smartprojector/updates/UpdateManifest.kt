package mx.quili.smartprojector.updates

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.net.URI
import java.util.Locale

/**
 * Mirror of the `latest.json` written by scripts/Publish-Lan.ps1.
 *
 * The schema is shared with the other LAN-published apps in the house, so a build of any of
 * them can read any of their manifests. [versionName] is the plain `X.Y.Z` every installed
 * build understands; [displayVersion] adds the prerelease suffix.
 */
@Serializable
data class UpdateManifest(
    val schemaVersion: Int,
    val versionCode: Int,
    val versionName: String,
    val displayVersion: String? = null,
    val commit: String,
    val apk: String,
    val sha256: String,
    val sizeBytes: Long,
    val publishedAt: String,
)

data class AvailableUpdate(
    val versionCode: Int,
    /** Full version shown to the user, e.g. `1.1.0-beta.8`. */
    val versionName: String,
    val commit: String,
    val apkUrl: String,
    val sizeBytes: Long,
) {
    val sizeMb: String get() = String.format(Locale.US, "%.1f", sizeBytes / 1_048_576.0)
}

class UpdateFormatException(message: String, cause: Throwable? = null) : Exception(message, cause)

object UpdateManifestParser {
    const val SCHEMA_VERSION = 1
    const val MAX_MANIFEST_BYTES = 16 * 1024
    private const val MAX_APK_BYTES = 200L * 1024 * 1024

    private val json = Json { ignoreUnknownKeys = true }
    private val apkFileName = Regex("[A-Za-z0-9._-]{1,120}\\.apk")
    private val semVer = Regex("\\d{1,4}\\.\\d{1,4}\\.\\d{1,4}")
    private val prerelease = Regex("-beta\\.\\d{1,6}")
    private val commitHash = Regex("[0-9a-f]{7,40}")
    private val sha256Hex = Regex("[0-9a-f]{64}")

    /**
     * Parses and validates [text].
     *
     * The APK must be a bare file name resolved against the manifest's own URL, so a tampered
     * manifest can never send the device to another host.
     */
    fun parse(text: String, manifestUrl: String): AvailableUpdate {
        val manifest = try {
            json.decodeFromString<UpdateManifest>(text)
        } catch (e: IllegalArgumentException) {
            throw UpdateFormatException("latest.json no tiene el formato esperado", e)
        }
        with(manifest) {
            ensure(schemaVersion in 1..SCHEMA_VERSION) { "Versión de latest.json no soportada: $schemaVersion" }
            ensure(versionCode > 0) { "versionCode inválido: $versionCode" }
            ensure(semVer.matches(versionName)) { "versionName inválido" }
            ensure(displayVersion == null || isDisplayVersionOf(displayVersion, versionName)) { "displayVersion inválido" }
            ensure(commitHash.matches(commit)) { "commit inválido" }
            ensure(apkFileName.matches(apk)) { "Nombre de APK inválido" }
            ensure(sha256Hex.matches(sha256)) { "sha256 inválido" }
            ensure(sizeBytes in 1..MAX_APK_BYTES) { "Tamaño de APK inválido: $sizeBytes" }
        }
        return AvailableUpdate(
            versionCode = manifest.versionCode,
            versionName = manifest.displayVersion ?: manifest.versionName,
            commit = manifest.commit,
            apkUrl = URI(manifestUrl).resolve(manifest.apk).toString(),
            sizeBytes = manifest.sizeBytes,
        )
    }

    private fun isDisplayVersionOf(display: String, core: String): Boolean =
        display == core || (display.startsWith(core) && prerelease.matches(display.removePrefix(core)))

    private inline fun ensure(condition: Boolean, message: () -> String) {
        if (!condition) throw UpdateFormatException(message())
    }
}
