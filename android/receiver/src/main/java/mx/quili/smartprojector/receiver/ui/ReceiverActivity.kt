package mx.quili.smartprojector.receiver.ui

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.view.Gravity
import android.view.KeyEvent
import android.view.SurfaceHolder
import android.view.View
import android.view.WindowManager
import android.widget.FrameLayout
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import kotlinx.coroutines.launch
import mx.quili.smartprojector.protocol.Protocol
import mx.quili.smartprojector.receiver.BuildConfig
import mx.quili.smartprojector.receiver.R
import mx.quili.smartprojector.receiver.core.ReceiverState
import mx.quili.smartprojector.receiver.core.ReceiverStatus
import mx.quili.smartprojector.receiver.databinding.ActivityReceiverBinding
import mx.quili.smartprojector.receiver.media.MirrorSurface
import mx.quili.smartprojector.receiver.service.ReceiverService
import mx.quili.smartprojector.updates.AvailableUpdate
import mx.quili.smartprojector.updates.UpdateChecker
import mx.quili.smartprojector.updates.UpdateConfig
import mx.quili.smartprojector.updates.UpdateDownload
import mx.quili.smartprojector.updates.UpdateMonitor
import kotlin.math.min
import mx.quili.smartprojector.updates.R as UpdatesR

/**
 * The receiver's only screen.
 *
 * It shows pairing details while idle and becomes the rendering surface while mirroring.
 * A single activity avoids Android 10+'s ban on starting activities from the background:
 * the service can never launch a mirroring screen on its own, so the screen that is
 * already open has to be the one that renders.
 */
class ReceiverActivity : AppCompatActivity() {

    private lateinit var binding: ActivityReceiverBinding

    private val updates = UpdateMonitor(
        UpdateChecker(UpdateConfig(BuildConfig.UPDATE_MANIFEST_URL, BuildConfig.VERSION_CODE)),
    )

    /** Non-null while a newer build is offered; the remote's OK key downloads it. */
    private var pendingUpdate: AvailableUpdate? = null

    private val notificationPermission = registerForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { /* The receiver works either way; the notification is only nicer to have. */ }

    private val surfaceCallback = object : SurfaceHolder.Callback {
        override fun surfaceCreated(holder: SurfaceHolder) {
            MirrorSurface.publish(holder.surface)
        }

        override fun surfaceChanged(holder: SurfaceHolder, format: Int, width: Int, height: Int) {
            MirrorSurface.publish(holder.surface)
        }

        override fun surfaceDestroyed(holder: SurfaceHolder) {
            // Must be cleared before the surface dies: a codec still holding it will
            // crash in native code the moment it tries to render.
            MirrorSurface.publish(null)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityReceiverBinding.inflate(layoutInflater)
        setContentView(binding.root)

        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        goFullscreen()

        binding.surface.holder.addCallback(surfaceCallback)

        requestNotificationPermissionIfNeeded()
        ReceiverService.start(this)
        observeState()
        observeUpdates()
    }

    /**
     * A projector is driven by a remote, so the download is bound to OK rather than a
     * button nobody can reach. Mirroring takes the key back: interrupting a cast to open a
     * browser is never what the press meant.
     */
    override fun onKeyDown(keyCode: Int, event: KeyEvent): Boolean {
        val confirm = keyCode == KeyEvent.KEYCODE_DPAD_CENTER || keyCode == KeyEvent.KEYCODE_ENTER
        val update = pendingUpdate
        if (confirm && update != null && binding.idlePanel.visibility == View.VISIBLE) {
            UpdateDownload.open(this, update)
            return true
        }
        return super.onKeyDown(keyCode, event)
    }

    override fun onDestroy() {
        binding.surface.holder.removeCallback(surfaceCallback)
        super.onDestroy()
    }

    private fun observeState() {
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                ReceiverStatus.state.collect(::render)
            }
        }
    }

    private fun observeUpdates() {
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                launch { updates.poll() }
                updates.available.collect(::renderUpdate)
            }
        }
    }

    private fun renderUpdate(update: AvailableUpdate?) {
        pendingUpdate = update
        if (update == null) {
            binding.update.visibility = View.GONE
            return
        }

        val headline = getString(UpdatesR.string.update_available, update.versionName, update.sizeMb)
        val action = if (UpdateDownload.isAvailable(this, update)) {
            getString(UpdatesR.string.update_action_remote)
        } else {
            getString(UpdatesR.string.update_manual, update.apkUrl)
        }
        binding.update.text = "$headline\n$action"
        binding.update.visibility = View.VISIBLE
    }

    private fun render(state: ReceiverState) {
        when (state) {
            is ReceiverState.Idle -> {
                binding.idlePanel.visibility = View.VISIBLE
                binding.receiverName.text = state.receiverName
                binding.pin.text = state.pin
                binding.pinLabel.visibility = View.VISIBLE
                binding.pin.visibility = View.VISIBLE
                binding.instructions.text = getString(R.string.idle_instructions)
                binding.address.text = state.ipAddress
                    ?.let { getString(R.string.address_format, it, Protocol.DEFAULT_PORT) }
                    ?: getString(R.string.address_offline)
            }

            is ReceiverState.Connecting -> {
                binding.idlePanel.visibility = View.VISIBLE
                binding.instructions.text = getString(R.string.connecting_format, state.senderName)
                // The code is irrelevant mid-handshake and showing it invites a bystander
                // to read it off the wall.
                binding.pinLabel.visibility = View.GONE
                binding.pin.visibility = View.GONE
            }

            is ReceiverState.Mirroring -> {
                binding.idlePanel.visibility = View.GONE
                fitSurface(state.width, state.height)
            }

            is ReceiverState.Failed -> {
                binding.idlePanel.visibility = View.VISIBLE
                binding.pinLabel.visibility = View.GONE
                binding.pin.visibility = View.GONE
                binding.instructions.text = getString(R.string.failed_format, state.message)
            }
        }
    }

    /**
     * Letterboxes the video instead of stretching it.
     *
     * A phone in portrait produces a tall frame; scaling that to a 16:9 panel without
     * preserving the ratio makes everything look squashed.
     */
    private fun fitSurface(videoWidth: Int, videoHeight: Int) {
        binding.root.post {
            val availableWidth = binding.root.width
            val availableHeight = binding.root.height
            if (availableWidth == 0 || availableHeight == 0) return@post

            val scale = min(
                availableWidth.toFloat() / videoWidth,
                availableHeight.toFloat() / videoHeight,
            )

            binding.surface.layoutParams = FrameLayout.LayoutParams(
                (videoWidth * scale).toInt(),
                (videoHeight * scale).toInt(),
                Gravity.CENTER,
            )
            binding.surface.holder.setFixedSize(videoWidth, videoHeight)
        }
    }

    private fun goFullscreen() {
        @Suppress("DEPRECATION")
        window.decorView.systemUiVisibility = (
            View.SYSTEM_UI_FLAG_FULLSCREEN
                or View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                or View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
                or View.SYSTEM_UI_FLAG_LAYOUT_STABLE
                or View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
                or View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
            )
    }

    private fun requestNotificationPermissionIfNeeded() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return

        val granted = ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) ==
            PackageManager.PERMISSION_GRANTED
        if (!granted) {
            notificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
    }
}
