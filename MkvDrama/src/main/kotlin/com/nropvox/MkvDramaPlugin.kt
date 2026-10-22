package com.nropvox

import android.content.Context
import androidx.appcompat.app.AppCompatActivity
import com.lagradost.cloudstream3.plugins.CloudstreamPlugin
import com.lagradost.cloudstream3.plugins.Plugin

@CloudstreamPlugin
class MkvDramaPlugin : Plugin() {
    companion object {
        private const val PREF_FILE = "MkvDrama"
    }

    override fun load(context: Context) {
        val sharedPref = context.getSharedPreferences(PREF_FILE, Context.MODE_PRIVATE)

        registerMainAPI(MkvDrama(sharedPref))

        openSettings = { ctx ->
            val activity = ctx as AppCompatActivity
            if (!activity.isFinishing && !activity.isDestroyed) {
                MkvDramaSettingsFragment(this, sharedPref)
                    .show(activity.supportFragmentManager, "mkvdrama_settings")
            }
        }
    }
}
