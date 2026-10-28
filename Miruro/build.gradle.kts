version = 1

cloudstream {
    authors = listOf("NropVox")
    language = "en"
    description =
        "Anime from Miruro. Sub, soft-sub and dub tracks from several servers, with selectable subtitles."

    /**
     * Status int as the following:
     * 0: Down
     * 1: Ok
     * 2: Slow
     * 3: Beta only
     **/
    status = 1 // will be 3 if unspecified
    tvTypes = listOf("Anime", "AnimeMovie", "OVA")
    iconUrl = "https://www.google.com/s2/favicons?domain=miruro.tv&sz=%size%"
}
