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
import mx.quili.smartprojector.sender.R
import mx.quili.smartprojector.sender.core.CastFailure
import mx.quili.smartprojector.sender.core.CastState
import mx.quili.smartprojector.sender.core.CastStatus
import mx.quili.smartprojector.sender.core.DiscoveredReceiver
import mx.quili.smartprojector.sender.core.SenderPrefs
import mx.quili.smartprojector.sender.databinding.ActivityMainBinding
import mx.quili.smartprojector.sender.databinding.DialogPinBinding
import mx.quili.smartprojector.sender.net.MdnsDiscovery
import mx.quili.smartprojector.sender.service.CastService

/** Picks a receiver, handles pairing, and starts or stops the cast. */
class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private lateinit var prefs: SenderPrefs

    private var discovery: MdnsDiscovery? = null

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

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        prefs = SenderPrefs(this)

        binding.receivers.layoutManager = LinearLayoutManager(this)
        binding.receivers.adapter = adapter
        binding.stop.setOnClickListener { CastService.stop(this) }

        requestNotificationPermissionIfNeeded()
        observeState()
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
                binding.status.text = getString(R.string.casting_to, state.receiver.name)
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

        val manager = getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
        captureConsent.launch(manager.createScreenCaptureIntent())
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
