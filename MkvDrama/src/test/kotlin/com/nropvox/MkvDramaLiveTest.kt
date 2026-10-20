package com.nropvox

import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test

/** Explicit opt-in; ordinary builds never contact the live site. Does not request media. */
class MkvDramaLiveTest {
    @Test fun catalogMetadataAndMoviePanel() = runBlocking {
        assumeTrue(System.getenv("MKVDRAMA_LIVE_TEST") == "1")
        val base = "https://mkvdrama.net"
        val session = MkvDramaSession(browserVerification = false)
        val catalog = session.get("$base/titles/?type=drama&order=latest&page=1", acceptJson = true).json()
        assertTrue(catalog.path("results").isArray)
        assertTrue(catalog.path("results").size() > 0)
        val metadata = session.get("$base/bad-genius", acceptJson = true).json().path("series")
        assertEquals("movie", metadata.path("type").asText())
        val links = MkvDramaDecoder.releases(session.panel("$base/bad-genius"), "$base/bad-genius")
        assertTrue(links.isNotEmpty())
        val destinations = links.mapNotNull { session.request(it.url, "$base/bad-genius").location }
        assertTrue(destinations.any { it.startsWith("https://pixeldrain.com/u/") })
    }
}
