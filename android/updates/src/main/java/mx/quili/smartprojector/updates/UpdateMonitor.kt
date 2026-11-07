package mx.quili.smartprojector.updates

import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * Holds the newest build the LAN server offers, if any.
 *
 * Only an actual upgrade reaches [available]; everything else (server down, off the home
 * Wi-Fi, already current) leaves the previous value alone, so a momentary outage never
 * makes a pending notice disappear from the screen.
 */
class UpdateMonitor(private val checker: UpdateChecker) {

    private val _available = MutableStateFlow<AvailableUpdate?>(null)
    private val mutex = Mutex()

    val available: StateFlow<AvailableUpdate?> = _available.asStateFlow()

    suspend fun refresh(): UpdateCheckResult = mutex.withLock {
        val result = checker.check()
        when (result) {
            is UpdateCheckResult.Available -> _available.value = result.update
            UpdateCheckResult.UpToDate -> _available.value = null
            UpdateCheckResult.Disabled, UpdateCheckResult.Unreachable, UpdateCheckResult.Invalid -> Unit
        }
        result
    }

    /**
     * Checks now and then every [intervalMs] until the caller's coroutine is cancelled.
     *
     * The receiver stays open for days at a time, so a single check at startup would miss
     * every build published afterwards.
     */
    suspend fun poll(intervalMs: Long = DEFAULT_INTERVAL_MS) {
        require(intervalMs > 0) { "El intervalo debe ser positivo: $intervalMs" }
        while (true) {
            if (refresh() == UpdateCheckResult.Disabled) return
            delay(intervalMs)
        }
    }

    companion object {
        const val DEFAULT_INTERVAL_MS: Long = 6 * 60 * 60 * 1000
    }
}
