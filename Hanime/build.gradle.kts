version = 2

cloudstream {
    authors = listOf("NropVox")
    language = "en"
    description = "Hentai from hanime.tv. HLS streams up to 720p, with Pixeldrain MP4 mirrors."

    /**
     * Status int as the following:
     * 0: Down
     * 1: Ok
     * 2: Slow
     * 3: Beta only
     **/
    status = 1 // will be 3 if unspecified
    tvTypes = listOf("NSFW")
    iconUrl = "https://www.google.com/s2/favicons?domain=hanime.tv&sz=%size%"

    isCrossPlatform = true
}
