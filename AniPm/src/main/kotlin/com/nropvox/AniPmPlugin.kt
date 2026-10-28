package com.nropvox

import com.lagradost.cloudstream3.plugins.BasePlugin
import com.lagradost.cloudstream3.plugins.CloudstreamPlugin

@CloudstreamPlugin
class AniPmPlugin : BasePlugin() {
    override fun load() {
        registerMainAPI(AniPm())
    }
}
