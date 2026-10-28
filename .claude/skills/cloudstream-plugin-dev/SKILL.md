---
name: cloudstream-plugin-dev
description: Create, build, deploy and debug Cloudstream plugins (extensions/providers) in this repo, end to end on the adb-connected Android phone. Covers recon of the target site (hands off to /site-recon), scaffolding a new plugin module, parser unit tests, building the .cs3, pushing and hot-reloading it in the app, driving the app over adb (provider picker, main page rows, search, result page, playing an episode, listing the emitted sources) and reading logcat to fix failures. Use whenever the user wants to add, write or port a provider/extension/plugin for a site, fix or update a broken one ("X shows no links", "search returns nothing", "plugin won't load", "crashes on the phone"), test a plugin on the phone/device, or debug with adb/logcat, even if they only name a site or a plugin folder and say "make it work".
---

# Cloudstream plugin development

You build Cloudstream plugins in this repo and prove they work on the user's phone. The phone
matters because JVM tests can't reach link emission (see "What JVM tests can't do" below), and
because Android behaves differently from the JVM: ICU regexes, the app rewriting episode data,
Cloudflare interceptors. A plugin is done only after the app has shown its rows, search results,
a result page and playing video, and you have seen them yourself.

Everything on the device goes through `scripts/csdev.py`, a helper bundled with this skill. It
already handles the tedious, fragile parts: device selection, waking the screen, the first-run
screen, hot reload, fresh uiautomator dumps, landscape/portrait, auto-hiding player controls and
log filtering. Use it rather than retyping adb pipelines; that way the time goes into the plugin.

## Setup (once per session)

zsh doesn't split `$VAR` into words, so define a function rather than a variable:

```bash
cs(){ python3 /mnt/AJ/Projects/cloudstream-extensions-istarvin/.claude/skills/cloudstream-plugin-dev/scripts/csdev.py "$@"; }
export CSDEV_SCRATCH=<your scratchpad dir>/csdev   # screenshots, ui dumps, log mark (per-owner subfolder)
export CSDEV_OWNER=<plugin>-<task>                 # unique to you, e.g. kayoanime-fix; same in every call
cs devices      # which phone, and whether the Cloudstream build is installed
cs lease status # is another agent on the phone right now?
```

Shell functions and exports don't persist between Bash calls, so repeat the `cs(){…}` and
`export` lines at the start of each call, with the **same** `CSDEV_OWNER` every time. Run from
the repo root (or your worktree root), because gradle tasks and the `<Plugin>/build/` paths are relative to it.
`cs --help` lists every command. The ones you'll use most:

| Command | What it proves |
|---|---|
| `cs lease acquire` / `release` / `status` | you own the phone; every device command refuses to run without it |
| `cs build P` / `cs test P` | compiles to `P/build/P.cs3` / parser unit tests pass |
| `cs deploy P` | builds, pushes and hot-reloads the plugin, then waits for `Loaded plugin P.cs3 successfully` and prints the stack trace if loading failed |
| `cs provider P` | P appears in the home picker and is selected |
| `cs rows` | `getMainPage`: each row title with its first card titles |
| `cs psearch "q"` | `search`: results from this provider only |
| `cs tap "<title>"` then `cs screen` | `load`: the result page title and episode count, or the error text the app shows |
| `cs play [N]` then `cs links` | `loadLinks`: which link the player picked, time to first frame, subtitles, errors |
| `cs sources` | every ExtractorLink and subtitle the plugin emitted, as listed in the player |
| `cs mark` / `cs log [--grep RE] [--plugin P]` | app logcat since the mark, with framework noise removed |
| `cs ui` / `cs tap q` / `cs shot` | raw driving when a screen command doesn't fit |

When a screen command fails or you need to drive the app by hand, read `references/ui-map.md`.
It has the view ids for each screen, the deep links and the quirks of this phone.

## Device etiquette

The phone is the user's own, signed in to their accounts. Treat it that way:

