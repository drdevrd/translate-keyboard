package com.drdevrd.translatekeyboard

import android.content.ClipboardManager
import android.content.Context
import android.inputmethodservice.InputMethodService
import android.inputmethodservice.Keyboard
import android.inputmethodservice.KeyboardView
import android.os.Handler
import android.os.Looper
import android.view.View
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputConnection
import android.widget.Button
import android.widget.HorizontalScrollView
import android.widget.LinearLayout
import android.widget.TextView

class TranslateKeyboardService : InputMethodService(), KeyboardView.OnKeyboardActionListener {

    private lateinit var keyboardView: KeyboardView
    private lateinit var qwertyKeyboard: Keyboard
    private lateinit var symbolsKeyboard: Keyboard

    private lateinit var suggestionScroll: HorizontalScrollView
    private lateinit var suggestionStrip: LinearLayout
    private lateinit var translationPanel: LinearLayout
    private lateinit var translationText: TextView
    private lateinit var transliterationText: TextView
    private lateinit var explanationText: TextView
    private lateinit var insertButton: Button
    private lateinit var langToggleButton: Button
    private lateinit var translateNowButton: Button
    private lateinit var reverseTranslateButton: Button
    private lateinit var dismissButton: Button

    private lateinit var autocorrect: AutocorrectEngine
    private var openAi: OpenAiTranslator? = null

    private var caps = false
    private var usingSymbols = false
    private val currentWord = StringBuilder()

    private val mainHandler = Handler(Looper.getMainLooper())
    private var translateRunnable: Runnable? = null
    private val TRANSLATE_DEBOUNCE_MS = 800L

    private var targetLang = "hi" // "hi" = Hindi, "ta" = Tamil
    private var liveTranslateEnabled = true
    private var lastTranslation: TranslationResult? = null

    override fun onCreate() {
        super.onCreate()
        autocorrect = AutocorrectEngine(this)
        loadPrefs()
    }

    private fun loadPrefs() {
        val prefs = getSharedPreferences(Prefs.NAME, MODE_PRIVATE)
        targetLang = prefs.getString(Prefs.TARGET_LANG, "hi") ?: "hi"
        liveTranslateEnabled = prefs.getBoolean(Prefs.LIVE_TRANSLATE, true)
        val key = prefs.getString(Prefs.API_KEY, "") ?: ""
        openAi = if (key.isNotBlank()) OpenAiTranslator(key) else null
    }

    override fun onCreateInputView(): View {
        val container = layoutInflater.inflate(R.layout.keyboard_container, null)

        keyboardView = container.findViewById(R.id.keyboardView)
        suggestionScroll = container.findViewById(R.id.suggestionScroll)
        suggestionStrip = container.findViewById(R.id.suggestionStrip)
        translationPanel = container.findViewById(R.id.translationPanel)
        translationText = container.findViewById(R.id.translationText)
        transliterationText = container.findViewById(R.id.transliterationText)
        explanationText = container.findViewById(R.id.explanationText)
        insertButton = container.findViewById(R.id.insertButton)
        langToggleButton = container.findViewById(R.id.langToggleButton)
        translateNowButton = container.findViewById(R.id.translateNowButton)
        reverseTranslateButton = container.findViewById(R.id.reverseTranslateButton)
        dismissButton = container.findViewById(R.id.dismissButton)

        qwertyKeyboard = Keyboard(this, R.xml.keyboard_qwerty)
        symbolsKeyboard = Keyboard(this, R.xml.keyboard_symbols)
        keyboardView.keyboard = qwertyKeyboard
        keyboardView.setOnKeyboardActionListener(this)

        updateLangButtonLabel()

        langToggleButton.setOnClickListener {
            targetLang = if (targetLang == "hi") "ta" else "hi"
            getSharedPreferences(Prefs.NAME, MODE_PRIVATE).edit()
                .putString(Prefs.TARGET_LANG, targetLang).apply()
            updateLangButtonLabel()
        }

        translateNowButton.setOnClickListener {
            mainHandler.removeCallbacks(translateRunnable ?: Runnable {})
            performTranslate()
        }

        reverseTranslateButton.setOnClickListener { translateFromClipboard() }

        insertButton.setOnClickListener { insertTranslation() }
        dismissButton.setOnClickListener { translationPanel.visibility = View.GONE }

        return container
    }

