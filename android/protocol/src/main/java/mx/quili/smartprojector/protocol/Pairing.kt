package mx.quili.smartprojector.protocol

import java.security.SecureRandom

/**
 * Pairing codes.
 *
 * Anyone on the same Wi-Fi can reach the receiver's port, so an unauthenticated
 * receiver lets any guest, roommate or neighbour with the password throw an image
 * onto the screen. A short code shown on the projector and typed on the phone is
 * enough here: the attacker must already be on the LAN and must read the projected
 * screen, and the receiver rate-limits attempts.
 */
object Pairing {

    const val PIN_LENGTH: Int = 6

    private const val DIGITS = "0123456789"

    /** Attempts allowed before the receiver rotates the code. */
    const val MAX_ATTEMPTS: Int = 5

    private val random = SecureRandom()

    /** Generates a uniformly random numeric code. */
    fun generatePin(): String {
        val sb = StringBuilder(PIN_LENGTH)
        repeat(PIN_LENGTH) {
            sb.append(DIGITS[random.nextInt(DIGITS.length)])
        }
        return sb.toString()
    }

    /**
     * Compares codes without an early exit.
     *
     * A naive comparison returns as soon as two characters differ, which leaks the
     * length of the correct prefix through timing and lets an attacker recover the
     * code digit by digit.
     */
    fun matches(expected: String, provided: String): Boolean {
        val a = expected.toByteArray(Charsets.UTF_8)
        val b = provided.toByteArray(Charsets.UTF_8)

        // Fold the length difference into the result instead of returning early.
        var diff = a.size xor b.size
        for (i in a.indices) {
            // Reading b cyclically keeps the loop length tied to `expected` only.
            val bByte = if (b.isEmpty()) 0 else b[i % b.size].toInt()
            diff = diff or (a[i].toInt() xor bByte)
        }
        return diff == 0
    }

    /** True when the string could be a well-formed code. */
    fun isWellFormed(pin: String): Boolean =
        pin.length == PIN_LENGTH && pin.all { it in DIGITS }
}
