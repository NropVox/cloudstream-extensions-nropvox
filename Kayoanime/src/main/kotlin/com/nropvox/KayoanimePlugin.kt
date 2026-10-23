package com.nropvox

import android.app.Activity
import android.app.AlertDialog
import android.content.Context
import android.content.SharedPreferences
import android.text.InputType
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView
import com.lagradost.cloudstream3.plugins.CloudstreamPlugin
import com.lagradost.cloudstream3.plugins.Plugin

@CloudstreamPlugin
class KayoanimePlugin : Plugin() {
    override fun load(context: Context) {
        val sharedPref = context.getSharedPreferences(Kayoanime.PREF_FILE, Context.MODE_PRIVATE)
        registerMainAPI(Kayoanime(sharedPref))

        // Built in code rather than from a layout, so the plugin needs no resources.
        openSettings = { ctx ->
            val activity = ctx as Activity
            if (!activity.isFinishing && !activity.isDestroyed) showSettings(activity, sharedPref)
        }
    }

    private fun showSettings(activity: Activity, pref: SharedPreferences) {
        val pad = (16 * activity.resources.displayMetrics.density).toInt()
        fun field(hint: String, value: String, secret: Boolean) = EditText(activity).apply {
            setHint(hint)
            setText(value)
            setSingleLine()
            inputType = InputType.TYPE_CLASS_TEXT or
                if (secret) InputType.TYPE_TEXT_VARIATION_PASSWORD else InputType.TYPE_TEXT_VARIATION_URI
            tag = hint
        }
        val url = field("Proxy URL", pref.getString(Kayoanime.PROXY_URL_KEY, "").orEmpty(), secret = false)
        val key = field("API key", pref.getString(Kayoanime.PROXY_KEY_KEY, "").orEmpty(), secret = true)
        val form = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(pad, pad, pad, 0)
            addView(TextView(activity).apply {
                text = "Episodes are Google Drive files served by your own Drive proxy. " +
                    "Leave the URL empty for ${Kayoanime.DEFAULT_PROXY_URL}."
            })
            addView(url)
            addView(key)
        }
        AlertDialog.Builder(activity)
            .setTitle("Kayoanime")
            .setView(form)
            .setPositiveButton("Save") { _, _ ->
                pref.edit()
                    .putString(Kayoanime.PROXY_URL_KEY, url.text.toString().trim().trimEnd('/'))
                    .putString(Kayoanime.PROXY_KEY_KEY, key.text.toString().trim())
                    .apply()
            }
            .setNegativeButton("Cancel", null)
            .show()
    }
}
