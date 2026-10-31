version = 6

cloudstream {
    authors = listOf("NropVox")
    language = "en"
    description =
        "Movies, TV shows, anime and Asian dramas from TMDB. Every title is looked up on Dulo, Movy, 7Movies, TonkaCine, Cno.to, Vegamovies, MoviesMod, 4KHDHub and MkvBase at once (plus KatDrama, KissKH and GoPlay for Asian titles, and AnimoTv, Ani.pm, Reanime and AnimeX for anime through AniList), with Vadapav files and OpenSubtitles subtitles by IMDb id; dead links are dropped and playback starts as soon as enough working ones are in. Also adds OmniAnime, an anime-only AniList catalog that asks the anime sites directly and the TMDB-keyed sources through an ani.zip mapping."

    /**
     * Status int as the following:
     * 0: Down
     * 1: Ok
     * 2: Slow
     * 3: Beta only
     **/
    status = 3 // will be 3 if unspecified
    tvTypes = listOf("Movie", "TvSeries", "Anime", "AnimeMovie", "OVA", "AsianDrama")
    iconUrl = "https://www.google.com/s2/favicons?domain=themoviedb.org&sz=%size%"

    isCrossPlatform = true
}

// The stream resolvers of the TMDB- and AniList-keyed plugins and of the title-searched sites (with the nexdrive host
// chain two of them use), compiled in so no other plugin has to be installed.
listOf(
    "dulosource", "movysource", "sevenmoviessource", "tonkacinesource", "cnotosource", "katdramasource", "animotvsource", "anipmsource",
    "nexdrive", "vegamoviessource", "moviesmodsource", "fourkhdhubsource", "kisskhsource", "goplaysource", "reanimesource", "animexsource",
)
    .forEach { android.sourceSets.getByName("main").kotlin.srcDir("../shared/$it") }
