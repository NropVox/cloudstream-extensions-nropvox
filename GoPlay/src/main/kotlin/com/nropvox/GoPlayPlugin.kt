package com.nropvox

import com.lagradost.cloudstream3.plugins.BasePlugin
import com.lagradost.cloudstream3.plugins.CloudstreamPlugin

@CloudstreamPlugin
class GoPlayPlugin : BasePlugin() {
    override fun load() {
        registerMainAPI(GoPlay())
    }
}
