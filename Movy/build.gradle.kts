version = 2

cloudstream {
    authors = listOf("NropVox")
    language = "en"
    description =
        "Movies and TV shows from movy.sx. Decrypts movy's stream resolvers and lists every working server."

    /**
     * Status int as the following:
     * 0: Down
     * 1: Ok
     * 2: Slow
     * 3: Beta only
     **/
    status = 1 // will be 3 if unspecified
    tvTypes = listOf("Movie", "TvSeries")
    iconUrl = "https://www.google.com/s2/favicons?domain=movy.sx&sz=%size%"

    isCrossPlatform = true
}

// Cloudstream's inline JSON and key APIs use Java 11 bytecode.