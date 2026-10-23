package com.nropvox

import com.lagradost.cloudstream3.plugins.BasePlugin
import com.lagradost.cloudstream3.plugins.CloudstreamPlugin

@CloudstreamPlugin
class ReanimePlugin : BasePlugin() {
    override fun load() {
        registerMainAPI(Reanime())
    }
}
