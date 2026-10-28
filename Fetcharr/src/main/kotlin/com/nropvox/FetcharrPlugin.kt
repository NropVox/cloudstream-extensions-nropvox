package com.nropvox

import android.content.Context
import com.lagradost.cloudstream3.plugins.CloudstreamPlugin
import com.lagradost.cloudstream3.plugins.Plugin

/** Adds the Fetcharr hand-off actions to every provider's episode/movie action menu. */
@CloudstreamPlugin
class FetcharrPlugin : Plugin() {
    override fun load(context: Context) {
        registerVideoClickAction(SendToFetcharrAction())
        registerVideoClickAction(SendSeasonToFetcharrAction())
    }
}
