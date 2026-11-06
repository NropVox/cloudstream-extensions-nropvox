package com.nropvox

import android.content.Context
import androidx.appcompat.app.AppCompatActivity
import com.lagradost.cloudstream3.plugins.CloudstreamPlugin
import com.lagradost.cloudstream3.plugins.Plugin

@CloudstreamPlugin
class ExtractorPlugin : Plugin() {
    companion object {
        private const val PREF_FILE = "Extractors"

        /**
         * This plugin's settings for Cloudstream Desktop's web UI, which can't show the Android sheet: one entry per
         * preference key (string, secret, boolean, int or choice). Android ignores it.
         */
        const val SETTINGS_SCHEMA = """[
            {"file": "$PREF_FILE", "key": "$HLS_PROXY_URL_PREF_KEY", "type": "string", "label": "HLS proxy URL",
             "help": "Streams whose host needs special headers are played through this proxy. Leave it empty to use the default.",
             "default": "$HLS_PROXY_DEFAULT_URL"}
        ]"""
    }

    override fun load(context: Context) {
        val sharedPref = context.getSharedPreferences(PREF_FILE, Context.MODE_PRIVATE)
        registerExtractorAPI(Vidara())
        registerExtractorAPI(RubyVidHub())
        registerExtractorAPI(SubtitleCat())
        registerExtractorAPI(Turboplayers())
        registerExtractorAPI(DoodStream())
        registerExtractorAPI(DoodDoply())
        registerExtractorAPI(DoodVideo())
        registerExtractorAPI(Ds2Play())
        registerExtractorAPI(D000d())
        registerExtractorAPI(VidHidePro1())
        registerExtractorAPI(VidHidePro2())
        registerExtractorAPI(VidHidePro3())
        registerExtractorAPI(VidHidePro4())
        registerExtractorAPI(VidHidePro6())
        registerExtractorAPI(VidHidePro7())
        registerExtractorAPI(VidhideVIP())
        registerExtractorAPI(Javlion())
        registerExtractorAPI(Dhcplay())
        registerExtractorAPI(Smoothpre())
        registerExtractorAPI(Dhtpre())
        registerExtractorAPI(Peytonepre())
        registerExtractorAPI(Movearnpre())
        registerExtractorAPI(Dintezuvio())
        registerExtractorAPI(Streamhihi())
        registerExtractorAPI(Javsw())
        registerExtractorAPI(Swhoi())
        registerExtractorAPI(Javmoon())
        registerExtractorAPI(MixDropis())
        registerExtractorAPI(Javclan())
        registerExtractorAPI(Javggvideo())
        registerExtractorAPI(LulusStream())
        registerExtractorAPI(HgLink())
        registerExtractorAPI(RyderJet())
        registerExtractorAPI(MyCloudZ())
        registerExtractorAPI(StbP2P())
        registerExtractorAPI(Playerupnone())
        registerExtractorAPI(F4Stream())
        registerExtractorAPI(F4Scdn())
        registerExtractorAPI(Turtleviplay())
        registerExtractorAPI(Turboviplay())
        registerExtractorAPI(Hanerix())
        registerExtractorAPI(JavVids())
        registerExtractorAPI(Reely())
        registerExtractorAPI(HLSProxy(sharedPref))
        registerExtractorAPI(Emturbovid())
        registerExtractorAPI(AsnWish())
        registerExtractorAPI(Fc2Stream())
        registerExtractorAPI(Vvaassjjav())
        registerExtractorAPI(Evsishere())
        registerExtractorAPI(Luminscdn())
        registerExtractorAPI(JavtifulPage())
        registerExtractorAPI(SexTBPage())
        registerExtractorAPI(MissAVPage())

        openSettings = { ctx ->
            val activity = ctx as AppCompatActivity
            if (!activity.isFinishing && !activity.isDestroyed) {
                PluginSettingsSheet(
                    this, "Extractors", extractorsSettings(sharedPref),
                    "Changes apply to streams loaded after saving; an episode you already opened keeps its cached links.",
                ).show(activity.supportFragmentManager, "extractors_settings")
            }
        }
    }
}
