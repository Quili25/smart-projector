package mx.quili.smartprojector.sender.core

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** A receiver found on the network. */
data class DiscoveredReceiver(
    val name: String,
    val host: String,
    val port: Int,
) {
    /** Stable key for remembering a pairing, independent of DHCP address churn. */
    val key: String get() = name
}

/** Why a cast attempt ended, in terms the UI can act on. */
enum class CastFailure {
    /** The receiver wants a pairing code, or the one we sent was wrong. */
    PIN_REQUIRED,

    /** Another phone is already casting. */
    BUSY,

    /** Sender and receiver speak different protocol versions. */
    VERSION_MISMATCH,

    /** Anything network- or codec-related. */
    CONNECTION_LOST,
}

sealed interface CastState {
    data object Idle : CastState

    data class Connecting(val receiver: DiscoveredReceiver) : CastState

    data class Casting(
        val receiver: DiscoveredReceiver,
        val width: Int,
        val height: Int,
    ) : CastState

    data class Failed(
        val receiver: DiscoveredReceiver?,
        val reason: CastFailure,
        val detail: String? = null,
    ) : CastState
}

/** Shared state between the casting service and the UI. */
object CastStatus {

    private val _state = MutableStateFlow<CastState>(CastState.Idle)

    val state: StateFlow<CastState> = _state.asStateFlow()

    fun publish(next: CastState) {
        _state.value = next
    }
}
