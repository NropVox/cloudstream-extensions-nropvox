package com.nropvox

import com.lagradost.cloudstream3.plugins.BasePlugin
import com.lagradost.cloudstream3.plugins.CloudstreamPlugin

@CloudstreamPlugin
class CnoToPlugin : BasePlugin() {
    override fun load() {
        registerMainAPI(CnoTo())
    }
}
