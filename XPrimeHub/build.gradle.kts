version = 2

cloudstream {
    authors = listOf("NropVox")
    language = "en"
    description = "Indian OTT web series (ULLU, NeonX, MoodX, Atrangii…) and adult films from XPrimeHub. Streams V-Cloud, HubCloud and V-Drive mirrors; G-Direct and 10Gbps as download-only links."
    status = 1
    tvTypes = listOf("NSFW")
    iconUrl = "https://www.google.com/s2/favicons?domain=xprimehub.pics&sz=%size%"
}

// The nexdrive host resolvers are shared with Vegamovies.
android.sourceSets.getByName("main").kotlin.srcDir("../shared/nexdrive")
