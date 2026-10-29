version = 2

cloudstream {
    authors = listOf("NropVox")
    language = "en"
    description =
        "Movies, TV shows and anime from Cno.to. Plays Cno.to's own HLS servers (up to 4K) with subtitles in many languages."

    /**
     * Status int as the following:
     * 0: Down
     * 1: Ok
     * 2: Slow
     * 3: Beta only
     **/
    status = 1 // will be 3 if unspecified
    tvTypes = listOf("Movie", "TvSeries", "Anime", "AnimeMovie")
    iconUrl = "https://www.google.com/s2/favicons?domain=cno.to&sz=%size%"

    isCrossPlatform = true
}
