package com.efroni.totalktotext

import android.animation.ValueAnimator
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.graphics.PixelFormat
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.text.Editable
import android.text.TextWatcher
import android.view.Gravity
import android.view.LayoutInflater
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.view.inputmethod.InputMethodManager
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.core.app.NotificationCompat
import kotlin.math.abs

/**
 * Foreground service that owns one small floating window (the "bubble"):
 * compact circular state, an expanded horizontal pill, and a language-picker
 * panel - all three are children of the same root view, toggled by
 * visibility, so there is only ever one WindowManager window to manage.
 *
 * Speech recognition runs entirely in-process via SpeechRecognizer with a
 * headless RecognitionListener; the OS's full-screen "Speak now" dialog
 * (RecognizerIntent.ACTION_RECOGNIZE_SPEECH launched as an Activity) is never
 * invoked anywhere in this app.
 */
class OverlayBubbleService : Service() {

    companion object {
        var isRunning: Boolean = false
            private set

        private const val NOTIF_CHANNEL_ID = "ttt_bubble_channel"
        private const val NOTIF_ID = 1
        private const val EDGE_MARGIN_DP = 2
        private const val AUTO_LOCK_WORD_COUNT = 2
    }

    private lateinit var windowManager: WindowManager
    private lateinit var params: WindowManager.LayoutParams
    private lateinit var root: FrameLayout

    private lateinit var compactBubble: FrameLayout
    private lateinit var compactRecDot: View
    private lateinit var expandedPill: LinearLayout
    private lateinit var languagePanel: LinearLayout
    private lateinit var micButton: FrameLayout
    private lateinit var micIcon: android.widget.ImageView
    private lateinit var langChip: TextView
    private lateinit var transcriptPreview: TextView
    private lateinit var copyButton: FrameLayout
    private lateinit var collapseButton: FrameLayout
    private lateinit var languageSearch: EditText
    private lateinit var languageList: LinearLayout

    private var recognizer: SpeechRecognizer? = null
    private var listening = false
    private var selectedLanguage: SpeechLanguage = Languages.AUTO_DETECT
    private var pendingLanguage: SpeechLanguage? = null
    private var utteranceWordCount = 0
    private var utteranceLocked = false
    private var currentTranscript = ""

    private val mainHandler = Handler(Looper.getMainLooper())
    private var restartRunnable: Runnable? = null

    override fun onCreate() {
        super.onCreate()
        isRunning = true
        startForeground(NOTIF_ID, buildNotification())
        windowManager = getSystemService(WINDOW_SERVICE) as WindowManager
        inflateOverlay()
        wireInteractions()
        populateLanguageList(Languages.PICKER_ORDER)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int = START_STICKY

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        super.onDestroy()
        isRunning = false
        restartRunnable?.let { mainHandler.removeCallbacks(it) }
        recognizer?.destroy()
        recognizer = null
        try {
            windowManager.removeView(root)
        } catch (_: Exception) {
            /* view was never attached, or already gone */
        }
    }

    /* ------------------------------------------------------------------ */
    /* overlay window setup                                                */
    /* ------------------------------------------------------------------ */
    private fun inflateOverlay() {
        val inflater = LayoutInflater.from(this)
        root = inflater.inflate(R.layout.overlay_bubble, null) as FrameLayout

        compactBubble = root.findViewById(R.id.compactBubble)
        compactRecDot = root.findViewById(R.id.compactRecDot)
        expandedPill = root.findViewById(R.id.expandedPill)
        languagePanel = root.findViewById(R.id.languagePanel)
        micButton = root.findViewById(R.id.micButton)
        micIcon = root.findViewById(R.id.micIcon)
        langChip = root.findViewById(R.id.langChip)
        transcriptPreview = root.findViewById(R.id.transcriptPreview)
        copyButton = root.findViewById(R.id.copyButton)
        collapseButton = root.findViewById(R.id.collapseButton)
        languageSearch = root.findViewById(R.id.languageSearch)
        languageList = root.findViewById(R.id.languageList)

        val overlayType =
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
            else WindowManager.LayoutParams.TYPE_PHONE

        params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            overlayType,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.TRANSLUCENT,
        )
        params.gravity = Gravity.TOP or Gravity.START
        params.x = dp(24)
        params.y = dp(160)

