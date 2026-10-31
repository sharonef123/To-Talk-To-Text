package com.efroni.totalktotext

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.widget.Button
import android.widget.EditText
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import java.util.Locale

/**
 * Minimal voice-to-text screen. Tap the mic, speak in Hebrew or English, and the
 * recognized text lands in the box below (copy it into any app).
 * Uses the device's built-in speech recognizer, so Hebrew/English support depends
 * on the recognizer and its installed language packs.
 */
class MainActivity : AppCompatActivity() {

    private lateinit var output: EditText
    private lateinit var languageLabel: TextView
    private lateinit var micButton: Button

    private var languageTag = "en-US"

    private val permissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (granted) startListening() else toast(getString(R.string.mic_permission_denied))
    }

    private val speechLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        val matches = result.data?.getStringArrayListExtra(RecognizerIntent.EXTRA_RESULTS)
        val text = matches?.firstOrNull()
        if (!text.isNullOrBlank()) {
            val existing = output.text.toString()
            val joined = if (existing.isBlank()) text else "$existing $text"
            output.setText(joined)
            output.setSelection(joined.length)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        output = findViewById(R.id.output)
        languageLabel = findViewById(R.id.languageLabel)
        micButton = findViewById(R.id.micButton)

        micButton.setOnClickListener { onMicClicked() }
        findViewById<Button>(R.id.languageButton).setOnClickListener { toggleLanguage() }
        updateLanguageLabel()
    }

    private fun onMicClicked() {
        if (!SpeechRecognizer.isRecognitionAvailable(this)) {
            toast(getString(R.string.recognizer_unavailable))
            return
        }
        val granted = ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) ==
            PackageManager.PERMISSION_GRANTED
        if (granted) startListening() else permissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
    }

    private fun startListening() {
        val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            putExtra(RecognizerIntent.EXTRA_LANGUAGE, languageTag)
            putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, false)
            putExtra(RecognizerIntent.EXTRA_PROMPT, getString(R.string.speak_prompt))
        }
        try {
            speechLauncher.launch(intent)
        } catch (e: Exception) {
            toast(getString(R.string.recognizer_unavailable))
        }
    }

    private fun toggleLanguage() {
        languageTag = if (languageTag == "en-US") "he-IL" else "en-US"
        updateLanguageLabel()
    }

    private fun updateLanguageLabel() {
        val name = Locale.forLanguageTag(languageTag).getDisplayLanguage(Locale.ENGLISH)
        languageLabel.text = getString(R.string.language_label, name)
    }

    private fun toast(msg: String) = Toast.makeText(this, msg, Toast.LENGTH_SHORT).show()
}
