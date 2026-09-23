package mx.quili.smartprojector.sender.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.util.DisplayMetrics
import android.util.Log
import androidx.core.app.NotificationCompat
import mx.quili.smartprojector.protocol.HandshakeStatus
import mx.quili.smartprojector.protocol.Hello
import mx.quili.smartprojector.protocol.PacketFlags
import mx.quili.smartprojector.protocol.PacketReader
import mx.quili.smartprojector.protocol.PacketType
import mx.quili.smartprojector.protocol.PacketWriter
import mx.quili.smartprojector.protocol.Protocol
import mx.quili.smartprojector.protocol.VideoConfig
import mx.quili.smartprojector.protocol.readWelcome
import mx.quili.smartprojector.protocol.writeHello
import mx.quili.smartprojector.sender.R
import mx.quili.smartprojector.sender.core.CastFailure
import mx.quili.smartprojector.sender.core.CastState
import mx.quili.smartprojector.sender.core.CastStatus
import mx.quili.smartprojector.sender.core.DiscoveredReceiver
import mx.quili.smartprojector.sender.core.SenderPrefs
import mx.quili.smartprojector.sender.media.ScreenEncoder
import mx.quili.smartprojector.sender.ui.MainActivity
import java.io.IOException
import java.net.InetSocketAddress
import java.net.Socket
import java.nio.ByteBuffer

/**
 * Captures the screen and streams it to a receiver for as long as it runs.
 *
 * A foreground service is not optional here: since Android 10 `MediaProjection` refuses
 * to hand out a capture token unless a `mediaProjection`-typed foreground service is
 * already running, and the persistent notification is what tells the user their screen
 * is being shared.
 */
class CastService : Service() {

    private lateinit var prefs: SenderPrefs

    private var projection: MediaProjection? = null
    private var virtualDisplay: VirtualDisplay? = null
    private var encoder: ScreenEncoder? = null
    private var socket: Socket? = null
    private var writer: PacketWriter? = null

    private var worker: Thread? = null
    private var reader: Thread? = null
    private var heartbeat: Thread? = null

    @Volatile
    private var running = false

    /** Set once the receiver has acknowledged our format, so frames are not sent early. */
    @Volatile
    private var configSent = false

    private val projectionCallback = object : MediaProjection.Callback() {
        override fun onStop() {
            Log.i(TAG, "Screen capture revoked")
            stopSelf()
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        prefs = SenderPrefs(this)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            stopSelf()
            return START_NOT_STICKY
        }
        if (intent == null || running) return START_NOT_STICKY

        val target = DiscoveredReceiver(
            name = intent.getStringExtra(EXTRA_NAME).orEmpty(),
            host = intent.getStringExtra(EXTRA_HOST).orEmpty(),
            port = intent.getIntExtra(EXTRA_PORT, Protocol.DEFAULT_PORT),
        )
        val pin = intent.getStringExtra(EXTRA_PIN).orEmpty()
        val resultCode = intent.getIntExtra(EXTRA_RESULT_CODE, 0)

        @Suppress("DEPRECATION")
        val resultData: Intent? = intent.getParcelableExtra(EXTRA_RESULT_DATA)

        if (target.host.isBlank() || resultData == null) {
            Log.e(TAG, "Missing target or capture token")
            stopSelf()
            return START_NOT_STICKY
        }

        running = true
        // Must precede getMediaProjection on Android 10+.
        startForeground(NOTIFICATION_ID, buildNotification(target.name))
        CastStatus.publish(CastState.Connecting(target))

        worker = Thread({ cast(target, pin, resultCode, resultData) }, "smartcast-sender").apply {
            isDaemon = true
            start()
        }
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        running = false
        teardown()
        if (CastStatus.state.value !is CastState.Failed) {
            CastStatus.publish(CastState.Idle)
        }
        super.onDestroy()
    }

