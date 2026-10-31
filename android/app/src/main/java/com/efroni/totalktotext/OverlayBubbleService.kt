package com.efroni.totalktotext

import android.animation.ValueAnimator
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.BroadcastReceiver
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.res.ColorStateList
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
 * Foreground service that owns one small floating window: a 48dp circular
 * bubble that is the single persistent control. A tap starts/stops
 * recording right there (the bubble glows red while listening); a
 * long-press opens a settings panel (language picker, transcript history,
 * close); dragging moves it, releasing snaps it to the nearest edge.
 *
 * Speech recognition runs entirely in-process via SpeechRecognizer with a
 * headless RecognitionListener - the OS's full-screen "Speak now" dialog is
 * never invoked anywhere in this app. Finalized text is copied straight to
 * the clipboard, and a small "paste" hint briefly appears next to the
 * bubble as a reminder. There is deliberately no AccessibilityService: that
 * capability is the same one real malware abuses to read/inject into other
 * apps' screens, and declaring BIND_ACCESSIBILITY_SERVICE gets sideloaded
 * APKs flagged hard by Play Protect and most antivirus scanners - not worth
 * it for a convenience the clipboard already covers.
 */
class OverlayBubbleService : Service() {

    companion object {
        var isRunning: Boolean = false
            private set

        private const val NOTIF_CHANNEL_ID = "ttt_bubble_channel"
        private const val NOTIF_ID = 1
        private const val EDGE_MARGIN_DP = 2
        private const val AUTO_LOCK_WORD_COUNT = 2
        private const val LONG_PRESS_MS = 450L
        private const val MOVE_THRESHOLD_DP = 6
    }

    private lateinit var windowManager: WindowManager
    private lateinit var params: WindowManager.LayoutParams
    private lateinit var root: FrameLayout

    private lateinit var compactBubble: FrameLayout
    private lateinit var pasteHint: TextView
    private lateinit var settingsPanel: LinearLayout
    private lateinit var languageSearch: EditText
    private lateinit var languageList: LinearLayout
    private lateinit var historyClearButton: FrameLayout
    private lateinit var historyList: LinearLayout
    private lateinit var closeBubbleButton: TextView

    private var recognizer: SpeechRecognizer? = null
    private var listening = false
    private var selectedLanguage: SpeechLanguage = Languages.AUTO_DETECT
    private var pendingLanguage: SpeechLanguage? = null
    private var utteranceWordCount = 0
    private var utteranceLocked = false
    private val transcriptHistory = mutableListOf<String>()
    // Populated asynchronously (if the device's recognizer answers at all) by
    // ACTION_GET_LANGUAGE_DETAILS; null means "unknown", in which case we
    // just try the requested language and let onError's fallback catch it.
    private var supportedLanguageTags: Set<String>? = null

    private val mainHandler = Handler(Looper.getMainLooper())
    private var restartRunnable: Runnable? = null
    private var longPressRunnable: Runnable? = null
    private var pasteHintRunnable: Runnable? = null
    private var glowAnimator: ValueAnimator? = null

