# Cloudstream UI map (prerelease.debug 4.8.0-PRE, verified on the TECNO 2026-10-23)

Read this when a `csdev.py` screen command fails and you have to drive the app by hand
with `ui` / `tap` / `swipe`. View ids are stable across portrait and landscape; coordinates are not.

## How to find things

- `ui` prints `idx flags (x,y) 'text' desc=… #resource-id`. Flags: `C` clickable, `F` focused, `✓` checked.
- `tap <q>` matches exact text/desc/id first, then substring, so `tap result_play_movie` and
  `tap "Episode 3"` both work. Use `--nth` when a substring hits several nodes (it says so).
- A tap is refused when the node belongs to another app (Google sign-in prompts, permission
  dialogs, the launcher). Take a `shot`, tell the user what is on screen and let them deal with it.
  Account, security and payment prompts on the user's phone are theirs to answer, never yours.

## Screens

| Screen | Ids worth knowing |
|---|---|
| First-run "Extensions" (after every cold start) | `next_btt` ("Done"). `app`/`restart` dismiss it |
| Home | `home_api_fab` (selected provider chip; its text is the provider name), `search_button` (provider-scoped search, top-left), `home_child_more_info` (row title), `imageText` (card title), `background_card`, `home_preview_play`/`home_preview_info`, `home_loading_error`, `home_reload_connectionerror` |
| Provider picker (bottom sheet from `home_api_fab`) | rows are `text1` with a flag prefix (`🇬🇧 Hentaverse`, `🇮🇳 XPrimeHub`); type chips `home_select_movies`, `home_select_tv_series`, `home_select_anime`, `home_select_asian`, `home_select_nsfw` (✓ = active). Only providers whose `tvTypes` match an active chip are listed |
| Nav | `navigation_home`, `navigation_search` (global search), `navigation_library`, `navigation_downloads`, `navigation_settings`. Bottom bar in portrait, left rail in landscape |
| Search results | `search_src_text` (field), `search_result_root` / `imageText` (results), `quick_search_back` |
| Result page (`load()`) | `result_title`, `result_meta_site`/`_type`/`_year`, `result_description`, `result_play_movie` (movies), `result_episodes_text` ("12 Episodes"), `result_season_button`, `result_sort_button`, `episode_holder_large` → `episode_text` ("1. Title"), `episode_runtime`; errors: `result_error_text` (URL + exception), `result_reload_connectionerror` (Retry). It also contains a trailer `player_view`, so don't treat `player_view` alone as "player open" |
| Player (`loadLinks`) | `player_view`; controls (hidden after ~2 s): `player_sources_btt` ("Source"), `player_tracks_btt`, `player_subtitle_offset_btt`, `player_video_title_rez` ("Hentaverse HLS - 1920x1080" = link name + resolution), `player_video_title`, `player_go_back`, `player_episodes_button`; while resolving: `overlay_loading_skip_button` ("Skip Loading (N)", N = links so far; skipping cancels running resolvers) |
| Source dialog | left column `text1` = every emitted ExtractorLink as "name quality"; right column `text1` = subtitles; `apply_btt`, `cancel_btt` |

## Quirks

- **Landscape after the player.** The app often stays landscape (1920x1080) with a nav rail
  after playback, even at display rotation 0. Ids still work; hard-coded portrait coordinates
  don't. `cmd window set-ignore-orientation-request true` forces portrait but squashes the
  player. If you use it, restore it with `false` and `settings put system accelerometer_rotation 1`.
- **Player controls auto-hide** faster than a dump/tap round trip, so `sources` shows them and
  taps in a single device shell call. Do the same by hand: `adb shell 'input tap 960 270; sleep 0.4; input tap X Y'`.
- **Movie result pages**: in landscape `result_play_movie` sits below the cast row, so it is absent from `ui` until you swipe up (`swipe 960 800 960 200`). `play` with no number only works once it is visible.
- **Back from a result page** returns to the search results (if you came from `psearch`), not to the previous player/result. Re-`tap` the card to reopen the title.
- **Lists are virtualised.** A dump only sees visible rows (home rows, episode list, source list),
  so swipe and dump again. `rows`, `play N` and `sources` already do this.
- **The home preview header** is tall in landscape, so it takes several swipes to reach the rows.
- **uiautomator can get stuck**: you see "UiAutomationService … already registered", a 0-byte
  ui.xml, or a respawning `uiautomator`. `dump` retries and then tells you. Fall back to
  `shot` + Read + `tapxy`, or `adb shell pkill -f uiautomator`.
- **Screen asleep.** If a `shot` comes back black, run `app`: it wakes the screen and dismisses the keyguard.
- **NSFW providers** need NSFW in Settings → search "media" → Preferred media, plus the NSFW
  chip in the picker (scroll the chip row left if it is cut off). If you enabled them for a test,
  switch them back off and re-pick the previously selected provider afterwards.
- **Deep links** (all sent with `-p <package>`; MainActivity is not exported, AccountSelectActivity2 forwards):
  - `cloudstreamapp:` hot-reloads every local plugin in-process (what gradle `deployWithAdb` uses). `deploy` relies on it.
  - `cloudstreamsearch://<query>` opens the *global* search tab: all providers, grouped, filtered by the type chips.
  - `csshare:<b64 apiName>?<b64 url>` should open a result page, but on this build nothing happened (tested 2026-10-23), so don't rely on it.
- Several Cloudstream builds are installed (`cloudstream3`, `.prerelease`, `.debug`, `.prerelease.debug`).
  Plugins are tested in `com.lagradost.cloudstream3.prerelease.debug`. Without `-p`, a deep link
  opens a chooser.
- Local plugins live in `/sdcard/Cloudstream3/plugins/`. The app copies them into
  `/sdcard/Android/data/<pkg>/files/plugins/` when it loads them.
