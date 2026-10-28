version = 1

cloudstream {
    authors = listOf("NropVox")
    language = "en"
    description =
        "Anime from AnimeX (animex.one). Sub and dub HLS streams from several servers, with multi-language soft subtitles."

    /**
     * Status int as the following:
     * 0: Down
     * 1: Ok
     * 2: Slow
     * 3: Beta only
     **/
    status = 1 // will be 3 if unspecified
    tvTypes = listOf("Anime", "AnimeMovie", "OVA")
    iconUrl = "https://www.google.com/s2/favicons?domain=animex.one&sz=%size%"
}
