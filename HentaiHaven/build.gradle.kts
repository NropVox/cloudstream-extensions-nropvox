version = 1

cloudstream {
    authors = listOf("NropVox")
    language = "en"
    description = "Hentai from hentaihaven.xxx. HLS streams up to 1080p (VP9), with multi-language subtitles on newer uploads."

    /**
     * Status int as the following:
     * 0: Down
     * 1: Ok
     * 2: Slow
     * 3: Beta only
     **/
    status = 1 // will be 3 if unspecified
    tvTypes = listOf("NSFW")
    iconUrl = "https://www.google.com/s2/favicons?domain=hentaihaven.xxx&sz=%size%"
}
