package mx.quili.smartprojector.sender.ui

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.Bundle
import android.view.View
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.recyclerview.widget.LinearLayoutManager
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import kotlinx.coroutines.launch
import mx.quili.smartprojector.protocol.Pairing
import mx.quili.smartprojector.sender.BuildConfig
import mx.quili.smartprojector.sender.R
import mx.quili.smartprojector.sender.core.CastFailure
import mx.quili.smartprojector.sender.core.CastState
import mx.quili.smartprojector.sender.core.CastStatus
import mx.quili.smartprojector.sender.core.DiscoveredReceiver
import mx.quili.smartprojector.sender.core.SenderPrefs
import mx.quili.smartprojector.sender.databinding.ActivityMainBinding
import mx.quili.smartprojector.sender.databinding.DialogPinBinding
import mx.quili.smartprojector.sender.media.PhoneMute
import mx.quili.smartprojector.sender.net.MdnsDiscovery
import mx.quili.smartprojector.sender.service.CastService
import mx.quili.smartprojector.updates.AvailableUpdate
import mx.quili.smartprojector.updates.UpdateChecker
import mx.quili.smartprojector.updates.UpdateConfig
import mx.quili.smartprojector.updates.UpdateDownload
import mx.quili.smartprojector.updates.UpdateMonitor
import mx.quili.smartprojector.updates.R as UpdatesR

