package com.nropvox

import com.lagradost.cloudstream3.plugins.BasePlugin
import com.lagradost.cloudstream3.plugins.CloudstreamPlugin

@CloudstreamPlugin
class TonkaCinePlugin : BasePlugin() {
    override fun load() {
        registerMainAPI(TonkaCine())
    }
}