- Touch only Cloudstream. `tap` refuses nodes from other apps for this reason. If a Google
  sign-in prompt, permission dialog or anything unexpected covers the app, take a `shot`, tell the
  user what it is and wait for them. Account and security prompts are never yours to answer.
- Drive the phone only while you hold the lease (see "Several agents at once"). It has one
  screen, one player and one plugin loader, so another agent's taps or `deploy` would wreck your session.
- Leave the phone as you found it. If you enabled NSFW in Preferred media or the picker, changed
  the selected provider, forced orientation, or switched `Log.d` to `Log.i` for a debug build,
  undo it before you finish.

## Several agents at once

Recon, code, `cs build` and `cs test` run fine in parallel. The phone doesn't: `deploy` hot-reloads
**every** local plugin, and the screen, player, selected provider and logcat are all shared. So:

- **Own worktree per agent.** Work in a git worktree (the Agent tool's `isolation: "worktree"`, or
  `git worktree add ../cs-<task> -b <task>`), so edits and `build/` outputs don't collide. Copy the
  gitignored `local.properties` from the main checkout into the worktree root, or gradle can't find
  the SDK. Untracked plugin folders aren't in a new worktree either, so commit or copy them in first.
  Two agents shouldn't change the same plugin, or `shared/` code that more than one plugin compiles in.
- **Lease the phone for a whole device session**, not per command: `tap` → `screen` → `play` → `links`
  depend on each other. `cs lease acquire --note "<Plugin>: <what>"` waits up to 90 s (`--wait N`).
  If it says busy, go back to code or tests and try again later. Never work around it with raw `adb`.
- Every device command refreshes the lease. One idle for 10 min (`CSDEV_LEASE_TTL`) is stale and the
  next `acquire` takes it over, so don't hold it while you write a long fix. `cs lease release`, edit
  and rebuild, then `acquire` again. If someone took over a stale lease of yours, device commands say
  so. Acquire again, and since the phone state is no longer yours, re-`deploy` before trusting anything.
- Release only **after** restoring the phone (see "Finishing"): the next agent expects the user's settings.
- `cs lease release --force` clears another agent's lease. Use it only when the user tells you to.
- If you're the parent splitting work across subagents, give each one a different `CSDEV_OWNER`.

## Workflow: new plugin for a site

### 1. Know the site before writing code

Look for an existing recon folder first: `/mnt/AJ/Projects/<host>-recon/` (a sibling of this
repo, e.g. `new1.vegamovies.lease-recon`), containing `<host>-api.md` and `samples/`. If there
isn't one, invoke the **site-recon** skill with the site URL. It reverse engineers catalogs,
search, details, episodes and how a playable URL is reached, and saves real samples. Don't guess
endpoints from the homepage: a wrong endpoint costs a full build-deploy-debug cycle on the phone,
and recon is cheaper.

From the recon you need to know:
- which requests produce each home row, search results, title details and the episode list;
- the chain from an episode to a playable URL: own CDN, a known extractor host, or an embed that needs its own extractor;
- protection: a Cloudflare challenge (→ `CloudflareKiller`), required headers or referer, signed or expiring URLs;
- the `tvTypes` the site really carries, because they decide which picker chip shows the provider.

### 2. Scaffold the module

Follow `references/scaffold.md`. It has the folder layout, `build.gradle.kts`, Plugin/MainAPI
skeletons and the rules that came from past device failures. Keep pure logic in `<Name>Parser`
so it can be unit tested.

### 3. Parser tests

Write `<Name>ParserTest` against real samples from the recon folder, then run `cs test <Name>`.
Cover the parsing that would otherwise fail silently on the phone: URL/slug shapes, quality
labels, playlist and JSON parsing, regexes and decoders.

**What JVM tests can't do:** the Cloudstream jar is compile-only and not on the test runtime
classpath. Even when it is added, `newExtractorLink` / `loadExtractor` need the app's crypto
library and `CloudflareKiller` needs Android's `CookieManager`. So `MainAPI` methods can't be
called end to end in tests, and link emission is verified on the phone.

### 4. Deploy and verify on the phone

