package com.github.digitallyrefined.androidipcamera

import android.content.Intent
import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.preference.ListPreference
import androidx.preference.PreferenceFragmentCompat
import androidx.preference.SwitchPreferenceCompat

class SettingsActivity : AppCompatActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // Ładujemy fragment jako zawartość aktywności
        supportFragmentManager
            .beginTransaction()
            .replace(android.R.id.content, SettingsFragment())
            .commit()
    }
}

// Fragment zawierający preferencje (ustawienia)
class SettingsFragment : PreferenceFragmentCompat() {

    override fun onCreatePreferences(savedInstanceState: Bundle?, rootKey: String?) {
        setPreferencesFromResource(R.xml.preferences, rootKey)

        val streamSwitch = findPreference<SwitchPreferenceCompat>("stream_enabled")
        val fpsPref = findPreference<ListPreference>("stream_fps")

        // Zablokuj zmianę FPS jeśli włączony streaming
        val isStreamingEnabled = preferenceManager.sharedPreferences?.getBoolean("stream_enabled", false) ?: false
        fpsPref?.isEnabled = !isStreamingEnabled

        streamSwitch?.setOnPreferenceChangeListener { _, newValue ->
            val enabled = newValue as Boolean
            fpsPref?.isEnabled = !enabled

            // Włącz/wyłącz usługę MJPEG
            val context = requireContext()
            val intent = Intent(context, MjpegStreamService::class.java)
            if (enabled) {
                ContextCompat.startForegroundService(context, intent)
            } else {
                context.stopService(intent)
            }

            true
        }
    }
}
