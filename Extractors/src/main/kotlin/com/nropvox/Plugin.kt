package com.nropvox

import android.content.Context
import androidx.appcompat.app.AppCompatActivity
import com.lagradost.cloudstream3.plugins.CloudstreamPlugin
import com.lagradost.cloudstream3.plugins.Plugin

@CloudstreamPlugin
class ExtractorPlugin : Plugin() {
    companion object {
        private const val PREF_FILE = "Extractors"
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
                ExtractorsSettingsFragment(this, sharedPref)
                    .show(activity.supportFragmentManager, "extractors_settings")
            }
        }
    }
}
