package com.drdevrd.translatekeyboard

import android.Manifest
import android.content.pm.PackageManager
import android.os.Bundle
import android.widget.Button
import android.widget.EditText
import android.widget.Switch
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat

class SettingsActivity : AppCompatActivity() {

    private val MIC_PERMISSION_CODE = 1001
    private lateinit var micStatusText: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_settings)

        val prefs = getSharedPreferences(Prefs.NAME, MODE_PRIVATE)
        val apiKeyInput = findViewById<EditText>(R.id.apiKeyInput)
        val liveTranslateSwitch = findViewById<Switch>(R.id.liveTranslateSwitch)
        val saveButton = findViewById<Button>(R.id.saveButton)
        val statusText = findViewById<TextView>(R.id.statusText)
        val grantMicButton = findViewById<Button>(R.id.grantMicButton)
        micStatusText = findViewById(R.id.micStatusText)

        apiKeyInput.setText(prefs.getString(Prefs.API_KEY, ""))
        liveTranslateSwitch.isChecked = prefs.getBoolean(Prefs.LIVE_TRANSLATE, true)

        saveButton.setOnClickListener {
            prefs.edit()
                .putString(Prefs.API_KEY, apiKeyInput.text.toString().trim())
                .putBoolean(Prefs.LIVE_TRANSLATE, liveTranslateSwitch.isChecked)
                .apply()
            statusText.text = getString(R.string.saved)
        }

        grantMicButton.setOnClickListener {
            ActivityCompat.requestPermissions(
                this,
                arrayOf(Manifest.permission.RECORD_AUDIO),
                MIC_PERMISSION_CODE
            )
        }

        updateMicStatus()
    }

    override fun onResume() {
        super.onResume()
        updateMicStatus()
    }

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        updateMicStatus()
    }

    private fun updateMicStatus() {
        val granted = ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) ==
            PackageManager.PERMISSION_GRANTED
        micStatusText.text = if (granted) getString(R.string.mic_granted) else getString(R.string.mic_not_granted)
    }
}

object Prefs {
    const val NAME = "translate_keyboard_prefs"
    const val API_KEY = "openai_api_key"
    const val TARGET_LANG = "target_lang" // "hi" or "ta"
    const val LIVE_TRANSLATE = "live_translate" // true = auto-translate as you pause/type
}
