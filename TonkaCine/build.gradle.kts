version = 3

cloudstream {
    authors = listOf("NropVox")
    language = "en"
    description = "Movies, series, anime and cartoons from multi.tonkacine.watch."
    status = 3
    tvTypes = listOf("Movie", "TvSeries")
    iconUrl = "https://www.google.com/s2/favicons?domain=multi.tonkacine.watch&sz=%size%"

    isCrossPlatform = true
}

// The player resolvers are shared with OmniStream.
android.sourceSets.getByName("main").kotlin.srcDir("../shared/tonkacinesource")