    override fun onStartInputView(info: EditorInfo?, restarting: Boolean) {
        super.onStartInputView(info, restarting)
        currentWord.clear()
        suggestionScroll.visibility = View.GONE
        translationPanel.visibility = View.GONE
        loadPrefs()
    }

    private fun updateLangButtonLabel() {
        langToggleButton.text = if (targetLang == "hi") getString(R.string.target_hindi) else getString(R.string.target_tamil)
    }

    // ---- KeyboardView.OnKeyboardActionListener ----

    override fun onKey(primaryCode: Int, keyCodes: IntArray?) {
        val ic = currentInputConnection ?: return
        when (primaryCode) {
            Keyboard.KEYCODE_SHIFT -> {
                caps = !caps
                qwertyKeyboard.isShifted = caps
                keyboardView.invalidateAllKeys()
            }
            Keyboard.KEYCODE_DELETE -> {
                if (currentWord.isNotEmpty()) {
                    currentWord.deleteCharAt(currentWord.length - 1)
                }
                ic.deleteSurroundingText(1, 0)
                refreshSuggestions()
                if (liveTranslateEnabled) scheduleTranslate()
            }
            Keyboard.KEYCODE_DONE -> {
                finishWord(ic)
                ic.commitText("\n", 1)
                if (liveTranslateEnabled) performTranslate()
            }
            -2 -> { // ABC / 123 toggle
                usingSymbols = !usingSymbols
                keyboardView.keyboard = if (usingSymbols) symbolsKeyboard else qwertyKeyboard
                keyboardView.invalidateAllKeys()
            }
            -10 -> { // globe key: switch to next input method
                switchToNextInputMethod(false)
            }
            32 -> { // space
                finishWord(ic)
                ic.commitText(" ", 1)
                if (liveTranslateEnabled) scheduleTranslate()
            }
            10 -> { // newline via symbol keyboard, treat like done
                finishWord(ic)
                ic.commitText("\n", 1)
                if (liveTranslateEnabled) performTranslate()
            }
            else -> {
                var code = primaryCode.toChar()
                if (Character.isLetter(code) && caps) {
                    code = Character.toUpperCase(code)
                }
                ic.commitText(code.toString(), 1)
                if (Character.isLetter(code)) {
                    currentWord.append(code)
                    refreshSuggestions()
                } else {
                    // punctuation ends the word/sentence
                    finishWord(ic)
                    if (liveTranslateEnabled) {
                        if (code == '.' || code == '?' || code == '!') {
                            performTranslate()
                        } else {
                            scheduleTranslate()
                        }
                    }
                }
            }
        }
    }

    /** Applies a confident autocorrect fix to the word just finished, if any. */
    private fun finishWord(ic: InputConnection) {
        if (currentWord.isNotEmpty()) {
            val fix = autocorrect.autoFix(currentWord.toString())
            if (fix != null && fix != currentWord.toString().lowercase()) {
                ic.deleteSurroundingText(currentWord.length, 0)
                ic.commitText(fix, 1)
            }
        }
        currentWord.clear()
        suggestionScroll.visibility = View.GONE
        suggestionStrip.removeAllViews()
    }

    private fun refreshSuggestions() {
        suggestionStrip.removeAllViews()
        if (currentWord.length < 2) {
            suggestionScroll.visibility = View.GONE
            return
        }
        val suggestions = autocorrect.suggest(currentWord.toString())
        if (suggestions.isEmpty()) {
            suggestionScroll.visibility = View.GONE
            return
        }
        suggestionScroll.visibility = View.VISIBLE
        for (word in suggestions) {
            val chip = Button(this)
            chip.text = word
            chip.textSize = 13f
            chip.isAllCaps = false
            chip.setOnClickListener { replaceCurrentWord(word) }
            suggestionStrip.addView(chip)
        }
    }