```bash
cs lease acquire --note "<Name>: verify"   # first: nothing below runs without it
cs deploy <Name>          # must print "loaded <Name>.cs3"
cs provider <Name>        # if missing from the picker: wrong tvTypes chip, NSFW off, or the load failed
cs rows                   # every mainPageOf row present, with plausible titles
cs psearch "<a title you know exists>"
cs tap "<result title>"; cs screen
cs play 1; sleep 10; cs links; cs sources
```

Check each method separately and note the result. Cover at least one movie and one series if
the site has both, and a title from more than one home row. A second page of a row
(`hasNext`) and season switching are worth one check each when the site has them.

The bar for "works" is a first frame in `links` plus a sensible `sources` list (names and
qualities you'd want to pick from). A link that is emitted but never plays doesn't count, because
users see it as a broken source.

### 5. Debug loop

`cs mark`, reproduce the failure, then `cs log` (and `cs links` for playback). Common failures and their usual causes:

| Symptom | Look for | Usual cause |
|---|---|---|
| `deploy` prints FAILED / no load line | stack trace in the deploy output | missing class or dependency, `<clinit>` crash, ICU regex (`PatternSyntaxException`) |
| Provider missing from the picker | `cs ui` after `tap home_api_fab`; chips ✓ | `tvTypes` not matching an active chip; NSFW off; plugin didn't load |
| Rows empty / home error | `cs log --grep "<Name>\|NiceHttp\|Exception"` | selector/JSON drift, CF challenge, `NoClassDefFoundError` from a bad static init |
| Search empty | same | query encoding, wrong endpoint, `hasNext` or paging param |
| Result page shows an error | `cs screen` prints `result_error_text` (URL + exception) | parse failure, or `load` got a URL shape it didn't expect (e.g. an episode URL from a row) |
| Player spins, "Skip Loading (0)" | `cs log`, `cs links` | `loadLinks` got absolute `https://host/slug` data, threw early, or extractors hung (CF deadlock) |
| Link emitted, `PLAYER ERROR` | the error line in `cs links` | missing referer/headers, wrong `ExtractorLinkType` (M3U8 vs VIDEO), expired or signed URL |
| Network timeouts only on the phone | `SocketTimeoutException` in log | the phone's network or a slow host; retry (`tap result_reload_connectionerror`) before changing code |

The phone drops `Log.d`. For extra tracing use `Log.i(name, …)` and remove or downgrade it before
finishing. When a fix is needed, change the code, `cs deploy` again (hot reload takes about a second)
and repeat only the failing step. Then repeat the full step-4 list once at the end, because a fix
to `loadLinks` can break `load`.

## Workflow: fixing or updating an existing plugin

1. Reproduce on the phone first (`cs deploy P` with the current code, then the failing step), so
   you know the symptom is real and current.
2. Re-check the site with curl or a quick recon of the broken part. If the site moved hosts or
   changed its API, update the recon doc too, so the next fix starts from the truth.
3. Fix, bump `version` in `P/build.gradle.kts`, add or adjust a parser test for the case that
   broke, then run step 4 above.

## Finishing

Restore the phone (see "Device etiquette"), then `cs lease release`. Run `cs test <Name>` once more if it has tests, and
report to the user with a short matrix, so they can see what was checked and what wasn't:

```
<Name> on <device>, Cloudstream <pkg>
| Check            | Result                                              |
|------------------|-----------------------------------------------------|
| loads            | ✓ Loaded plugin <Name>.cs3                          |
| main page        | ✓ 6/6 rows (Trending, Latest, …)                    |
| search "honey"   | ✓ 1 result                                          |
| load (series)    | ✓ Honey Blonde 2, 4 episodes                        |
| play ep 2        | ✓ HLS 1080p, first frame 1.6 s; 5 sources, 3 subs   |
| movie            | – site has no movies                                |
| not verified     | page 2 of rows; season switch                       |
```

Don't commit unless asked. If a recon or device detail turned out to be non-obvious and
reusable (a new phone quirk, a host-specific trick), it belongs in memory or in
`references/ui-map.md` so the next plugin doesn't have to rediscover it.
