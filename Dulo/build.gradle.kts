version = 2

cloudstream {
    authors = listOf("NropVox")
    language = "en"
    description =
        "Movies, TV shows and anime from dulo.mov (TMDB catalog). Plays dulo's own Pickle HLS (up to 4K) plus whichever extra servers its resolver finds (Cobalt, Breeze, Shinobi…), with subtitles in ~20 languages."

    /**
     * Status int as the following:
     * 0: Down
     * 1: Ok
     * 2: Slow
     * 3: Beta only
     **/
    status = 1 // will be 3 if unspecified
    tvTypes = listOf("Movie", "TvSeries", "Anime")
    iconUrl = "https://www.google.com/s2/favicons?domain=dulo.mov&sz=%size%"

    isCrossPlatform = true
}
