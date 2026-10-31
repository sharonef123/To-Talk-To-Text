package com.efroni.totalktotext

/** One entry in the language picker: a human label and a BCP-47 recognizer tag. */
data class SpeechLanguage(val label: String, val tag: String)

/**
 * The 30 most-spoken languages, plus "Auto-Detect" as a distinct pseudo-entry
 * (tag = null means "let the device's default recognizer locale decide" -
 * see OverlayBubbleService for why true live multi-language detection isn't
 * something the public SpeechRecognizer API can do mid-utterance).
 */
object Languages {
    val AUTO_DETECT = SpeechLanguage("Auto-Detect", "")

    val ALL: List<SpeechLanguage> = listOf(
        SpeechLanguage("English (US)", "en-US"),
        SpeechLanguage("Hebrew", "he-IL"),
        SpeechLanguage("Spanish", "es-ES"),
        SpeechLanguage("Arabic", "ar-SA"),
        SpeechLanguage("French", "fr-FR"),
        SpeechLanguage("German", "de-DE"),
        SpeechLanguage("Russian", "ru-RU"),
        SpeechLanguage("Mandarin Chinese", "zh-CN"),
        SpeechLanguage("Portuguese", "pt-BR"),
        SpeechLanguage("Hindi", "hi-IN"),
        SpeechLanguage("Japanese", "ja-JP"),
        SpeechLanguage("Korean", "ko-KR"),
        SpeechLanguage("Italian", "it-IT"),
        SpeechLanguage("Turkish", "tr-TR"),
        SpeechLanguage("Vietnamese", "vi-VN"),
        SpeechLanguage("Polish", "pl-PL"),
        SpeechLanguage("Dutch", "nl-NL"),
        SpeechLanguage("Thai", "th-TH"),
        SpeechLanguage("Indonesian", "id-ID"),
        SpeechLanguage("Ukrainian", "uk-UA"),
        SpeechLanguage("Greek", "el-GR"),
        SpeechLanguage("Swedish", "sv-SE"),
        SpeechLanguage("Romanian", "ro-RO"),
        SpeechLanguage("Czech", "cs-CZ"),
        SpeechLanguage("Hungarian", "hu-HU"),
        SpeechLanguage("Finnish", "fi-FI"),
        SpeechLanguage("Danish", "da-DK"),
        SpeechLanguage("Norwegian", "nb-NO"),
        SpeechLanguage("Bengali", "bn-BD"),
        SpeechLanguage("Urdu", "ur-PK"),
    )

    /** Auto-Detect first, then every language alphabetically by label. */
    val PICKER_ORDER: List<SpeechLanguage> =
        listOf(AUTO_DETECT) + ALL.sortedBy { it.label }

    fun byTag(tag: String?): SpeechLanguage =
        ALL.find { it.tag == tag } ?: AUTO_DETECT
}
