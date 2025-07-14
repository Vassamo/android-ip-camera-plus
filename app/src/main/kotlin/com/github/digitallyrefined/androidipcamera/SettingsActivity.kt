package com.github.digitallyrefined.androidipcamera

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import androidx.appcompat.app.AppCompatActivity
import androidx.preference.ListPreference
import androidx.preference.Preference
import androidx.preference.PreferenceFragmentCompat

class SettingsActivity : AppCompatActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        supportFragmentManager
            .beginTransaction()
            .replace(android.R.id.content, SettingsFragment())
            .commit()
    }

    class SettingsFragment : PreferenceFragmentCompat() {
        companion object {
            private const val PICK_CERTIFICATE_FILE = 1
        }

        override fun onCreatePreferences(savedInstanceState: Bundle?, rootKey: String?) {
            setPreferencesFromResource(R.xml.preferences, rootKey)

            // Dodajemy dynamiczną listę FPS
            val context = preferenceManager.context
            val supportedFps = CameraUtils.getAvailableFpsOptions(context)

            val fpsPref = ListPreference(context).apply {
                key = "stream_fps"
                title = "Camera Stream FPS"
                entries = supportedFps.map { "$it FPS" }.toTypedArray()
                entryValues = supportedFps.map { it.toString() }.toTypedArray()
                summary = "%s"
                setDefaultValue(supportedFps.min().toString())
            }

            preferenceScreen.addPreference(fpsPref)
        }


        override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
            if (requestCode == PICK_CERTIFICATE_FILE && resultCode == Activity.RESULT_OK) {
                data?.data?.let { uri ->
                    // Store the certificate path
                    val certificatePath = uri.toString()
                    preferenceManager.sharedPreferences?.edit()?.apply {
                        putString("certificate_path", certificatePath)
                        apply()
                    }
                    // Update the preference summary
                    findPreference<Preference>("certificate_path")?.summary = certificatePath
                }
            }
            super.onActivityResult(requestCode, resultCode, data)
        }
    }
}
