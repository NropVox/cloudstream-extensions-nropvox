version = 12

cloudstream {
    authors = listOf("NropVox")
    language = "en"
    description =
        "Japanese Adult Video from Vietnam Server"

    /**
     * Status int as the following:
     * 0: Down
     * 1: Ok
     * 2: Slow
     * 3: Beta only
     **/
    status = 1 // will be 3 if unspecified
    tvTypes = listOf("NSFW")
    iconUrl = "https://javhdz.men/favicon.ico"

    requiresResources = true
    isCrossPlatform = false
}

dependencies {
    implementation("com.google.android.material:material:1.13.0")
}

// Cloudstream's inline persistence and JSON APIs now use Java 11 bytecode.