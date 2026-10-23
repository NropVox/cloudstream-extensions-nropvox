package com.nropvox

import com.lagradost.cloudstream3.plugins.BasePlugin
import com.lagradost.cloudstream3.plugins.CloudstreamPlugin

@CloudstreamPlugin
class AnimoTvSlashPlugin : BasePlugin() {
    override fun load() {
        registerMainAPI(AnimoTvSlash())
    }
}
