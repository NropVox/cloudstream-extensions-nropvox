package com.nropvox

import org.jsoup.Jsoup
import org.junit.Assert.assertEquals
import org.junit.Test

class JavFC2ParserTest {
    @Test
    fun parsesModernCardsWithAnOuterLinkAndLazyPoster() {
        val document = Jsoup.parse(
            """
            <div class="modern-movie-grid movie-container">
              <article class="modern-movie-card latest-movie-img-container">
                <a class="modern-movie-card__link" href="/watch/first.html">
                  <div class="modern-movie-card__poster movie-img">
                    <img class="modern-movie-card__image" src="/blank.jpg" data-src="/first.jpg">
                    <span class="modern-movie-card__badge video_quality">HD</span>
                    <div class="modern-movie-card__body movie-title">
                      <h3 class="modern-movie-card__title">First title</h3>
                    </div>
                  </div>
                </a>
              </article>
            </div>
            """.trimIndent()
        )

        assertEquals(
            listOf(JavFC2Parser.Card("First title", "/watch/first.html", "/first.jpg")),
            JavFC2Parser.cards(document)
        )
    }

    @Test
    fun retainsLegacyVideosAndSkipsPeople() {
        val document = Jsoup.parse(
            """
            <div class="movie-container">
              <div>
                <div class="movie-img"><img src="/video.jpg"></div>
                <div class="movie-title"><h3><a href="/watch/old.html">Old title</a></h3></div>
              </div>
              <div>
                <span class="label">Actor</span>
                <div class="movie-title"><h3><a href="/actor/name">Name</a></h3></div>
              </div>
            </div>
            """.trimIndent()
        )

        assertEquals(
            listOf(JavFC2Parser.Card("Old title", "/watch/old.html", "/video.jpg")),
            JavFC2Parser.cards(document)
        )
    }

    @Test
    fun parsesModernWatchMetadataAndEscapedHlsUrl() {
        val document = Jsoup.parse(
            """
            <div id="player-div">
              <script>
                createOvooPlayer("play", {
                  sources: [{ src: "https:\/\/stream.example\/playlist?key=123&n=2", type: 'application/x-mpegURL' }]
                });
              </script>
            </div>
            <div class="modern-watch-poster"><img src="/poster.jpg"></div>
            <h1 class="modern-watch-title">Watch title</h1>
            """.trimIndent()
        )

        assertEquals("Watch title", JavFC2Parser.watchTitle(document))
        assertEquals("/poster.jpg", JavFC2Parser.watchPoster(document))
        assertEquals("https://stream.example/playlist?key=123&n=2", JavFC2Parser.streamUrl(document))
    }

    @Test
    fun retainsLegacyWatchMetadataAndPlainHlsUrl() {
        val document = Jsoup.parse(
            """
            <div id="player-div"><script>sources: [{ src: 'https://stream.example/playlist' }]</script></div>
            <div id="info"><img src="/poster.jpg"></div>
            <span class="title">Legacy watch title</span>
            """.trimIndent()
        )

        assertEquals("Legacy watch title", JavFC2Parser.watchTitle(document))
        assertEquals("/poster.jpg", JavFC2Parser.watchPoster(document))
        assertEquals("https://stream.example/playlist", JavFC2Parser.streamUrl(document))
    }
}