    private fun cast(target: DiscoveredReceiver, pin: String, resultCode: Int, resultData: Intent) {
        try {
            val connection = Socket()
            connection.connect(InetSocketAddress(target.host, target.port), CONNECT_TIMEOUT_MS)
            connection.tcpNoDelay = true
            connection.soTimeout = Protocol.CONNECTION_TIMEOUT_MS
            socket = connection

            val packetReader = PacketReader(connection.getInputStream())
            val packetWriter = PacketWriter(connection.getOutputStream())
            writer = packetWriter

            packetWriter.writeHello(
                Hello(
                    protocolVersion = Protocol.VERSION,
                    deviceId = prefs.deviceId,
                    deviceName = deviceName(),
                    pin = pin,
                ),
            )

            val welcome = packetReader.readWelcome()
            if (welcome.status != HandshakeStatus.ACCEPTED) {
                handleRejection(target, welcome.status)
                return
            }
            prefs.remember(target.key)

            startCapture(target, welcome.width, welcome.height, welcome.maxFps, resultCode, resultData, packetWriter)
            startReader(packetReader)
            startHeartbeat(packetWriter)

            // Blocks on this thread until the encoder is stopped or the socket dies.
            encoder?.drain(FrameSink(packetWriter))
        } catch (e: IOException) {
            Log.w(TAG, "Cast ended", e)
            fail(target, CastFailure.CONNECTION_LOST, e.message)
        } catch (e: Exception) {
            Log.e(TAG, "Cast failed", e)
            fail(target, CastFailure.CONNECTION_LOST, e.message)
        } finally {
            // Stopping from a worker thread is safe and is what returns the UI to idle.
            Handler(Looper.getMainLooper()).post { stopSelf() }
        }
    }

    private fun handleRejection(target: DiscoveredReceiver, status: HandshakeStatus) {
        val reason = when (status) {
            HandshakeStatus.REJECTED_PIN -> {
                // Our stored pairing is stale (the receiver was reset); drop it so the
                // user is asked for a fresh code instead of failing forever.
                prefs.forget(target.key)
                CastFailure.PIN_REQUIRED
            }

            HandshakeStatus.REJECTED_BUSY -> CastFailure.BUSY
            HandshakeStatus.REJECTED_VERSION -> CastFailure.VERSION_MISMATCH
            HandshakeStatus.ACCEPTED -> return
        }
        fail(target, reason, null)
    }

    private fun startCapture(
        target: DiscoveredReceiver,
        requestedWidth: Int,
        requestedHeight: Int,
        maxFps: Int,
        resultCode: Int,
        resultData: Intent,
        packetWriter: PacketWriter,
    ) {
        // H.264 requires even dimensions; odd values fail to configure on some encoders.
        val width = (requestedWidth.takeIf { it > 0 } ?: FALLBACK_WIDTH) and 1.inv()
        val height = (requestedHeight.takeIf { it > 0 } ?: FALLBACK_HEIGHT) and 1.inv()
        val fps = maxFps.coerceIn(MIN_FPS, MAX_FPS)

        val manager = getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
        val media = manager.getMediaProjection(resultCode, resultData)
            ?: throw IllegalStateException("Screen capture permission was not granted")
        projection = media
        media.registerCallback(projectionCallback, Handler(Looper.getMainLooper()))

        val screenEncoder = ScreenEncoder(
            width = width,
            height = height,
            frameRate = fps,
            bitRate = ScreenEncoder.recommendedBitRate(width, height, fps),
        )
        encoder = screenEncoder
        screenEncoder.start()

        virtualDisplay = media.createVirtualDisplay(
            VIRTUAL_DISPLAY_NAME,
            width,
            height,
            screenDensity(),
            DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
            screenEncoder.inputSurface,
            null,
            null,
        )

        CastStatus.publish(CastState.Casting(target, width, height))
        Log.i(TAG, "Casting ${width}x$height @${fps}fps to ${target.host}")
    }

    /**
     * Consumes what the receiver sends back.
     *
     * Even though the receiver only answers PONG, the socket must be drained: an unread
     * stream eventually stalls the peer's writes, and reading is also how we notice
     * promptly that the projector went away.
     */
    private fun startReader(packetReader: PacketReader) {
        reader = Thread({
            try {
                while (running) {
                    val header = packetReader.readHeader()
                    packetReader.skipPayload(header.payloadLength)
                    if (header.type == PacketType.BYE) {
                        Log.i(TAG, "Receiver closed the session")
                        break
                    }
                }
            } catch (e: Exception) {
                if (running) Log.i(TAG, "Reader ended: ${e.message}")
            } finally {
                encoder?.stop()
            }
        }, "smartcast-reader").apply {
            isDaemon = true
            start()
        }
    }

    private fun startHeartbeat(packetWriter: PacketWriter) {
        heartbeat = Thread({
            try {
                while (running) {
                    Thread.sleep(Protocol.HEARTBEAT_INTERVAL_MS)
                    if (!running) break
                    packetWriter.write(PacketType.PING)
                }
            } catch (e: InterruptedException) {
                Thread.currentThread().interrupt()
            } catch (e: Exception) {
                Log.i(TAG, "Heartbeat ended: ${e.message}")
                encoder?.stop()
            }
        }, "smartcast-heartbeat").apply {
            isDaemon = true
            start()
        }
    }

