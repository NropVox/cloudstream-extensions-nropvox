version = 2

cloudstream {
    authors = listOf("NropVox")
    language = "en"
    description = "Bollywood, Hollywood, South Indian, anime and web series from Vegamovies. Streams V-Cloud, HubCloud and V-Drive mirrors; G-Direct and 10Gbps as download-only links."
    status = 1
    tvTypes = listOf("Movie", "TvSeries", "Anime")
    iconUrl = "https://www.google.com/s2/favicons?domain=vegamovies.lease&sz=%size%"
}

// The nexdrive host resolvers are shared with XPrimeHub.
android.sourceSets.getByName("main").kotlin.srcDir("../shared/nexdrive")
