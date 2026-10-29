package com.nropvox

import android.content.Context
import com.lagradost.cloudstream3.plugins.CloudstreamPlugin
import com.lagradost.cloudstream3.plugins.Plugin

@CloudstreamPlugin
class StreamCornerPlugin : Plugin() {
    // A Plugin rather than a BasePlugin: the WebViews that run the site's decoder need a Context.
    override fun load(context: Context) {
        val app = context.applicationContext
        registerMainAPI(StreamCorner { origin -> StreamCornerBridge(app, origin) })
    }
}
