# MKVDrama

Standalone Android Cloudstream provider for `https://mkvdrama.net`, based on the
adjacent `mkvdrama-bypass` project. No bypass server or FlareSolverr is required
by the extension. The provider is marked **beta** because shorteners and file
hosts can require browser verification that has not been tested on a device.

- Catalogs use JSON; search uses the site's HTML cards.
- Details use JSON metadata and the encrypted download panel. Only published
  episode ranges become episodes; the advertised total is not used to invent links.
- The extension reproduces the cookie-bound seed/gate/pass exchange, authenticates
  AES-GCM payloads, and preserves every source, quality, and mirror.
- Native MKVDrama containers and ViewCrate rendered cards/bootstrap metadata
  resolve to individual files. Batch files and unknown episode assignments are
  excluded from regular episode playback.
- Pixeldrain file URLs become direct video links after a metadata check. Other
  supported file hosts are handed to Cloudstream extractors. This does not
  establish that every host has a working extractor.
- Filecrypt supports already accessible file rows, same-host Link/Go navigation,
  and movie CNL forms. Its proof-of-work/password gates are not implemented.
- Shortener forms are followed when accessible, with up to two on-device WebView
  fallback attempts per extraction. Interactive CAPTCHA completion, exe.io,
  Filecrypt verification, Gofile authentication, and other host-specific browser
  flows remain incomplete or depend on Cloudstream's extractors.

Build an installable package:

```sh
./gradlew :MkvDrama:make
# MkvDrama/build/MkvDrama.cs3
```

See [postman/README.md](postman/README.md) for reproducible fixture tests.
An optional live Kotlin smoke check (catalog, movie metadata, encrypted panel,
and site redirects; no media) is enabled with `MKVDRAMA_LIVE_TEST=1`.

Recon evidence and the full numbered API reference from this implementation are
in `/tmp/mkvdrama.net-recon/mkvdrama.net-api.md`. That folder is temporary and
contains private session responses; retain it separately if needed.
