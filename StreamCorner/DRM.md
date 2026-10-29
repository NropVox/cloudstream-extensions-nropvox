# StreamCorner playback investigation

Observed on 2026-10-29 using the operator-supplied `streamcorner.st-recon` directory, current embed scripts, the cached Cloudstream player, and offline decoder/media probes.

The embeds supply per-stream ClearKey material. There is no universal licence key to put in the provider. Widevine/PlayReady signalling in a manifest does not describe the embed's complete playback configuration: its modified players also accept ClearKey.

## Configuration formats

* Legacy `pandecocogaming` embeds: Base32 decode `p`, subtract 75 modulo 256 from each byte, then XOR with the repeating UTF-8 bytes of `0000@streamcorner@0000`. The resulting JSON contains `stream_url` and `stream_keys`, with the latter holding a `kid32hex:key32hex` pair (or an empty string for clear media). This string is the envelope passphrase, not a channel content key.
* Current `getsugatensho` embeds: the page's import-free WASM decodes `p` to `{n,u,c}`. Each entry in `c` has a 16-byte KID and a 44-byte wrapped key. The modified `assets4/scplayer.js` contains another import-free WASM module that unwraps the key to 16 bytes. Changing either the captured KID or wrapped key made the module reject the input.

`StreamCornerDrm` statically extracts the live modules without evaluating the original page scripts. `StreamCornerBridge.decode` executes them in a separate WebView with network requests blocked. Export names are discovered from each module. The observed calling conventions, memory addresses and operation selectors are recorded in `StreamCornerDrm`; a future ABI change can require a provider update. Modules are cached for ten minutes and invalidated after a decode error. Content keys are resolved from each fresh stream configuration and are not stored in the source.

The test fixtures retain the original lookup arrays, encoded carriers, decoder functions and call sites. They contain no stream tokens or content keys. Recovered modules match these SHA-256 digests:

| Module | Bytes | SHA-256 |
| --- | ---: | --- |
| Page configuration | 126088 | `2fd0a2e398dae0d88e3896f1b9573d64adf7de1f1acbf73cabc42fac5914d4be` |
| Player key unwrap | 90128 | `a35aeebd9d575944f194f6d6de02a85a24ee391ba65309dde261f1462a624cfd` |

## Cloudstream compatibility

The cached Cloudstream player constructs `DashMediaSource` for `DrmExtractorLink`, even when the link URL is HLS. Its ClearKey callback accepts one KID/key pair as unpadded Base64url.

Native Amazon DASH URLs use that callback directly. For the observed NBA VOD fMP4 HLS layout, `StreamCornerDash` describes the same encrypted fragments using DASH SegmentLists. It selects the highest-bandwidth video and corresponding audio group, verifies playlist KIDs, reads initialization timescales, and preserves discontinuities as separate DASH Periods. The MPD travels with the link in `extractorData` and is served by the provider's video interceptor; segment requests still reach their original CDN URLs.

The full captured NBA replay contained 13 periods and 2,090 segments per track. The generated MPD contained 4,180 segment references. A short local MPD decrypted 20 video frames and stereo audio with the recovered key. A separate native Amazon DASH proof decrypted 45 video frames at 1024×576 and stereo audio, with no decode errors. The generated decoder HTML also returned the expected stream configuration in Chromium with no page errors or external requests.

Common PSSH is accepted for ClearKey by [Media3's DRM session manager](https://github.com/androidx/media/blob/release/libraries/exoplayer/src/main/java/androidx/media3/exoplayer/drm/DefaultDrmSessionManager.java). The [DASH parser](https://github.com/androidx/media/blob/release/libraries/exoplayer_dash/src/main/java/androidx/media3/exoplayer/dash/manifest/DashManifestParser.java) also derives Common PSSH from `cenc:default_KID`, as present in the native Amazon manifests.

## Boundaries

The NBA adapter handles the observed timestamped VOD fMP4 layout. Live DRM HLS, unknown HLS layouts, byte ranges, gaps, rotating keys and streams requiring multiple keys are left unresolved. Legacy configurations containing only `api_url` still use the existing embed fallback. Plain HLS retains its existing playback path. Manifest KIDs and PSSH are never treated as content keys.

REA's connection closed during this investigation; the new findings above come from local static extraction and isolated decoder/media execution, not a successful new REA Evidence record. The earlier recon's statement that only plain HLS is usable is superseded for the formats described here.

## Validation

`./gradlew :StreamCorner:testDebugUnitTest :StreamCorner:make --offline` passed with 27 tests. Version 3 builds to `StreamCorner/build/StreamCorner.cs3`.

The existing NBA failure was reproduced on the test phone before the change. Updated plugin deployment and Cloudstream playback could not be verified because another plugin task held the shared device lease. The offline browser and media results above do not establish on-device playback.
