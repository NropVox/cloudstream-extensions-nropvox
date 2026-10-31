version = 3

cloudstream {
    authors = listOf("NropVox")
    language = "en"
    description = "4K and 1080p WEB-DL/BluRay movies, series and anime from 4KHDHub, many with Hindi audio. Streams HubCloud FSL mirrors; 10Gbps as download-only links."
    status = 1
    tvTypes = listOf("Movie", "TvSeries", "Anime")
    iconUrl = "https://www.google.com/s2/favicons?domain=4khdhub.one&sz=%size%"
}

// The HubCloud resolver is shared with Vegamovies and XPrimeHub.
android.sourceSets.getByName("main").kotlin.srcDir("../shared/nexdrive")

// The 4KHDHub search and redirector chain is shared with OmniStream.
android.sourceSets.getByName("main").kotlin.srcDir("../shared/fourkhdhubsource")
