package com.nropvox

import android.content.Context
import com.lagradost.cloudstream3.plugins.CloudstreamPlugin
import com.lagradost.cloudstream3.plugins.Plugin

@CloudstreamPlugin
class JavStreamPlugin : Plugin() {
    override fun load(context: Context) {
        registerMainAPI(JavStream())
    }
}