    private fun replaceCurrentWord(word: String) {
        val ic = currentInputConnection ?: return
        ic.deleteSurroundingText(currentWord.length, 0)
        ic.commitText(word, 1)
        currentWord.clear()
        currentWord.append(word)
        suggestionScroll.visibility = View.GONE
        if (liveTranslateEnabled) scheduleTranslate()
    }

    // ---- Forward translation (English typed -> Hindi/Tamil) ----

    private fun scheduleTranslate() {
        translateRunnable?.let { mainHandler.removeCallbacks(it) }
        val r = Runnable { performTranslate() }
        translateRunnable = r
        mainHandler.postDelayed(r, TRANSLATE_DEBOUNCE_MS)
    }

    private fun currentSentence(): String {
        val ic = currentInputConnection ?: return ""
        val before = ic.getTextBeforeCursor(200, 0)?.toString() ?: ""
        val lastBreak = before.lastIndexOfAny(charArrayOf('.', '?', '!', '\n'))
        return before.substring(lastBreak + 1).trim()
    }

    private fun performTranslate() {
        val text = currentSentence()
        if (text.isBlank()) return

        val client = openAi
        if (client == null) {
            translationPanel.visibility = View.VISIBLE
            translationText.text = ""
            transliterationText.text = ""
            explanationText.text = "Set your OpenAI API key in the Translate Keyboard app first."
            return
        }

        val targetName = if (targetLang == "hi") "Hindi" else "Tamil"
        translationPanel.visibility = View.VISIBLE
        translationText.text = "Translating..."
        transliterationText.text = ""
        explanationText.text = ""

        client.translateAsync(
            text = text,
            targetLanguageName = targetName,
            onResult = { result ->
                mainHandler.post {
                    if (result != null) {
                        lastTranslation = result
                        translationText.text = result.translation
                        transliterationText.text = result.transliteration
                        explanationText.text = result.explanation
                    }
                }
            },
            onError = { message ->
                mainHandler.post {
                    translationText.text = ""
                    explanationText.text = "Translation error: $message"
                }
            }
        )
    }

    private fun insertTranslation() {
        val result = lastTranslation ?: return
        val ic = currentInputConnection ?: return
        val sentence = currentSentence()
        if (sentence.isNotEmpty()) {
            ic.deleteSurroundingText(sentence.length, 0)
        }
        ic.commitText(result.translation + " ", 1)
        translationPanel.visibility = View.GONE
        currentWord.clear()
    }

    // ---- Reverse translation (copied Hindi/Tamil/Hinglish reply -> English) ----

    private fun translateFromClipboard() {
        val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        val clip = clipboard.primaryClip
        val text = clip?.getItemAt(0)?.coerceToText(this)?.toString()?.trim().orEmpty()

        if (text.isEmpty()) {
            translationPanel.visibility = View.VISIBLE
            translationText.text = ""
            transliterationText.text = ""
            explanationText.text = "Copy the message you received first, then tap this button."
            return
        }

        val client = openAi
        if (client == null) {
            translationPanel.visibility = View.VISIBLE
            translationText.text = ""
            transliterationText.text = ""
            explanationText.text = "Set your OpenAI API key in the Translate Keyboard app first."
            return
        }

        translationPanel.visibility = View.VISIBLE
        translationText.text = "Translating..."
        transliterationText.text = ""
        explanationText.text = ""
        lastTranslation = null // reverse result isn't insertable as a target-language translation

        client.reverseTranslateAsync(
            text = text,
            onResult = { result ->
                mainHandler.post {
                    if (result != null) {
                        translationText.text = result.englishTranslation
                        transliterationText.text = ""
                        explanationText.text = if (result.detectedLanguage.isNotBlank())
                            "Detected: ${result.detectedLanguage}" else ""
                    }
                }
            },
            onError = { message ->
                mainHandler.post {
                    translationText.text = ""
                    explanationText.text = "Translation error: $message"
                }
            }
        )
    }

    // ---- Unused listener callbacks ----
    override fun onPress(primaryCode: Int) {}
    override fun onRelease(primaryCode: Int) {}
    override fun onText(text: CharSequence?) {}
    override fun swipeLeft() {}
    override fun swipeRight() {}
    override fun swipeDown() {}
    override fun swipeUp() {}
}
