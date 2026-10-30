version = 3

cloudstream {
    authors = listOf("NropVox")
    language = "en"
    description = "Hollywood, dual-audio and anime movies and series from MoviesMod. Plays the DriveSeed mirrors of every quality: Cloud (R2) and Direct (worker) seek; Instant (Google) cannot seek and starts slowly."
    status = 1
    tvTypes = listOf("Movie", "TvSeries", "Anime")
    iconUrl = "https://www.google.com/s2/favicons?domain=moviesmod.meme&sz=%size%"

    isCrossPlatform = true
}

// The MoviesMod gate and DriveSeed chain is shared with OmniStream.
android.sourceSets.getByName("main").kotlin.srcDir("../shared/moviesmodsource")
