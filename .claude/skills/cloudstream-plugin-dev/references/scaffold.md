# Scaffolding a plugin module

Every plugin in this repo is a top-level folder named after the provider. `settings.gradle.kts`
includes any folder that has a `build.gradle.kts`, so creating the folder registers the module.
Match the existing modules (Hentaverse, HStream and XPrimeHub are recent, clean examples) rather
than these templates if the two ever disagree.

```
<Name>/
  build.gradle.kts
  src/main/kotlin/com/nropvox/<Name>Plugin.kt   registers the MainAPI
  src/main/kotlin/com/nropvox/<Name>.kt         MainAPI: network + Cloudstream types
  src/main/kotlin/com/nropvox/<Name>Parser.kt   pure functions: URL/slug parsing, decoders, ranking
  src/main/kotlin/com/nropvox/<Name>Models.kt   Jackson data classes for JSON APIs (if any)
  src/test/kotlin/com/nropvox/<Name>ParserTest.kt
```

The Parser split exists because JVM tests can't touch anything that needs the app (see SKILL.md).
Put every decision that can be wrong into the Parser: URL shapes, quality from labels, playlist
parsing, regexes and decoders. Unit tests can then pin it down with real samples.

## build.gradle.kts

```kotlin
version = 1

cloudstream {
    authors = listOf("NropVox")
    language = "en"                     // "hi" shows an Indian flag in the picker, etc.
    description = "<What it serves and from where. Stream kinds/qualities, any caveats.>"
    status = 1                          // 0 down, 1 ok, 2 slow, 3 beta
    tvTypes = listOf("Movie", "TvSeries")   // NSFW providers: listOf("NSFW")
    iconUrl = "https://www.google.com/s2/favicons?domain=<host>&sz=%size%"
}

android {
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }
}

tasks.withType<org.jetbrains.kotlin.gradle.tasks.KotlinJvmCompile>().configureEach {
    compilerOptions.jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_11)
}

dependencies {
    testImplementation("junit:junit:4.13.2")
}
```

JVM 11 is needed for the current Cloudstream inline APIs (`newExtractorLink` etc.). When you
change an existing plugin, bump `version` so installed copies update.

## <Name>Plugin.kt

```kotlin
package com.nropvox

import com.lagradost.cloudstream3.plugins.BasePlugin
import com.lagradost.cloudstream3.plugins.CloudstreamPlugin

@CloudstreamPlugin
class <Name>Plugin : BasePlugin() {
    override fun load() {
        registerMainAPI(<Name>())
    }
}
```

If the plugin needs its own extractor for a host, add `registerExtractorAPI(<Host>Extractor())`.
Check first whether the `Extractors` module or Cloudstream's built-ins already handle the host.
If they do, call `loadExtractor(url, referer, subtitleCallback, callback)`.

## <Name>.kt skeleton

```kotlin
package com.nropvox

import com.lagradost.api.Log
// The repo uses explicit imports (see Hentaverse.kt); expand these once the file compiles.
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*

/** <One line: what the site is built on and where the data really comes from (API host, player).> */
class <Name> : MainAPI() {
    override var mainUrl = "https://<host>"
    override var name = "<Name>"
    override var lang = "en"
    override val hasMainPage = true
    override val hasQuickSearch = false
    override val supportedTypes = setOf(TvType.Movie, TvType.TvSeries)

    override val mainPage = mainPageOf(
        "<path or api query>" to "<Row title>",
    )

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val items: List<SearchResponse> = TODO()
        return newHomePageResponse(HomePageList(request.name, items), hasNext = TODO())
    }

    override suspend fun search(query: String, page: Int): SearchResponseList {
        val items: List<SearchResponse> = TODO()
        return newSearchResponseList(items, hasNext = TODO())
    }

    override suspend fun load(url: String): LoadResponse {
        // Movie: newMovieLoadResponse(title, url, TvType.Movie, dataUrl) { posterUrl = …; plot = …; year = … }
        // Series: newTvSeriesLoadResponse(title, url, TvType.TvSeries, episodes) { … }
        //   episodes = list.map { newEpisode(<full URL or JSON>) { name = …; season = …; episode = … } }
        TODO()
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        // callback(newExtractorLink(name, "$name HLS", m3u8, ExtractorLinkType.M3U8) { quality = …; referer = … })
        // subtitleCallback(newSubtitleFile("English", vttUrl))
        TODO()
    }
}
```

Rules that came from real device failures:

- **Episode/movie `data` must be a full URL or JSON, never a bare slug.** The app makes
  relative data absolute against `mainUrl` before `loadLinks` (`slug` arrives as
  `https://host/slug`). Store the page URL and parse the id back out in `loadLinks`.
- **Escape literal `}` and `]` in every regex** (`\{…\}`, `\[[^\]]*\]`). Android compiles regexes
  with ICU, which rejects what the JVM accepts. A bad regex in an `object` fails in `<clinit>`:
  `NoClassDefFoundError … <clinit> failed`, with the real `PatternSyntaxException` further up the log.
- **Cloudflare-challenged hosts**: keep one `CloudflareKiller` (`by lazy`), let the first request
  solve the challenge alone behind a `Mutex`, then cap parallel requests to that host with
  `Semaphore(2)`. Five or more parallel requests deadlock OkHttp's per-host limit inside the interceptor.
- **WordPress behind Cloudflare**: `/wp-json/...` may be edge-cached by path only, so every query
  returns the same body. Use `/index.php?rest_route=/wp/v2/...` instead.
- Return early with `emptyList()` / `false` on blank input, and throw `ErrorLoadingException("…")`
  with a useful message from `load` when the page can't be parsed. That message shows on the result page.
- Log with `Log.i/w(name, …)`. The test phone drops `Log.d`.

## <Name>ParserTest.kt

```kotlin
package com.nropvox

import org.junit.Assert.assertEquals
import org.junit.Test

/** Values taken from responses saved on <date> (recon folder samples/). */
class <Name>ParserTest {
    @Test fun pageUrlsGiveSlug() {
        assertEquals("some-slug", <Name>Parser.slugOf("https://<host>/watch/some-slug/?t=3"))
    }
}
```

Feed the tests real strings from the recon folder's `samples/`, such as a JSON body, an m3u8 or an
HTML fragment, inlined or read from `src/test/resources/`. Run them with `csdev.py test <Name>`.
