package com.efroni.totalktotext

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.widget.Button
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat

/**
 * Onboarding / control screen. No full-screen speech dialog lives here (or
 * anywhere in the app) - this screen's only job is getting the two
 * permissions the floating bubble needs, then starting or stopping it.
 */
class MainActivity : AppCompatActivity() {

    private lateinit var overlayStatus: TextView
    private lateinit var overlayButton: Button
    private lateinit var micStatus: TextView
    private lateinit var micButton: Button
    private lateinit var startBubbleButton: Button

    private val micPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { refreshPermissionState() }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        overlayStatus = findViewById(R.id.overlayStatus)
        overlayButton = findViewById(R.id.overlayButton)
        micStatus = findViewById(R.id.micStatus)
        micButton = findViewById(R.id.micButton)
        startBubbleButton = findViewById(R.id.startBubbleButton)

        overlayButton.setOnClickListener { requestOverlayPermission() }
        micButton.setOnClickListener { requestMicPermission() }
        startBubbleButton.setOnClickListener { toggleBubble() }
    }

    override fun onResume() {
        super.onResume()
        refreshPermissionState()
    }

    private fun hasOverlayPermission(): Boolean = Settings.canDrawOverlays(this)

    private fun hasMicPermission(): Boolean =
        ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) ==
            PackageManager.PERMISSION_GRANTED

    private fun requestOverlayPermission() {
        val intent = Intent(
            Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
            Uri.parse("package:$packageName"),
        )
        startActivity(intent)
    }

    private fun requestMicPermission() {
        val perms = mutableListOf(Manifest.permission.RECORD_AUDIO)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            perms.add(Manifest.permission.POST_NOTIFICATIONS)
        }
        micPermissionLauncher.launch(perms.toTypedArray())
    }

    private fun refreshPermissionState() {
        val overlayGranted = hasOverlayPermission()
        overlayStatus.text = getString(
            if (overlayGranted) R.string.perm_overlay_granted else R.string.perm_overlay_action
        )
        overlayButton.isEnabled = !overlayGranted

        val micGranted = hasMicPermission()
        micStatus.text = getString(
            if (micGranted) R.string.perm_mic_granted else R.string.perm_mic_action
        )
        micButton.isEnabled = !micGranted

        val bothGranted = overlayGranted && micGranted
        startBubbleButton.isEnabled = bothGranted
        startBubbleButton.setText(
            if (OverlayBubbleService.isRunning) R.string.stop_bubble_button
            else R.string.start_bubble_button
        )
    }

    private fun toggleBubble() {
        if (!hasOverlayPermission() || !hasMicPermission()) {
            Toast.makeText(this, getString(R.string.recognizer_unavailable), Toast.LENGTH_SHORT).show()
            return
        }
        val wasRunning = OverlayBubbleService.isRunning
        if (wasRunning) {
            stopService(Intent(this, OverlayBubbleService::class.java))
        } else {
            ContextCompat.startForegroundService(this, Intent(this, OverlayBubbleService::class.java))
            moveTaskToBack(true)
        }
        startBubbleButton.setText(if (wasRunning) R.string.start_bubble_button else R.string.stop_bubble_button)
    }
}
