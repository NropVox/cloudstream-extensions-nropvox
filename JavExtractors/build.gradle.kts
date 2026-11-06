version = 22

cloudstream {
    authors = listOf("NropVox")
    language = "en"
    description = "Host and JAV page extractors for the NSFW sources"

    /**
     * Status int as the following:
     * 0: Down
     * 1: Ok
     * 2: Slow
     * 3: Beta only
     **/
    status = 1 // will be 3 if unspecified
    tvTypes = listOf("Others")

    requiresResources = true
    isCrossPlatform = false
}

dependencies {
    implementation("com.google.android.material:material:1.13.0")
}

// The settings sheet (OmniStream's design) is shared: src/main/res is a symlink to shared/settingsui/res.
android.sourceSets.getByName("main").kotlin.srcDir("../shared/settingsui")
