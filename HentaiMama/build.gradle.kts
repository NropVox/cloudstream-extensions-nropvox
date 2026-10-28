version = 1

cloudstream {
    authors = listOf("NropVox")
    language = "en"
    description = "Hentai from hentaimama.io. Direct MP4 (1080p/720p/480p) and HLS mirrors."

    /**
     * Status int as the following:
     * 0: Down
     * 1: Ok
     * 2: Slow
     * 3: Beta only
     **/
    status = 1 // will be 3 if unspecified
    tvTypes = listOf("NSFW")
    iconUrl = "https://www.google.com/s2/favicons?domain=hentaimama.io&sz=%size%"
}
