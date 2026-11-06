package com.nropvox

import com.lagradost.cloudstream3.plugins.BasePlugin
import com.lagradost.cloudstream3.plugins.CloudstreamPlugin

@CloudstreamPlugin
class EpornerPlugin : BasePlugin() {
    override fun load() {
        registerMainAPI(Eporner())
    }
}
