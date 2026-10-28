version = 1

cloudstream {
    authors = listOf("NropVox")
    language = "en"
    description =
        "Anime from ani.pm, subbed and dubbed. HLS up to 1080p with many subtitle languages."

    /**
     * Status int as the following:
     * 0: Down
     * 1: Ok
     * 2: Slow
     * 3: Beta only
     **/
    status = 1 // will be 3 if unspecified
    tvTypes = listOf("Anime", "AnimeMovie", "OVA")
    iconUrl = "https://www.google.com/s2/favicons?domain=ani.pm&sz=%size%"
}
