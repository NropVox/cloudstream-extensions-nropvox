version = 1

cloudstream {
    authors = listOf("NropVox")
    language = "en"
    description = "K-, C-, J- and Thai dramas and Asian movies from k-drama.in (KatDrama), keyed by TMDB. HLS from the site's devcorp and KissKH servers with many subtitle languages, plus dood/mixdrop/streamtape mirrors."
    status = 1
    tvTypes = listOf("AsianDrama", "Movie")
    iconUrl = "https://www.google.com/s2/favicons?domain=k-drama.in&sz=%size%"
}

dependencies {
    testImplementation("junit:junit:4.13.2")
}