/** Picks a receiver, handles pairing, and starts or stops the cast. */
class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private lateinit var prefs: SenderPrefs

    private var discovery: MdnsDiscovery? = null

    private val updates = UpdateMonitor(
        UpdateChecker(UpdateConfig(BuildConfig.UPDATE_MANIFEST_URL, BuildConfig.VERSION_CODE)),
    )

    private val adapter = ReceiverAdapter(::onReceiverSelected)

    /** Held while the system capture-consent dialog is up. */
    private var pendingTarget: DiscoveredReceiver? = null
    private var pendingPin: String = ""

    private val captureConsent = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult(),
    ) { result ->
        val target = pendingTarget
        val pin = pendingPin
        pendingTarget = null
        pendingPin = ""

        val data = result.data
        if (result.resultCode == RESULT_OK && data != null && target != null) {
            CastService.start(this, target, pin, result.resultCode, data)
        } else {
            toast(getString(R.string.error_permission))
        }
    }

    private val notificationPermission = registerForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { /* Casting works regardless; the notification is just more visible. */ }

    /** Asked right before capture consent; a refusal only costs the audio. */
    private val audioPermission = registerForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { granted ->
        if (!granted) toast(getString(R.string.audio_permission_denied))
        launchCaptureConsent()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        prefs = SenderPrefs(this)
        restoreVolumeAfterAbruptEnd()

        binding.receivers.layoutManager = LinearLayoutManager(this)
        binding.receivers.adapter = adapter
        binding.stop.setOnClickListener { CastService.stop(this) }

        requestNotificationPermissionIfNeeded()
        observeState()
        observeUpdates()
    }

    override fun onStart() {
        super.onStart()
        discovery = MdnsDiscovery(this) { receivers ->
            adapter.submitList(receivers)
            if (CastStatus.state.value is CastState.Idle) {
                binding.status.text = getString(
                    if (receivers.isEmpty()) R.string.no_receivers else R.string.receivers_found,
                )
            }
        }.also { it.start() }
    }

    override fun onStop() {
        discovery?.stop()
        discovery = null
        super.onStop()
    }

    private fun observeState() {
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                CastStatus.state.collect(::render)
            }
        }
    }

    /**
     * Checks while the screen is up, so a build published after the phone was last opened
     * still gets noticed without a service running in the background.
     */
    private fun observeUpdates() {
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                launch { updates.poll() }
                updates.available.collect(::renderUpdate)
            }
        }
    }

    private fun renderUpdate(update: AvailableUpdate?) {
        if (update == null) {
            binding.updateBanner.visibility = View.GONE
            return
        }

        binding.updateBanner.visibility = View.VISIBLE
        binding.updateTitle.text = getString(UpdatesR.string.update_available, update.versionName, update.sizeMb)
        binding.updateBanner.setOnClickListener {
            if (!UpdateDownload.open(this, update)) {
                toast(getString(UpdatesR.string.update_no_browser, update.apkUrl))
            }
        }
    }

    private fun render(state: CastState) {
        when (state) {
            is CastState.Idle -> {
                binding.stop.visibility = View.GONE
                binding.receivers.visibility = View.VISIBLE
                binding.status.text = getString(
                    if (adapter.itemCount == 0) R.string.searching else R.string.receivers_found,
                )
            }

            is CastState.Connecting -> {
                binding.stop.visibility = View.VISIBLE
                binding.status.text = getString(R.string.connecting_to, state.receiver.name)
            }

            is CastState.Casting -> {
                binding.stop.visibility = View.VISIBLE
                binding.receivers.visibility = View.GONE
                binding.status.text = getString(
                    if (state.withAudio) R.string.casting_to else R.string.casting_to_without_audio,
                    state.receiver.name,
                )
            }

            is CastState.Failed -> {
                binding.stop.visibility = View.GONE
                binding.receivers.visibility = View.VISIBLE
                binding.status.text = getString(R.string.receivers_found)
                onFailure(state)
            }
        }
    }

    private fun onFailure(state: CastState.Failed) {
        val message = when (state.reason) {
            CastFailure.PIN_REQUIRED -> getString(R.string.error_pin)
            CastFailure.BUSY -> getString(R.string.error_busy)
            CastFailure.VERSION_MISMATCH -> getString(R.string.error_version)
            CastFailure.CONNECTION_LOST -> getString(R.string.error_connection)
        }
        toast(message)

        // Consume the failure so returning to this screen does not replay the toast.
        CastStatus.publish(CastState.Idle)

        if (state.reason == CastFailure.PIN_REQUIRED && state.receiver != null) {
            promptForPin(state.receiver)
        }
    }

    private fun onReceiverSelected(target: DiscoveredReceiver) {
        if (prefs.isPaired(target.key)) {
            requestCapture(target, pin = "")
        } else {
            promptForPin(target)
        }
    }

    private fun promptForPin(target: DiscoveredReceiver) {
        val dialogBinding = DialogPinBinding.inflate(layoutInflater)

        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.pin_dialog_title)
            .setMessage(R.string.pin_dialog_message)
            .setView(dialogBinding.root)
            .setNegativeButton(R.string.cancel, null)
            .setPositiveButton(R.string.connect) { _, _ ->
                val pin = dialogBinding.pin.text.toString().trim()
                if (Pairing.isWellFormed(pin)) {
                    requestCapture(target, pin)
                } else {
                    toast(getString(R.string.error_pin))
                }
            }
            .show()
    }

    private fun requestCapture(target: DiscoveredReceiver, pin: String) {
        pendingTarget = target
        pendingPin = pin

        // Playback capture only exists on Android 10+; older phones cast video-only.
        val needsAudioPermission = Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) !=
            PackageManager.PERMISSION_GRANTED
        if (needsAudioPermission) {
            audioPermission.launch(Manifest.permission.RECORD_AUDIO)
        } else {
            launchCaptureConsent()
        }
    }

    private fun launchCaptureConsent() {
        if (pendingTarget == null) return
        val manager = getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
        captureConsent.launch(manager.createScreenCaptureIntent())
    }

    /** A cast killed with the process never ran its teardown, so it never unmuted the phone. */
    private fun restoreVolumeAfterAbruptEnd() {
        val state = CastStatus.state.value
        if (state is CastState.Idle || state is CastState.Failed) {
            PhoneMute(this, prefs).release()
        }
    }

    private fun requestNotificationPermissionIfNeeded() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return

        val granted = ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) ==
            PackageManager.PERMISSION_GRANTED
        if (!granted) {
            notificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
    }

    private fun toast(message: String) {
        Toast.makeText(this, message, Toast.LENGTH_LONG).show()
    }
}
