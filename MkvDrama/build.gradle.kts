version = 6

cloudstream {
    authors = listOf("NropVox")
    language = "en"
    description = "Asian dramas and movies from MKVDrama. Standalone download-panel and container resolution; some mirrors require browser verification."
    status = 3
    tvTypes = listOf("AsianDrama", "Movie")
    iconUrl = "https://raw.githubusercontent.com/NropVox/cloudstream-extensions-nropvox/main/docs/icons/mkvdrama.png"

    requiresResources = true
}

dependencies {
    implementation("com.google.android.material:material:1.13.0")
}
