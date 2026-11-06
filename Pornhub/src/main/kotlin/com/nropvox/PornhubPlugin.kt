package com.nropvox

import com.lagradost.cloudstream3.plugins.BasePlugin
import com.lagradost.cloudstream3.plugins.CloudstreamPlugin

@CloudstreamPlugin
class PornhubPlugin : BasePlugin() {
    override fun load() {
        registerMainAPI(Pornhub())
    }
}
