version = 1

cloudstream {
    authors = listOf("NropVox")
    language = "en"
    description =
        "Movies, TV shows, anime and Asian dramas from TMDB. Every title is looked up on Dulo, Movy, 7Movies, TonkaCine and Cno.to at once (plus KatDrama for Asian titles, and AnimoTv and Ani.pm for anime through AniList); dead links are dropped and playback starts as soon as enough working ones are in."

    /**
     * Status int as the following:
     * 0: Down
     * 1: Ok
     * 2: Slow
     * 3: Beta only
     **/
    status = 3 // will be 3 if unspecified
    tvTypes = listOf("Movie", "TvSeries", "Anime", "AnimeMovie", "AsianDrama")
    iconUrl = "https://www.google.com/s2/favicons?domain=themoviedb.org&sz=%size%"

    isCrossPlatform = true
}

// The stream resolvers of the TMDB- and AniList-keyed plugins, compiled in so no other plugin has to be installed.
listOf("dulosource", "movysource", "sevenmoviessource", "tonkacinesource", "cnotosource", "katdramasource", "animotvsource", "anipmsource")
    .forEach { android.sourceSets.getByName("main").kotlin.srcDir("../shared/$it") }