    override fun onCreate() {
        super.onCreate()
        isRunning = true
        startForeground(NOTIF_ID, buildNotification())
        windowManager = getSystemService(WINDOW_SERVICE) as WindowManager
        inflateOverlay()
        wireInteractions()
        populateLanguageList(Languages.PICKER_ORDER)
        querySupportedLanguages()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int = START_STICKY

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        super.onDestroy()
        isRunning = false
        restartRunnable?.let { mainHandler.removeCallbacks(it) }
        longPressRunnable?.let { mainHandler.removeCallbacks(it) }
        pasteHintRunnable?.let { mainHandler.removeCallbacks(it) }
        glowAnimator?.cancel()
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
        pasteHint = root.findViewById(R.id.pasteHint)
        settingsPanel = root.findViewById(R.id.settingsPanel)
        languageSearch = root.findViewById(R.id.languageSearch)
        languageList = root.findViewById(R.id.languageList)
        historyClearButton = root.findViewById(R.id.historyClearButton)
        historyList = root.findViewById(R.id.historyList)
        closeBubbleButton = root.findViewById(R.id.closeBubbleButton)

        val overlayType =
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
            else WindowManager.LayoutParams.TYPE_PHONE

        params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            overlayType,
            // FLAG_NOT_TOUCH_MODAL is the important one here: without it, this
            // window - once it becomes focusable (see openSettingsPanel) - can
            // end up swallowing touches across the WHOLE screen instead of
            // just its own small bounds, which reads as "everything froze".
            // It stays set at all times; only FLAG_NOT_FOCUSABLE is ever toggled.
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
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
    /* gesture: tap = toggle recording, long-press = settings, drag = move */
    /* ------------------------------------------------------------------ */
    private fun wireInteractions() {
        var downRawX = 0f
        var downRawY = 0f
        var downParamX = 0
        var downParamY = 0
        var moved = false
        var longPressFired = false

        compactBubble.setOnTouchListener { _, event ->
            when (event.action) {
                MotionEvent.ACTION_DOWN -> {
                    downRawX = event.rawX
                    downRawY = event.rawY
                    downParamX = params.x
                    downParamY = params.y
                    moved = false
                    longPressFired = false
                    val runnable = Runnable {
                        longPressFired = true
                        openSettingsPanel()
                    }
                    longPressRunnable = runnable
                    mainHandler.postDelayed(runnable, LONG_PRESS_MS)
                    true
                }
                MotionEvent.ACTION_MOVE -> {
                    val dx = event.rawX - downRawX
                    val dy = event.rawY - downRawY
                    if (!moved && (abs(dx) > dp(MOVE_THRESHOLD_DP) || abs(dy) > dp(MOVE_THRESHOLD_DP))) {
                        moved = true
                        longPressRunnable?.let { mainHandler.removeCallbacks(it) }
                    }
                    if (moved) {
                        params.x = downParamX + dx.toInt()
                        params.y = downParamY + dy.toInt()
                        safeUpdateLayout()
                    }
                    true
                }
                MotionEvent.ACTION_UP -> {
                    longPressRunnable?.let { mainHandler.removeCallbacks(it) }
                    when {
                        longPressFired -> { /* already handled when the timer fired */ }
                        moved -> snapToNearestEdge()
                        else -> toggleListening()
                    }
                    true
                }
                MotionEvent.ACTION_CANCEL -> {
                    longPressRunnable?.let { mainHandler.removeCallbacks(it) }
                    true
                }
                else -> false
            }
        }

        historyClearButton.setOnClickListener { clearAllHistory() }
        closeBubbleButton.setOnClickListener { stopSelf() }

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
        val bubbleWidth = compactBubble.width.takeIf { it > 0 } ?: dp(48)
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
    /* compact bubble <-> settings panel                                   */
    /* ------------------------------------------------------------------ */
    private fun openSettingsPanel() {
        populateHistoryList()
        pasteHintRunnable?.let { mainHandler.removeCallbacks(it) }
        pasteHint.visibility = View.GONE
        compactBubble.visibility = View.GONE
        settingsPanel.visibility = View.VISIBLE
        // A text field inside an overlay needs real input focus for the IME
        // to appear if tapped, so drop FLAG_NOT_FOCUSABLE while the panel is up.
        params.flags = params.flags and WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE.inv()
        clampIntoScreen()
        safeUpdateLayout()
    }

    private fun closeSettingsPanel() {
        if (settingsPanel.visibility != View.VISIBLE) return
        val imm = getSystemService(INPUT_METHOD_SERVICE) as InputMethodManager
        imm.hideSoftInputFromWindow(languageSearch.windowToken, 0)
        params.flags = params.flags or WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
        languageSearch.setText("")
        settingsPanel.visibility = View.GONE
        compactBubble.visibility = View.VISIBLE
        clampIntoScreen()
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
        } else {
            applyLanguage(lang)
        }
        closeSettingsPanel()
    }

    private fun applyLanguage(lang: SpeechLanguage) {
        selectedLanguage = lang
        pendingLanguage = null
        if (listening) restartListening()
    }

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
        // Proactive check: if we already know (from querySupportedLanguages)
        // that this language's pack isn't on the device, don't even try it -
        // drop straight to Auto rather than starting a session that's certain
        // to fail and hang the mic in a "listening" state.
        if (selectedLanguage != Languages.AUTO_DETECT && isLanguageKnownUnsupported(selectedLanguage.tag)) {
            Toast.makeText(this, R.string.language_unavailable_toast, Toast.LENGTH_SHORT).show()
            selectedLanguage = Languages.AUTO_DETECT
        }
        listening = true
        utteranceWordCount = 0
        utteranceLocked = false
        updateRecordingUi()
        try {
            ensureRecognizer().startListening(buildRecognizerIntent())
        } catch (_: Exception) {
            listening = false
            updateRecordingUi()
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
        updateRecordingUi()
    }

    private fun scheduleRestart(delayMs: Long) {
        if (!listening) return
        restartRunnable?.let { mainHandler.removeCallbacks(it) }
        val r = Runnable { if (listening) restartListening() }
        restartRunnable = r
        mainHandler.postDelayed(r, delayMs)
    }

    private fun updateRecordingUi() {
        if (listening) {
            compactBubble.backgroundTintList = ColorStateList.valueOf(getColorCompat(R.color.ttt_recording))
            startGlowPulse()
        } else {
            compactBubble.backgroundTintList = null
            stopGlowPulse()
        }
    }

    private fun startGlowPulse() {
        if (glowAnimator?.isRunning == true) return
        val animator = ValueAnimator.ofFloat(1f, 0.55f, 1f)
        animator.duration = 900
        animator.repeatCount = ValueAnimator.INFINITE
        animator.addUpdateListener { compactBubble.alpha = it.animatedValue as Float }
        animator.start()
        glowAnimator = animator
    }

    private fun stopGlowPulse() {
        glowAnimator?.cancel()
        glowAnimator = null
        compactBubble.alpha = 1f
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
        }

        override fun onResults(results: Bundle?) {
            val text = bestText(results)
            if (!text.isNullOrBlank()) {
                val trimmed = text.trim()
                maybeLockUtterance(trimmed)
                pushHistory(trimmed)
                deliverFinalText(trimmed)
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
                SpeechRecognizer.ERROR_LANGUAGE_NOT_SUPPORTED, SpeechRecognizer.ERROR_LANGUAGE_UNAVAILABLE -> {
                    if (selectedLanguage != Languages.AUTO_DETECT) {
                        Toast.makeText(
                            this@OverlayBubbleService, R.string.language_unavailable_toast, Toast.LENGTH_SHORT
                        ).show()
                        applyLanguage(Languages.AUTO_DETECT)
                    } else {
                        // Even the device default failed - stop instead of looping forever.
                        listening = false
                        updateRecordingUi()
                        Toast.makeText(this@OverlayBubbleService, R.string.recognizer_unavailable, Toast.LENGTH_SHORT).show()
                    }
                }
                SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS -> {
                    listening = false
                    updateRecordingUi()
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

    /* ------------------------------------------------------------------ */
    /* delivering text: clipboard, plus a transient "paste" reminder       */
    /* ------------------------------------------------------------------ */
    private fun deliverFinalText(text: String) {
        autoCopyToClipboard(text)
        showPasteHint()
    }

    private fun autoCopyToClipboard(text: String) {
        if (text.isBlank()) return
        val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        clipboard.setPrimaryClip(ClipData.newPlainText("Talk-To-Text", text))
    }

    private fun showPasteHint() {
        if (compactBubble.visibility != View.VISIBLE) return // don't pop it over the settings panel
        pasteHintRunnable?.let { mainHandler.removeCallbacks(it) }
        // Keep the hint on-screen: show it on whichever side of the bubble
        // has room, instead of always the same side and sometimes running
        // off the edge of the display.
        val bubbleOnRightHalf = (params.x + compactBubble.width / 2) > screenWidthPx() / 2
        val lp = pasteHint.layoutParams as FrameLayout.LayoutParams
        if (bubbleOnRightHalf) {
            lp.gravity = Gravity.TOP or Gravity.END
            lp.marginEnd = dp(56)
            lp.marginStart = 0
        } else {
            lp.gravity = Gravity.TOP or Gravity.START
            lp.marginStart = dp(56)
            lp.marginEnd = 0
        }
        pasteHint.layoutParams = lp
        pasteHint.visibility = View.VISIBLE
        clampIntoScreen()
        val r = Runnable { pasteHint.visibility = View.GONE; clampIntoScreen() }
        pasteHintRunnable = r
        mainHandler.postDelayed(r, 4000)
    }

    /* ------------------------------------------------------------------ */
    /* transcript history                                                  */
    /* ------------------------------------------------------------------ */
    private fun populateHistoryList() {
        historyList.removeAllViews()
        if (transcriptHistory.isEmpty()) {
            val empty = TextView(this)
            empty.text = getString(R.string.transcript_placeholder)
            empty.setTextColor(getColorCompat(R.color.ttt_text_muted))
            empty.textSize = 12f
            empty.setPadding(dp(14), dp(18), dp(14), dp(18))
            historyList.addView(empty)
            return
        }
        transcriptHistory.forEachIndexed { idx, entry ->
            val row = LinearLayout(this)
            row.orientation = LinearLayout.HORIZONTAL
            row.gravity = Gravity.CENTER_VERTICAL
            row.setPadding(dp(10), dp(8), dp(10), dp(8))

            val text = TextView(this)
            text.text = entry
            text.setTextColor(getColorCompat(R.color.ttt_text_primary))
            text.textSize = 12f
            text.maxLines = 2
            text.layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)

            val copy = TextView(this)
            copy.text = getString(R.string.copy_label)
            copy.setTextColor(getColorCompat(R.color.ttt_accent))
            copy.textSize = 12f
            copy.setPadding(dp(10), dp(4), dp(6), dp(4))
            copy.setOnClickListener {
                autoCopyToClipboard(entry)
                Toast.makeText(this, R.string.copied_toast, Toast.LENGTH_SHORT).show()
            }

            val delete = TextView(this)
            delete.text = "✕"
            delete.contentDescription = getString(R.string.delete_item_desc)
            delete.setTextColor(getColorCompat(R.color.ttt_text_muted))
            delete.textSize = 12f
            delete.setPadding(dp(6), dp(4), dp(0), dp(4))
            delete.setOnClickListener { deleteHistoryItem(idx) }

            row.addView(text)
            row.addView(copy)
            row.addView(delete)
            historyList.addView(row)
        }
    }

    private fun pushHistory(text: String) {
        if (text.isEmpty()) return
        transcriptHistory.add(0, text)
        while (transcriptHistory.size > 20) transcriptHistory.removeAt(transcriptHistory.size - 1)
    }

    private fun deleteHistoryItem(idx: Int) {
        if (idx < 0 || idx >= transcriptHistory.size) return
        transcriptHistory.removeAt(idx)
        populateHistoryList()
    }

    private fun clearAllHistory() {
        transcriptHistory.clear()
        populateHistoryList()
        Toast.makeText(this, R.string.cleared_toast, Toast.LENGTH_SHORT).show()
    }

    /* ------------------------------------------------------------------ */
    /* language-pack availability                                         */
    /* ------------------------------------------------------------------ */
    private fun querySupportedLanguages() {
        try {
            val intent = RecognizerIntent.getVoiceDetailsIntent(this)
                ?: Intent(RecognizerIntent.ACTION_GET_LANGUAGE_DETAILS)
            sendOrderedBroadcast(
                intent, null,
                object : BroadcastReceiver() {
                    override fun onReceive(context: Context, received: Intent) {
                        val extras = getResultExtras(true)
                        val supported = extras.getStringArrayList(RecognizerIntent.EXTRA_SUPPORTED_LANGUAGES)
                        if (supported != null) supportedLanguageTags = supported.toSet()
                    }
                },
                null, 0, null, null,
            )
        } catch (_: Exception) {
            // Leave supportedLanguageTags null - startListening() will just try
            // the requested language and rely on onError's fallback above.
        }
    }

    /** Null if unknown (device didn't answer) - treated as "try it anyway". */
    private fun isLanguageKnownUnsupported(tag: String): Boolean {
        val known = supportedLanguageTags ?: return false
        return tag.isNotEmpty() && tag !in known
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
