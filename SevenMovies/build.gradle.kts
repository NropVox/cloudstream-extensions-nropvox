version = 3

cloudstream {
    authors = listOf("NropVox")
    language = "en"
    description = "Movies and TV shows from 7movies.ac with Vidrift sources and subtitles."
    status = 1
    tvTypes = listOf("Movie", "TvSeries")
    iconUrl = "https://www.google.com/s2/favicons?domain=7movies.ac&sz=%size%"

    isCrossPlatform = true
}

// 7movies' playback chain is shared with OmniStream.
android.sourceSets.getByName("main").kotlin.srcDir("../shared/sevenmoviessource")
