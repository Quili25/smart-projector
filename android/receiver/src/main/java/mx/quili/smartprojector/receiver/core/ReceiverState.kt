package mx.quili.smartprojector.receiver.core

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** What the receiver is doing right now, as the UI needs to show it. */
sealed interface ReceiverState {

    /** Waiting for a sender. Shows the details a user needs in order to connect. */
    data class Idle(
        val receiverName: String,
        val ipAddress: String?,
        val pin: String,
    ) : ReceiverState

    /** A sender is negotiating; not yet rendering. */
    data class Connecting(val senderName: String) : ReceiverState

    /** Actively rendering a sender's screen. */
    data class Mirroring(
        val senderName: String,
        val width: Int,
        val height: Int,
    ) : ReceiverState

    /** Terminal-ish failure; the server keeps listening and recovers on the next connection. */
    data class Failed(val message: String) : ReceiverState
}

/**
 * Shared state between the foreground service (which owns the socket) and the activity
 * (which owns the surface).
 *
 * A process-wide holder rather than service binding: this is a single-process app whose
 * service and activity have the same lifetime in practice, and binding would add a
 * reconnect dance for no benefit on a device this constrained.
 */
object ReceiverStatus {

    private val _state = MutableStateFlow<ReceiverState>(
        ReceiverState.Idle(receiverName = "", ipAddress = null, pin = ""),
    )

    val state: StateFlow<ReceiverState> = _state.asStateFlow()

    fun publish(next: ReceiverState) {
        _state.value = next
    }

    val current: ReceiverState
        get() = _state.value
}
