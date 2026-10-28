version = 1

cloudstream {
    authors = listOf("NropVox")
    language = "en"
    description = "Korean, Chinese, Japanese and Thai dramas and movies from OnlyKDrama, many dubbed in Hindi. Plays the site's HubCloud, XCloud, GDFlix and worker download links (MKV, 480p-2160p); TMBCloud and FilePress need a captcha and are skipped."
    status = 0
    tvTypes = listOf("AsianDrama", "Movie")
    iconUrl = "https://www.google.com/s2/favicons?domain=onlykdrama.shop&sz=%size%"
}

// The HubCloud and Pixeldrain resolvers are shared with Vegamovies, XPrimeHub and 4KHDHub.
android.sourceSets.getByName("main").kotlin.srcDir("../shared/nexdrive")
