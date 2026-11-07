package mx.quili.smartprojector.receiver.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import android.util.DisplayMetrics
import android.util.Log
import android.view.WindowManager
import androidx.core.app.NotificationCompat
import mx.quili.smartprojector.protocol.HandshakeStatus
import mx.quili.smartprojector.protocol.PacketReader
import mx.quili.smartprojector.protocol.PacketWriter
import mx.quili.smartprojector.protocol.Protocol
import mx.quili.smartprojector.protocol.Welcome
import mx.quili.smartprojector.protocol.readHello
import mx.quili.smartprojector.protocol.writeWelcome
import mx.quili.smartprojector.receiver.R
import mx.quili.smartprojector.receiver.core.PairingStore
import mx.quili.smartprojector.receiver.core.ReceiverState
import mx.quili.smartprojector.receiver.core.ReceiverStatus
import mx.quili.smartprojector.receiver.net.DisplayGeometry
import mx.quili.smartprojector.receiver.net.LocalAddress
import mx.quili.smartprojector.receiver.net.MdnsAdvertiser
import mx.quili.smartprojector.receiver.net.SenderSession
import mx.quili.smartprojector.receiver.ui.ReceiverActivity
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Owns the listening socket and the mDNS advertisement for as long as the app is alive.
 *
 * Runs in the foreground because the projector has ~1 GB of RAM: a background service
 * holding a socket is one of the first things the system reclaims, and the failure mode
 * (projector silently stops being discoverable) is hard for a user to diagnose.
 */
class ReceiverService : Service() {

    private lateinit var pairing: PairingStore
    private lateinit var advertiser: MdnsAdvertiser

    private var serverSocket: ServerSocket? = null
    private var acceptThread: Thread? = null
    private var wakeLock: PowerManager.WakeLock? = null

    /** Guards the single rendering slot: one surface means one sender at a time. */
    private val sessionActive = AtomicBoolean(false)

    @Volatile
    private var running = false

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        pairing = PairingStore(this)
        advertiser = MdnsAdvertiser(this)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (!running) {
            running = true
            startForeground(NOTIFICATION_ID, buildNotification())
            acquireWakeLock()
            startServer()
        }
        // Restart if the system kills us: the projector should come back on its own.
        return START_STICKY
    }

    override fun onDestroy() {
        running = false
        advertiser.stop()
        runCatching { serverSocket?.close() }
        acceptThread?.interrupt()
        wakeLock?.let { if (it.isHeld) it.release() }
        wakeLock = null
        super.onDestroy()
    }

    private fun startServer() {
        val geometry = readDisplayGeometry()
        val name = receiverName()

        acceptThread = Thread({ serve(name, geometry) }, "smartcast-accept").apply {
            isDaemon = true
            start()
        }
    }

    private fun serve(name: String, geometry: DisplayGeometry) {
        val server = try {
            ServerSocket(Protocol.DEFAULT_PORT)
        } catch (e: Exception) {
            Log.e(TAG, "Could not bind port ${Protocol.DEFAULT_PORT}", e)
            ReceiverStatus.publish(
                ReceiverState.Failed("No se pudo abrir el puerto ${Protocol.DEFAULT_PORT}"),
            )
            return
        }

        serverSocket = server
        advertiser.start(name)
        publishIdle(name)
        Log.i(TAG, "Listening on ${Protocol.DEFAULT_PORT}")

        while (running && !server.isClosed) {
            val socket = try {
                server.accept()
            } catch (e: Exception) {
                if (running) Log.w(TAG, "Accept failed", e)
                continue
            }

            // Each sender gets its own thread so a slow or stuck peer cannot block the
            // accept loop and make the projector look offline to everyone else.
            Thread({ handle(socket, name, geometry) }, "smartcast-session").apply {
                isDaemon = true
                start()
            }
        }

        runCatching { server.close() }
    }

    private fun handle(socket: Socket, name: String, geometry: DisplayGeometry) {
        if (!sessionActive.compareAndSet(false, true)) {
            refuseAsBusy(socket, name)
            return
        }

        try {
            SenderSession(socket, pairing, name, geometry).run()
        } finally {
            sessionActive.set(false)
            publishIdle(name)
        }
    }

    /**
     * Tells a second sender why it cannot cast.
     *
     * Worth the handshake instead of just closing the socket: a bare connection reset
     * shows up on the phone as a generic network failure.
     */
    private fun refuseAsBusy(socket: Socket, name: String) {
        try {
            socket.soTimeout = Protocol.HANDSHAKE_TIMEOUT_MS
            val reader = PacketReader(socket.getInputStream())
            val writer = PacketWriter(socket.getOutputStream())
            reader.readHello()
            writer.writeWelcome(
                Welcome(HandshakeStatus.REJECTED_BUSY, name, 0, 0, 0),
            )
        } catch (e: Exception) {
            Log.w(TAG, "Failed to refuse a second sender", e)
        } finally {
            runCatching { socket.close() }
        }
    }

    private fun publishIdle(name: String) {
        ReceiverStatus.publish(
            ReceiverState.Idle(
                receiverName = name,
                ipAddress = LocalAddress.findIpv4(),
                pin = pairing.pin,
            ),
        )
    }

    private fun readDisplayGeometry(): DisplayGeometry {
        val windowManager = getSystemService(Context.WINDOW_SERVICE) as WindowManager
        val metrics = DisplayMetrics()

        @Suppress("DEPRECATION")
        windowManager.defaultDisplay.getRealMetrics(metrics)

        val refreshRate = @Suppress("DEPRECATION") windowManager.defaultDisplay.refreshRate
        val fps = refreshRate.toInt().coerceIn(MIN_FPS, MAX_FPS)

        return DisplayGeometry(metrics.widthPixels, metrics.heightPixels, fps)
    }

    private fun receiverName(): String {
        val model = Build.MODEL?.trim().orEmpty()
        return if (model.isBlank()) DEFAULT_NAME else model
    }

    private fun acquireWakeLock() {
        val power = getSystemService(Context.POWER_SERVICE) as PowerManager
        wakeLock = power.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, WAKE_LOCK_TAG).apply {
            setReferenceCounted(false)
            acquire()
        }
    }

    private fun buildNotification(): Notification {
        val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                getString(R.string.notification_channel),
                NotificationManager.IMPORTANCE_LOW,
            ).apply {
                setShowBadge(false)
            }
            manager.createNotificationChannel(channel)
        }

        val flags = PendingIntent.FLAG_UPDATE_CURRENT or
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) PendingIntent.FLAG_IMMUTABLE else 0

        val contentIntent = PendingIntent.getActivity(
            this,
            0,
            Intent(this, ReceiverActivity::class.java),
            flags,
        )

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle(getString(R.string.app_name))
            .setContentText(getString(R.string.notification_listening))
            .setSmallIcon(R.drawable.ic_launcher)
            .setOngoing(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setContentIntent(contentIntent)
            .build()
    }

    companion object {
        private const val TAG = "ReceiverService"
        private const val CHANNEL_ID = "smartcast_receiver"
        private const val NOTIFICATION_ID = 1
        private const val WAKE_LOCK_TAG = "smartcast:receiver"
        private const val DEFAULT_NAME = "Proyector"
        private const val MIN_FPS = 24

        /**
         * Mirroring needs no more, and 60 leaves the H713's decoder and 60 Hz panel no
         * headroom: any hiccup turns into delay the stream never recovers.
         */
        private const val MAX_FPS = 30

        fun start(context: Context) {
            val intent = Intent(context, ReceiverService::class.java)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(intent)
            } else {
                context.startService(intent)
            }
        }
    }
}
