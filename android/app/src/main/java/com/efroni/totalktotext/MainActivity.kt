package com.efroni.totalktotext

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.accessibilityservice.AccessibilityServiceInfo
import android.provider.Settings
import android.view.accessibility.AccessibilityManager
import android.widget.Button
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.app.AppCompatDelegate
import androidx.core.content.ContextCompat
import androidx.core.os.LocaleListCompat

/**
 * Onboarding / control screen. No full-screen speech dialog lives here (or
 * anywhere in the app) - this screen's only job is getting the permissions
 * the floating bubble needs, then starting or stopping it. Defaults to
 * Hebrew (see values/strings.xml); the top-bar button switches to English
 * via AppCompatDelegate's per-app language override, independent of the
 * device's system locale.
 */
class MainActivity : AppCompatActivity() {

    private lateinit var langToggleButton: Button
    private lateinit var overlayStatus: TextView
    private lateinit var overlayButton: Button
    private lateinit var micStatus: TextView
    private lateinit var micButton: Button
    private lateinit var accessStatus: TextView
    private lateinit var accessButton: Button
    private lateinit var startBubbleButton: Button

    private val micPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { refreshPermissionState() }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        langToggleButton = findViewById(R.id.langToggleButton)
        overlayStatus = findViewById(R.id.overlayStatus)
        overlayButton = findViewById(R.id.overlayButton)
        micStatus = findViewById(R.id.micStatus)
        micButton = findViewById(R.id.micButton)
        accessStatus = findViewById(R.id.accessStatus)
        accessButton = findViewById(R.id.accessButton)
        startBubbleButton = findViewById(R.id.startBubbleButton)

        langToggleButton.setOnClickListener { toggleLanguage() }
        overlayButton.setOnClickListener { requestOverlayPermission() }
        micButton.setOnClickListener { requestMicPermission() }
        accessButton.setOnClickListener { requestAccessibilityPermission() }
        startBubbleButton.setOnClickListener { toggleBubble() }
    }

    override fun onResume() {
        super.onResume()
        refreshPermissionState()
    }

    private fun toggleLanguage() {
        val current = AppCompatDelegate.getApplicationLocales()
        val isHebrew = current.isEmpty || current.toLanguageTags().startsWith("he")
        val next = if (isHebrew) "en" else "he"
        AppCompatDelegate.setApplicationLocales(LocaleListCompat.forLanguageTags(next))
        // setApplicationLocales recreates the activity to apply the new resources.
    }

    private fun hasOverlayPermission(): Boolean = Settings.canDrawOverlays(this)

    private fun hasMicPermission(): Boolean =
        ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) ==
            PackageManager.PERMISSION_GRANTED

    private fun hasAccessibilityPermission(): Boolean {
        val manager = getSystemService(ACCESSIBILITY_SERVICE) as AccessibilityManager
        val enabled = manager.getEnabledAccessibilityServiceList(
            AccessibilityServiceInfo.FEEDBACK_ALL_MASK
        )
        return enabled.any { it.resolveInfo.serviceInfo.packageName == packageName }
    }

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

    private fun requestAccessibilityPermission() {
        // There is no programmatic "grant" for an AccessibilityService - the
        // user has to flip it on in system Settings themselves.
        startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
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

        val accessGranted = hasAccessibilityPermission()
        accessStatus.text = getString(
            if (accessGranted) R.string.perm_access_granted else R.string.perm_access_action
        )
        accessButton.isEnabled = !accessGranted

        // Direct text injection is a bonus, not a hard requirement (the
        // clipboard always works), so only overlay + mic gate starting the bubble.
        val canStart = overlayGranted && micGranted
        startBubbleButton.isEnabled = canStart
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
