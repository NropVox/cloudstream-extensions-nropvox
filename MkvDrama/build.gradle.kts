version = 5

cloudstream {
    authors = listOf("NropVox")
    language = "en"
    description = "Asian dramas and movies from MKVDrama. Standalone download-panel and container resolution; some mirrors require browser verification."
    status = 3
    tvTypes = listOf("AsianDrama", "Movie")
    iconUrl = "https://mkvdrama.net/static/assets/favicon/favicon.svg"

    requiresResources = true
}

dependencies {
    implementation("com.google.android.material:material:1.13.0")
}
