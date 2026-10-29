version = 1

cloudstream {
    authors = listOf("NropVox")
    language = "en"
    description =
        "Asian dramas, movies and anime from kisskh.ovh (its JSON API). Plays the site's own HLS, with subtitles in up to 9 languages (English, Indonesian, Malay, Arabic, Khmer, Tagalog…)."

    /**
     * Status int as the following:
     * 0: Down
     * 1: Ok
     * 2: Slow
     * 3: Beta only
     **/
    status = 1 // will be 3 if unspecified
    tvTypes = listOf("AsianDrama", "Movie", "Anime", "TvSeries")
    iconUrl = "https://www.google.com/s2/favicons?domain=kisskh.ovh&sz=%size%"
}
