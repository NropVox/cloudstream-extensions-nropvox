# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## What this repo is

Cloudstream 3 extensions (published under the `nropvox` repo shortcode). Each top-level folder with a `build.gradle.kts` is one plugin module. `settings.gradle.kts` includes every such folder automatically, so creating a folder registers it. To build only one module, comment out the auto-include and add `include("Name")`. All plugins use the package `com.nropvox`. Plugins load in separate class loaders, so the same class name in several plugins doesn't clash.

## Commands

Run from the repo root. `local.properties` (gitignored) must point at the Android SDK. Copy it into any new git worktree.

```bash
./gradlew :Name:make                  # build Name/build/Name.cs3
./gradlew :Name:testDebugUnitTest     # JVM parser tests for one plugin
./gradlew :Name:testDebugUnitTest --tests 'com.nropvox.NameParserTest.someTest'   # single test
./gradlew :Name:deployWithAdb         # push + hot-reload on the adb-connected phone
./gradlew makePluginsJson             # builds all .cs3 + build/plugins.json
python3 .github/scripts/check_version_bumps.py origin/main   # plugins changed since origin/main without a version bump
```

CI ([.github/workflows/build.yml](.github/workflows/build.yml)) runs on every push to `main`. It runs the version-bump check against the last published commit, then `testDebugUnitTest makePluginsJson`, and force-pushes the `.cs3` files and `plugins.json` to the `builds` branch. Pushing to `main` therefore publishes to users. A missing bump or a failing parser test stops the whole publish. Put `[no-bump]` in the commit message for metadata-only changes (`status`, `description`), which reach users through `plugins.json` without a bump.

For on-device work (deploy, driving the app, reading logcat), use the project skill `.claude/skills/cloudstream-plugin-dev`. Its `scripts/csdev.py` wraps the gradle tasks above and adb. It also uses a phone lease, so several agents don't drive the device at once. Don't run raw adb in place of it.

## Architecture

Standard module layout (see `.claude/skills/cloudstream-plugin-dev/references/scaffold.md` for templates and the device-derived rules):

- `<Name>Plugin.kt`: `@CloudstreamPlugin` entry. Registers the `MainAPI` and any `ExtractorApi`s. Extends `Plugin` (with `Context`) when it needs SharedPreferences or a settings dialog (e.g. Kayoanime), otherwise `BasePlugin`.
- `<Name>.kt`: the `MainAPI` (network calls and Cloudstream types: `getMainPage`, `search`, `load`, `loadLinks`).
- `<Name>Parser.kt`: pure functions (URL/slug shapes, quality labels, regexes, decoders, playlist/JSON parsing). This is the only layer the JVM tests can reach.
- `<Name>Models.kt`: Jackson data classes.
- `src/test/.../<Name>ParserTest.kt`: JUnit 4 tests built from real site samples. Recon folders live next to this repo at `/mnt/AJ/Projects/<host>-recon/` (`<host>-api.md` + `samples/`).

Code shared between plugins:

- **`Extractors` module**: a separate installed plugin that registers many host `ExtractorApi`s (DoodStream, VidHide variants, F4, HLSProxy, SubtitleCat, …) and the JAV page resolvers in `JavPages.kt`. Providers reach them at runtime through `loadExtractor(url, …)`. Javtiful, SexTB, MissAV and JavStream need Extractors installed to work.
- **`shared/<name>/` source folders**: compiled into each plugin that uses them, so there's no runtime dependency. Example: `kotlin.srcDir("../shared/nexdrive")` in Vegamovies and XPrimeHub. Put host chains that only some providers use here. Put globally useful URL-routable extractors in Extractors. Changing `shared/` affects every plugin that compiles it in.
- **`shared/<site>source/` folders and OmniStream**: the link code of the TMDB- and AniList-keyed providers (`dulosource`, `movysource`, `sevenmoviessource`, `tonkacinesource`, `cnotosource`, `katdramasource`, `animotvsource`, `anipmsource`) lives in shared folders. Each one is compiled into its own plugin and into **OmniStream**, the TMDB-catalog aggregator that races all of them in parallel (`OmniStreamRace.kt`). A fix to one of these folders needs a version bump in both the owning plugin and OmniStream. OmniStream compiles every folder into one class loader, so top-level class names must be unique across them (prefix them with the site name).

The root `build.gradle.kts` sets compileSdk/minSdk, Java/JVM 11 (needed for Cloudstream's inline JSON/link APIs) and the shared dependencies. Don't bump Jackson above 2.13.1 or Rhino above 1.8.1. Per-module `build.gradle.kts` holds `version` and the `cloudstream { }` metadata. **Bump `version` whenever you change a plugin**, so installed copies update. `tvTypes` decides which picker chip shows the provider (`"NSFW"` for adult providers).

## Constraints that aren't visible from the code

- JVM tests can't call `MainAPI` methods or emit links. The Cloudstream jar is compile-only, `newExtractorLink` needs the app's crypto library and `CloudflareKiller` needs Android's `CookieManager`. Link emission is verified on the phone only.
- Android compiles regexes with ICU: escape literal `}` and `]`. An unescaped one passes JVM tests but crashes in `<clinit>` on the device.
- Episode/movie `data` must be a full URL or JSON. The app makes a bare slug absolute against `mainUrl`.
- With `CloudflareKiller`, let the first request solve the challenge alone (Mutex), then cap parallel requests to that host with `Semaphore(2)`. Five or more in parallel deadlock OkHttp.
- The test phone drops `Log.d`. Use `Log.i`/`Log.w` for tracing.
