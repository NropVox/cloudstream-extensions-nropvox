version = 4

cloudstream {
    authors = listOf("NropVox")
    language = "en"
    description = "Bollywood, Hollywood, South Indian, anime and web series from Vegamovies. Streams V-Cloud, HubCloud and V-Drive mirrors; G-Direct and 10Gbps as download-only links."
    status = 1
    tvTypes = listOf("Movie", "TvSeries", "Anime")
    iconUrl = "https://new1.vegamovies.lease/images/apple-touch-icon.png"
}

// The nexdrive host resolvers are shared with XPrimeHub.
android.sourceSets.getByName("main").kotlin.srcDir("../shared/nexdrive")

// The Vegamovies search and link chain is shared with OmniStream.
android.sourceSets.getByName("main").kotlin.srcDir("../shared/vegamoviessource")