    /** Bridges encoder output onto the wire. */
    private inner class FrameSink(private val packetWriter: PacketWriter) : ScreenEncoder.Sink {

        override fun onCodecConfig(codecSpecificData: ByteArray) {
            val active = encoder ?: return
            val config = VideoConfig(active.width, active.height, codecSpecificData)
            packetWriter.write(PacketType.VIDEO_CONFIG, payload = config.encode())
            configSent = true
            // The receiver builds its decoder on this packet, so the first frame after
            // it must be an IDR or there is nothing to start from.
            active.requestKeyFrame()
        }

        override fun onFrame(payload: ByteBuffer, presentationTimeUs: Long, isKeyFrame: Boolean) {
            if (!configSent) return
            val flags = if (isKeyFrame) PacketFlags.KEYFRAME else PacketFlags.NONE
            packetWriter.write(PacketType.VIDEO_FRAME, flags, presentationTimeUs, payload)
        }
    }

    private fun fail(target: DiscoveredReceiver?, reason: CastFailure, detail: String?) {
        CastStatus.publish(CastState.Failed(target, reason, detail))
    }

    private fun teardown() {
        encoder?.stop()
        heartbeat?.interrupt()

        runCatching { writer?.write(PacketType.BYE) }
        runCatching { virtualDisplay?.release() }
        runCatching { projection?.unregisterCallback(projectionCallback) }
        runCatching { projection?.stop() }
        runCatching { encoder?.close() }
        runCatching { socket?.close() }

        virtualDisplay = null
        projection = null
        encoder = null
        writer = null
        socket = null
        configSent = false
    }

    private fun screenDensity(): Int {
        val metrics = DisplayMetrics()
        @Suppress("DEPRECATION")
        (getSystemService(Context.WINDOW_SERVICE) as android.view.WindowManager)
            .defaultDisplay.getRealMetrics(metrics)
        return metrics.densityDpi
    }

    private fun deviceName(): String {
        val model = Build.MODEL?.trim().orEmpty()
        return model.ifBlank { "Celular" }
    }

    private fun buildNotification(receiverName: String): Notification {
        val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            manager.createNotificationChannel(
                NotificationChannel(
                    CHANNEL_ID,
                    getString(R.string.notification_channel),
                    NotificationManager.IMPORTANCE_LOW,
                ).apply { setShowBadge(false) },
            )
        }

        val pendingFlags = PendingIntent.FLAG_UPDATE_CURRENT or
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) PendingIntent.FLAG_IMMUTABLE else 0

        val open = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java),
            pendingFlags,
        )

        val stop = PendingIntent.getService(
            this,
            1,
            Intent(this, CastService::class.java).setAction(ACTION_STOP),
            pendingFlags,
        )

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle(getString(R.string.notification_casting))
            .setContentText(receiverName)
            .setSmallIcon(R.drawable.ic_launcher)
            .setOngoing(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setContentIntent(open)
            .addAction(0, getString(R.string.stop), stop)
            .build()
    }

    companion object {
        private const val TAG = "CastService"
        private const val CHANNEL_ID = "smartcast_sender"
        private const val NOTIFICATION_ID = 1
        private const val VIRTUAL_DISPLAY_NAME = "SmartCast"
        private const val CONNECT_TIMEOUT_MS = 5_000
        private const val FALLBACK_WIDTH = 1280
        private const val FALLBACK_HEIGHT = 720
        private const val MIN_FPS = 15
        private const val MAX_FPS = 60

        const val ACTION_STOP = "mx.quili.smartprojector.sender.STOP"

        private const val EXTRA_NAME = "name"
        private const val EXTRA_HOST = "host"
        private const val EXTRA_PORT = "port"
        private const val EXTRA_PIN = "pin"
        private const val EXTRA_RESULT_CODE = "result_code"
        private const val EXTRA_RESULT_DATA = "result_data"

        fun start(
            context: Context,
            target: DiscoveredReceiver,
            pin: String,
            resultCode: Int,
            resultData: Intent,
        ) {
            val intent = Intent(context, CastService::class.java).apply {
                putExtra(EXTRA_NAME, target.name)
                putExtra(EXTRA_HOST, target.host)
                putExtra(EXTRA_PORT, target.port)
                putExtra(EXTRA_PIN, pin)
                putExtra(EXTRA_RESULT_CODE, resultCode)
                putExtra(EXTRA_RESULT_DATA, resultData)
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(intent)
            } else {
                context.startService(intent)
            }
        }

        fun stop(context: Context) {
            context.stopService(Intent(context, CastService::class.java))
        }
    }
}
