package com.nropvox

import android.content.SharedPreferences
import androidx.core.content.edit

internal fun extractorsSettings(pref: SharedPreferences) = listOf(
    SettingSection(
        "Playback",
        listOf(
            SettingField(
                kind = SettingField.Kind.URL,
                title = "HLS proxy URL",
                description = "Streams whose host needs special headers are played through this proxy. " +
                    "Leave it empty to use $HLS_PROXY_DEFAULT_URL.",
                label = "Proxy URL",
                hint = HLS_PROXY_DEFAULT_URL,
                unsetSummary = "Default · ${HLS_PROXY_DEFAULT_URL.removePrefix("https://")}",
                setSummary = { "Custom · ${it.removePrefix("https://").removePrefix("http://").substringBefore('/')}" },
                get = { pref.getString(HLS_PROXY_URL_PREF_KEY, "").orEmpty().trim().takeIf { it != HLS_PROXY_DEFAULT_URL }.orEmpty() },
                set = { pref.edit { putString(HLS_PROXY_URL_PREF_KEY, it) } },
                normalize = ::normalizeSettingUrl,
                clearLabel = "Reset",
            ),
        ),
    ),
)
