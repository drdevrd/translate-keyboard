package com.drdevrd.translatekeyboard

import android.os.Bundle
import android.widget.Button
import android.widget.EditText
import android.widget.Switch
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity

class SettingsActivity : AppCompatActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_settings)

        val prefs = getSharedPreferences(Prefs.NAME, MODE_PRIVATE)
        val apiKeyInput = findViewById<EditText>(R.id.apiKeyInput)
        val liveTranslateSwitch = findViewById<Switch>(R.id.liveTranslateSwitch)
        val saveButton = findViewById<Button>(R.id.saveButton)
        val statusText = findViewById<TextView>(R.id.statusText)

        apiKeyInput.setText(prefs.getString(Prefs.API_KEY, ""))
        liveTranslateSwitch.isChecked = prefs.getBoolean(Prefs.LIVE_TRANSLATE, true)

        saveButton.setOnClickListener {
            prefs.edit()
                .putString(Prefs.API_KEY, apiKeyInput.text.toString().trim())
                .putBoolean(Prefs.LIVE_TRANSLATE, liveTranslateSwitch.isChecked)
                .apply()
            statusText.text = getString(R.string.saved)
        }
    }
}

object Prefs {
    const val NAME = "translate_keyboard_prefs"
    const val API_KEY = "openai_api_key"
    const val TARGET_LANG = "target_lang" // "hi" or "ta"
    const val LIVE_TRANSLATE = "live_translate" // true = auto-translate as you pause/type
}
