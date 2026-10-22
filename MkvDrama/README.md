# MKVDrama

Standalone Android Cloudstream provider for `https://mkvdrama.net`, based on the
adjacent `mkvdrama-bypass` project. No bypass server or FlareSolverr is required
by the extension. The provider is marked **beta** because shorteners and file
hosts can require browser verification that has not been tested on a device.

- Catalogs use JSON; search uses the site's HTML cards.
- Details use JSON metadata and the encrypted download panel. Only published
  episode ranges become episodes; the advertised total is not used to invent links.
- The extension reproduces the cookie-bound seed/gate/pass exchange and
  authenticates AES-GCM payloads.
- Playback follows a single path, as the bypass client's single-path export does.
  Qualities are tried best first (1080pHD, then 2160p, 1080p, 720p, …). Within a
  quality, all ouo mirrors are landed together and the best container wins
  (native, then ViewCrate, then Filecrypt). Direct containers, other shorteners
  and file hosts follow. The next path is tried only when one fails.
- The chosen container's URL and every row it lists are cached per series
  (`CloudStreamApp` keys, seven days). Later episodes play from those rows without
  the panel, `/_c/` redirects or shorteners. A cached container is re-read when an
  episode is missing or its links fail, for example an ongoing batch that has grown.
- Native MKVDrama containers and ViewCrate rendered cards/bootstrap metadata
  resolve to individual files. When ViewCrate or Filecrypt offers a DLC, it is
  decoded locally; only its key token goes to JDownloader's key service. Every
  file-host link is then cached at once. Without a DLC, row redirects are resolved
  only for the host being played, and the result is cached.
- Each episode plays from one host: Pixeldrain, else Gofile, else another, with
  Filecrypt rows marked offline last. The next host is tried only if one fails.
  Batch files and unknown episode assignments are excluded from regular episode
  playback.
- Pixeldrain file URLs become direct video links after a metadata check. Other
  supported file hosts are handed to Cloudstream extractors. This does not
  establish that every host has a working extractor.
- Filecrypt supports already accessible file rows, same-host Link/Go navigation,
  DLC exports and movie CNL forms. Its proof-of-work/password gates are not
  implemented.
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
