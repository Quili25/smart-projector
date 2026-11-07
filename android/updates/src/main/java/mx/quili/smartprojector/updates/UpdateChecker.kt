package mx.quili.smartprojector.updates

import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.URI

/** Build-time update settings; an empty [manifestUrl] disables the feature (debug builds). */
data class UpdateConfig(val manifestUrl: String, val installedVersionCode: Int) {
    val enabled: Boolean get() = manifestUrl.isNotEmpty()
    val host: String get() = if (enabled) URI(manifestUrl).host.orEmpty() else ""
}

sealed interface UpdateCheckResult {
    data object Disabled : UpdateCheckResult
    data object UpToDate : UpdateCheckResult
    data class Available(val update: AvailableUpdate) : UpdateCheckResult

    /** Off the home Wi-Fi or the server is down: expected, never an error for the user. */
    data object Unreachable : UpdateCheckResult
    data object Invalid : UpdateCheckResult
}

fun interface UpdateSource {
    /** Returns the manifest body or throws [IOException]. */
    suspend fun fetch(url: String): String
}

class HttpUpdateSource : UpdateSource {
    override suspend fun fetch(url: String): String = withContext(Dispatchers.IO) {
        val connection = URI(url).toURL().openConnection() as HttpURLConnection
        try {
            connection.connectTimeout = CONNECT_TIMEOUT_MS
            connection.readTimeout = READ_TIMEOUT_MS
            connection.instanceFollowRedirects = false
            connection.useCaches = false
            connection.setRequestProperty("Accept", "application/json")
            val status = connection.responseCode
            if (status != HttpURLConnection.HTTP_OK) throw IOException("HTTP $status")
            connection.inputStream.use { readCapped(it, UpdateManifestParser.MAX_MANIFEST_BYTES) }
        } finally {
            connection.disconnect()
        }
    }

    private fun readCapped(input: InputStream, maxBytes: Int): String {
        val out = ByteArrayOutputStream()
        val buffer = ByteArray(4096)
        while (true) {
            val read = input.read(buffer)
            if (read < 0) break
            out.write(buffer, 0, read)
            if (out.size() > maxBytes) throw IOException("latest.json excede $maxBytes bytes")
        }
        return out.toString(Charsets.UTF_8.name())
    }

    private companion object {
        const val CONNECT_TIMEOUT_MS = 3_000
        const val READ_TIMEOUT_MS = 5_000
    }
}

class UpdateChecker(
    private val config: UpdateConfig,
    private val source: UpdateSource = HttpUpdateSource(),
) {
    suspend fun check(): UpdateCheckResult {
        if (!config.enabled) return UpdateCheckResult.Disabled
        val body = try {
            source.fetch(config.manifestUrl)
        } catch (e: IOException) {
            Log.i(TAG, "Servidor de actualizaciones no disponible: ${e.message}")
            return UpdateCheckResult.Unreachable
        }
        val update = try {
            UpdateManifestParser.parse(body, config.manifestUrl)
        } catch (e: UpdateFormatException) {
            Log.w(TAG, "latest.json rechazado: ${e.message}")
            return UpdateCheckResult.Invalid
        }
        return if (update.versionCode > config.installedVersionCode) {
            UpdateCheckResult.Available(update)
        } else {
            UpdateCheckResult.UpToDate
        }
    }

    private companion object {
        const val TAG = "UpdateChecker"
    }
}