        windowManager.addView(root, params)
    }

    /* ------------------------------------------------------------------ */
    /* drag + snap (compact bubble only)                                   */
    /* ------------------------------------------------------------------ */
    private fun wireInteractions() {
        var downRawX = 0f
        var downRawY = 0f
        var downParamX = 0
        var downParamY = 0
        var moved = false

        compactBubble.setOnTouchListener { _, event ->
            when (event.action) {
                MotionEvent.ACTION_DOWN -> {
                    downRawX = event.rawX
                    downRawY = event.rawY
                    downParamX = params.x
                    downParamY = params.y
                    moved = false
                    true
                }
                MotionEvent.ACTION_MOVE -> {
                    val dx = event.rawX - downRawX
                    val dy = event.rawY - downRawY
                    if (abs(dx) > dp(3) || abs(dy) > dp(3)) moved = true
                    if (moved) {
                        params.x = downParamX + dx.toInt()
                        params.y = downParamY + dy.toInt()
                        safeUpdateLayout()
                    }
                    true
                }
                MotionEvent.ACTION_UP -> {
                    if (moved) snapToNearestEdge() else expand()
                    true
                }
                else -> false
            }
        }

        micButton.setOnClickListener { toggleListening() }
        copyButton.setOnClickListener { copyTranscript() }
        collapseButton.setOnClickListener { collapse() }
        langChip.setOnClickListener { openLanguagePanel() }

        languageSearch.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {
                val query = s?.toString()?.trim()?.lowercase() ?: ""
                val filtered = if (query.isEmpty()) Languages.PICKER_ORDER
                else Languages.PICKER_ORDER.filter { it.label.lowercase().contains(query) }
                populateLanguageList(filtered)
            }
            override fun afterTextChanged(s: Editable?) {}
        })
    }

    private fun safeUpdateLayout() {
        try {
            windowManager.updateViewLayout(root, params)
        } catch (_: Exception) {
            /* window already torn down */
        }
    }

    private fun screenWidthPx(): Int = resources.displayMetrics.widthPixels

    private fun snapToNearestEdge() {
        val bubbleWidth = compactBubble.width.takeIf { it > 0 } ?: dp(52)
        val screenWidth = screenWidthPx()
        val leftTarget = dp(EDGE_MARGIN_DP)
        val rightTarget = screenWidth - bubbleWidth - dp(EDGE_MARGIN_DP)
        val goRight = (params.x + bubbleWidth / 2) > screenWidth / 2
        val target = if (goRight) rightTarget else leftTarget

        val animator = ValueAnimator.ofInt(params.x, target)
        animator.duration = 180
        animator.addUpdateListener {
            params.x = it.animatedValue as Int
            safeUpdateLayout()
        }
        animator.start()
    }

    /* ------------------------------------------------------------------ */
    /* compact / expanded / language-panel states                          */
    /* ------------------------------------------------------------------ */
    private fun expand() {
        compactBubble.visibility = View.GONE
        languagePanel.visibility = View.GONE
        expandedPill.visibility = View.VISIBLE
        clampIntoScreen()
    }

    private fun collapse() {
        closeLanguagePanel()
        expandedPill.visibility = View.GONE
        languagePanel.visibility = View.GONE
        compactBubble.visibility = View.VISIBLE
        clampIntoScreen()
    }

    private fun openLanguagePanel() {
        expandedPill.visibility = View.GONE
        languagePanel.visibility = View.VISIBLE
        clampIntoScreen()
        // A text field inside an overlay needs real input focus for the IME
        // to appear, so briefly drop FLAG_NOT_FOCUSABLE while the panel is up.
        params.flags = params.flags and WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE.inv()
        safeUpdateLayout()
        languageSearch.requestFocus()
        val imm = getSystemService(INPUT_METHOD_SERVICE) as InputMethodManager
        imm.showSoftInput(languageSearch, InputMethodManager.SHOW_IMPLICIT)
    }

    private fun closeLanguagePanel() {
        if (languagePanel.visibility != View.VISIBLE) return
        val imm = getSystemService(INPUT_METHOD_SERVICE) as InputMethodManager
        imm.hideSoftInputFromWindow(languageSearch.windowToken, 0)
        params.flags = params.flags or WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
        languageSearch.setText("")
        languagePanel.visibility = View.GONE
        expandedPill.visibility = View.VISIBLE
        safeUpdateLayout()
    }

    private fun clampIntoScreen() {
        root.post {
            val maxX = (screenWidthPx() - root.width).coerceAtLeast(0)
            if (params.x > maxX) params.x = maxX
            if (params.x < 0) params.x = 0
            safeUpdateLayout()
        }
    }

    /* ------------------------------------------------------------------ */
    /* language picker                                                     */
    /* ------------------------------------------------------------------ */
    private fun populateLanguageList(items: List<SpeechLanguage>) {
        languageList.removeAllViews()
        for (item in items) {
            val row = TextView(this)
            row.text = if (item == Languages.AUTO_DETECT) item.label else "${item.label} (${item.tag})"
            row.setTextColor(getColorCompat(R.color.ttt_text_primary))
            row.textSize = 13f
            row.setPadding(dp(10), dp(10), dp(10), dp(10))
            row.setOnClickListener { selectLanguage(item) }
            languageList.addView(row)
        }
    }

    private fun getColorCompat(resId: Int): Int =
        androidx.core.content.ContextCompat.getColor(this, resId)

    private fun selectLanguage(lang: SpeechLanguage) {
        if (utteranceLocked && listening) {
            // Mid-utterance: queue it so the swap lands between sentences,
            // never jarringly mid-word.
            pendingLanguage = lang
            langChip.text = chipLabel(lang) + " …"
        } else {
            applyLanguage(lang)
        }
        closeLanguagePanel()
    }

    private fun applyLanguage(lang: SpeechLanguage) {
        selectedLanguage = lang
        pendingLanguage = null
        langChip.text = chipLabel(lang)
        if (listening) restartListening()
    }

    private fun chipLabel(lang: SpeechLanguage): String =
        if (lang == Languages.AUTO_DETECT) getString(R.string.lang_auto_short)
        else lang.tag.substringBefore('-').uppercase()

    /* ------------------------------------------------------------------ */
    /* speech recognition                                                  */
    /* ------------------------------------------------------------------ */
    private fun ensureRecognizer(): SpeechRecognizer {
        var r = recognizer
        if (r == null) {
            r = SpeechRecognizer.createSpeechRecognizer(this)
            r.setRecognitionListener(buildListener())
            recognizer = r
        }
        return r
    }

    private fun buildRecognizerIntent(): Intent {
        val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH)
        intent.putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
        intent.putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
        intent.putExtra(RecognizerIntent.EXTRA_CALLING_PACKAGE, packageName)
        // Auto-Detect: leave EXTRA_LANGUAGE unset so the device's default
        // recognizer locale decides, same as any other app that doesn't force
        // a language. The public SpeechRecognizer API has no supported way to
        // detect-and-switch locale live within one utterance, so "Auto" means
        // "use the system default", with the lock-on-first-words logic below
        // only guarding against OUR OWN app swapping languages mid-sentence.
        if (selectedLanguage.tag.isNotEmpty()) {
            intent.putExtra(RecognizerIntent.EXTRA_LANGUAGE, selectedLanguage.tag)
        }
        return intent
    }

    private fun toggleListening() {
        if (listening) stopListening() else startListening()
    }

    private fun startListening() {
        listening = true
        utteranceWordCount = 0
        utteranceLocked = false
        updateMicUi()
        try {
            ensureRecognizer().startListening(buildRecognizerIntent())
        } catch (_: Exception) {
            listening = false
            updateMicUi()
        }
    }

    private fun restartListening() {
        if (!listening) return
        try {
            ensureRecognizer().cancel()
            ensureRecognizer().startListening(buildRecognizerIntent())
        } catch (_: Exception) {
            /* ignore - onError will drive a retry if needed */
        }
    }

    private fun stopListening() {
        listening = false
        restartRunnable?.let { mainHandler.removeCallbacks(it) }
        try {
            recognizer?.stopListening()
        } catch (_: Exception) {
            /* ignore */
        }
        updateMicUi()
    }

    private fun scheduleRestart(delayMs: Long) {
        if (!listening) return
        restartRunnable?.let { mainHandler.removeCallbacks(it) }
        val r = Runnable { if (listening) restartListening() }
        restartRunnable = r
        mainHandler.postDelayed(r, delayMs)
    }

    private fun updateMicUi() {
        micIcon.isSelected = listening
        micButton.setBackgroundResource(R.drawable.mic_circle_bg)
        micButton.backgroundTintList = if (listening) {
            android.content.res.ColorStateList.valueOf(getColorCompat(R.color.ttt_recording))
        } else {
            null
        }
        compactRecDot.visibility = if (listening) View.VISIBLE else View.GONE
    }

    private fun wordCount(text: String): Int =
        text.trim().split(Regex("\\s+")).count { it.isNotEmpty() }

    private fun buildListener(): RecognitionListener = object : RecognitionListener {
        override fun onReadyForSpeech(params: Bundle?) {}
        override fun onBeginningOfSpeech() {}
        override fun onRmsChanged(rmsdB: Float) {}
        override fun onBufferReceived(buffer: ByteArray?) {}
        override fun onEndOfSpeech() {}

        override fun onPartialResults(partialResults: Bundle?) {
            val text = bestText(partialResults) ?: return
            maybeLockUtterance(text)
            transcriptPreview.text = (currentTranscript + " " + text).trim()
        }

        override fun onResults(results: Bundle?) {
            val text = bestText(results)
            if (!text.isNullOrBlank()) {
                maybeLockUtterance(text)
                currentTranscript = (currentTranscript + " " + text).trim()
                transcriptPreview.text = currentTranscript
            }
            utteranceWordCount = 0
            utteranceLocked = false
            val queued = pendingLanguage
            if (queued != null) {
                applyLanguage(queued)
            } else if (listening) {
                restartListening()
            }
        }

        override fun onError(error: Int) {
            if (!listening) return
            when (error) {
                SpeechRecognizer.ERROR_NO_MATCH, SpeechRecognizer.ERROR_SPEECH_TIMEOUT -> scheduleRestart(200)
                SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS -> {
                    listening = false
                    updateMicUi()
                    Toast.makeText(this@OverlayBubbleService, R.string.recognizer_unavailable, Toast.LENGTH_SHORT).show()
                }
                else -> scheduleRestart(500)
            }
        }

        @Suppress("DEPRECATION")
        override fun onEvent(eventType: Int, params: Bundle?) {}
    }

    // Partial/final results are each the FULL cumulative transcript of the
    // current utterance so far (not a delta), so the word count is simply
    // re-measured from the latest chunk rather than accumulated across calls.
    private fun maybeLockUtterance(latestChunk: String) {
        if (utteranceLocked) return
        utteranceWordCount = wordCount(latestChunk)
        if (utteranceWordCount >= AUTO_LOCK_WORD_COUNT) utteranceLocked = true
    }

    private fun bestText(bundle: Bundle?): String? {
        val list = bundle?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
        return list?.firstOrNull()
    }

    private fun copyTranscript() {
        val text = currentTranscript.trim()
        if (text.isEmpty()) return
        val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        clipboard.setPrimaryClip(ClipData.newPlainText("Talk-To-Text", text))
        Toast.makeText(this, R.string.copied_toast, Toast.LENGTH_SHORT).show()
    }

    /* ------------------------------------------------------------------ */
    /* foreground notification                                             */
    /* ------------------------------------------------------------------ */
    private fun buildNotification(): android.app.Notification {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val manager = getSystemService(NotificationManager::class.java)
            val channel = NotificationChannel(
                NOTIF_CHANNEL_ID,
                getString(R.string.notif_channel_name),
                NotificationManager.IMPORTANCE_MIN,
            )
            manager.createNotificationChannel(channel)
        }
        val openApp = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE,
        )
        return NotificationCompat.Builder(this, NOTIF_CHANNEL_ID)
            .setSmallIcon(R.mipmap.ic_launcher)
            .setContentTitle(getString(R.string.notif_title))
            .setContentText(getString(R.string.notif_text))
            .setContentIntent(openApp)
            .setOngoing(true)
            .setPriority(NotificationCompat.PRIORITY_MIN)
            .build()
    }

    private fun dp(value: Int): Int =
        (value * resources.displayMetrics.density).toInt()
}
