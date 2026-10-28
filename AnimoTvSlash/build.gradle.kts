version = 1

cloudstream {
    authors = listOf("NropVox")
    language = "en"
    description = "Anime from animotvslash.org. Sub, SoftSub (ASS subtitle tracks) and Dub; first-party HLS (360p-1080p) and MP4 servers plus third-party mirrors."

    /**
     * Status int as the following:
     * 0: Down
     * 1: Ok
     * 2: Slow
     * 3: Beta only
     **/
    status = 1 // will be 3 if unspecified
    tvTypes = listOf("Anime", "AnimeMovie", "OVA")
    iconUrl = "https://www.google.com/s2/favicons?domain=animotvslash.org&sz=%size%"
}
