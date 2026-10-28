#!/usr/bin/env python3
"""Build, deploy and drive Cloudstream plugins on an adb-connected phone.

Run from the plugin repo root:  python3 .claude/skills/cloudstream-plugin-dev/scripts/csdev.py <command> ...

Commands (all print short, greppable output):
  devices                      list adb devices and the one that will be used
  lease acquire [--wait S] [--note TEXT]
                               take the exclusive device lease for $CSDEV_OWNER (waits up to S s if held)
  lease release [--force]      give it back (--force: clear someone else's, only when the user says so)
  lease status                 who holds the device, since when, from which worktree
  build   <Plugin>...          ./gradlew :<P>:make  (→ <P>/build/<P>.cs3)
  test    <Plugin>             ./gradlew :<P>:testDebugUnitTest
  deploy  <Plugin>... [--no-build]
                               build, push to /sdcard/Cloudstream3/plugins, hot-reload the app,
                               and confirm "Loaded plugin <P>.cs3 successfully" (or print the failure)
  app                          wake the phone and make sure Cloudstream is up on its home screen
  restart                      force-stop and cold-start Cloudstream (clears first-run screen)
  ui [--all]                   fresh uiautomator dump → numbered list of labelled/clickable nodes
  tap <query> [--nth N] [--exact]
                               tap a node whose text / content-desc / resource-id matches query
  tapxy <x> <y>                raw tap
  type <text>                  type into the focused field (spaces handled)
  key <KEYCODE>...             e.g. key ENTER, key BACK
  swipe <x1> <y1> <x2> <y2> [ms]
  search <query>               open the in-app global search for query (cloudstreamsearch:// deep link)
  shot [name]                  screenshot → scratch dir; prints the path (Read it to see the screen)
  mark                         remember "now" so `log` only shows lines after it
  log [--grep RE] [--plugin P] [--all] [--tail N] [--follow-secs S]
                               app logcat since the last mark, noise filtered; --all = whole buffer;
                               --plugin = only lines naming P (plus their stack traces)
  links                        summary of what the player tried: links, chosen source, errors
  wait <query> [--timeout S]   poll ui dumps until a node matches (or time out)

Screen-level (built on the above; view ids work in portrait and landscape):
  screen                       which screen is up (home/result/player/search) + any error text shown
  home                         go to the home tab, scrolled to top; prints the selected provider
  provider <Name>              select a provider in the home picker (scrolls the list)
  rows [--pages N]             main-page rows and first card titles (exercises getMainPage)
  psearch <query>              search only the selected provider (exercises search)
  play [N]                     on a result page: Play Movie, or episode N (exercises loadLinks); marks the log
  sources                      open the player's Source list, print every link + subtitle, close it

Env: ANDROID_SERIAL (pick device), CS_PACKAGE (default com.lagradost.cloudstream3.prerelease.debug),
     CSDEV_OWNER (unique agent name; required for every command that touches the device),
     CSDEV_SCRATCH (where shots/dumps/state go, in a per-owner subfolder; default /tmp/csdev),
     CSDEV_LEASE_DIR (default ~/.cache/csdev), CSDEV_LEASE_TTL (idle seconds before a lease is stale; 600).

Concurrency: one phone has one screen, one player and one plugin loader (`deploy` reloads every
local plugin), so only the lease holder may drive it. build/test/devices/lease work without it.
"""
import argparse
import fcntl
import json
import os
import re
import shlex
import subprocess
import sys
import time
import xml.etree.ElementTree as ET

PKG = os.environ.get("CS_PACKAGE", "com.lagradost.cloudstream3.prerelease.debug")
OWNER = re.sub(r"[^\w.-]", "_", os.environ.get("CSDEV_OWNER", "").strip())
# Subagents share one session scratchpad, so each owner gets its own folder (log mark, ui dump, shots).
SCRATCH = os.path.join(os.environ.get("CSDEV_SCRATCH", "/tmp/csdev"), OWNER or "_noowner")
DEVICE_PLUGINS = "/sdcard/Cloudstream3/plugins"
DEVICE_UI = f"/sdcard/csdev-ui-{OWNER or 'noowner'}.xml"
STATE_MARK = os.path.join(SCRATCH, "logmark")
LEASE_DIR = os.environ.get("CSDEV_LEASE_DIR", os.path.expanduser("~/.cache/csdev"))
LEASE_TTL = float(os.environ.get("CSDEV_LEASE_TTL", 600))
# Lines from the app that are never interesting while debugging a plugin.
NOISE = re.compile(
    r"chromium|TranClassInfo|Resources|ViewRootImpl|BLASTBufferQueue|Choreographer|InsetsController|"
    r"OpenGLRenderer|AdrenoGLES|libEGL|HWUI|ImeTracker|InputMethodManager|AutofillManager|Gralloc|"
    r"FrameEvents|SurfaceView|VRI\[|WindowOnBackDispatcher|ProfileInstaller|Compatibility ?Change"
)

os.makedirs(SCRATCH, exist_ok=True)


def die(msg, code=1):
    print(f"ERROR: {msg}", file=sys.stderr)
    sys.exit(code)


def run(cmd, check=False, capture=True, timeout=None, **kw):
    r = subprocess.run(cmd, capture_output=capture, text=True, timeout=timeout, **kw)
    if check and r.returncode != 0:
        die(f"{' '.join(cmd)} failed ({r.returncode}):\n{(r.stdout or '')[-2000:]}{(r.stderr or '')[-2000:]}")
    return r


# ---------------------------------------------------------------- device ----

_serial = None


def serial():
    """ANDROID_SERIAL if set; else the only online physical device (emulators are often offline here)."""
    global _serial
    if _serial:
        return _serial
    want = os.environ.get("ANDROID_SERIAL")
    out = run(["adb", "devices"], check=True).stdout.splitlines()[1:]
    online = [l.split()[0] for l in out if l.strip().endswith("\tdevice")]
    if want:
        if want not in online:
            die(f"ANDROID_SERIAL={want} is not online. adb devices:\n" + "\n".join(out))
        _serial = want
    else:
        physical = [s for s in online if not s.startswith("emulator-")]
        pick = physical or online
        if not pick:
            die("no online adb device. Plug the phone in / accept the USB debugging prompt.\n" + "\n".join(out))
        if len(pick) > 1:
            die("several devices online, set ANDROID_SERIAL to one of: " + ", ".join(pick))
        _serial = pick[0]
    return _serial


def adb(*args, check=False, timeout=60):
    return run(["adb", "-s", serial(), *args], check=check, timeout=timeout)


def sh(cmd, check=False, timeout=60):
    """Run a device shell command given as one string (device-side sh parses it)."""
    return adb("shell", cmd, check=check, timeout=timeout)


def pid():
    return sh(f"pidof {PKG}").stdout.strip().split(" ")[0] or None


def wake():
    power = sh("dumpsys power | grep -m1 mWakefulness").stdout
    if "Awake" not in power:
        sh("input keyevent KEYCODE_WAKEUP")
        time.sleep(0.5)
    sh("wm dismiss-keyguard")


def view(uri):
    # -p routes through the exported AccountSelectActivity2 of *this* build (several CS builds are installed).
    return sh(f"am start -a android.intent.action.VIEW -d {shlex.quote(uri)} -p {PKG}")


# ----------------------------------------------------------------- lease ----
# A device-wide lock shared by every worktree and session: {owner, since, heartbeat, cwd, note}.
# It covers a whole device session (tap → screen → play → links depend on each other), not single
# commands. Every device command refreshes the heartbeat; a lease idle for LEASE_TTL is stale and
# can be taken over, so a crashed agent can't hold the phone forever.

def lease_path():
    os.makedirs(LEASE_DIR, exist_ok=True)
    return os.path.join(LEASE_DIR, "lease-" + re.sub(r"[^\w.-]", "_", serial()) + ".json")


def with_lease_file(fn):
    """Run fn(current_lease_or_None) under an flock; if it returns a dict, write it; if False, delete."""
    path = lease_path()
    with open(path + ".lock", "w") as lk:
        fcntl.flock(lk, fcntl.LOCK_EX)
        try:
            cur = json.load(open(path)) if os.path.exists(path) else None
        except (ValueError, OSError):
            cur = None
        new = fn(cur)
        if new is False:
            if os.path.exists(path):
                os.remove(path)
        elif isinstance(new, dict):
            with open(path + ".tmp", "w") as f:
                json.dump(new, f)
            os.replace(path + ".tmp", path)
        return cur


def stale(lease):
    return time.time() - lease["heartbeat"] > LEASE_TTL


def describe(lease):
    idle = int(time.time() - lease["heartbeat"])
    return (f"{lease['owner']} since {time.strftime('%H:%M:%S', time.localtime(lease['since']))}, "
            f"idle {idle}s{' (STALE)' if stale(lease) else ''}, cwd {lease.get('cwd', '?')}"
            + (f", note: {lease['note']}" if lease.get("note") else ""))


def need_owner():
    if not OWNER:
        die("CSDEV_OWNER is not set. Export a name unique to this agent (e.g. CSDEV_OWNER=kayoanime-fix) "
            "and `lease acquire` before touching the device.")


def hold_lease():
    """Exit unless OWNER holds the device lease; refresh its heartbeat."""
    need_owner()

    def check(cur):
        if cur and cur["owner"] == OWNER:
            return dict(cur, heartbeat=time.time())
        return None
    cur = with_lease_file(check)
    if not cur or cur["owner"] != OWNER:
        die(f"{OWNER} does not hold the device lease ({'held by ' + describe(cur) if cur else 'free'}). "
            f"Run `lease acquire` first. Don't drive the phone without it: another agent may be on it.")


def cmd_lease(a):
    if a.action == "status":
        cur = with_lease_file(lambda c: None)
        print(f"{serial()}: " + (describe(cur) if cur else "free"))
        return
    need_owner()
    if a.action == "release":
        def rel(cur):
            if cur and (cur["owner"] == OWNER or a.force):
                return False
            return None
        cur = with_lease_file(rel)
        if not cur:
            print("lease was already free")
        elif cur["owner"] == OWNER or a.force:
            sh(f"rm -f {DEVICE_UI}")
            print(f"released lease of {cur['owner']} on {serial()}")
        else:
            die(f"not yours: held by {describe(cur)}")
        return
    # acquire
    end, told = time.time() + a.wait, False
    while True:
        now = time.time()

        def take(cur):
            if cur is None or cur["owner"] == OWNER or stale(cur):
                mine = cur if cur and cur["owner"] == OWNER else {}
                return {"owner": OWNER, "since": mine.get("since", now), "heartbeat": now,
                        "cwd": os.getcwd(), "note": a.note or mine.get("note", "")}
            return None
        cur = with_lease_file(take)
        if cur is None or cur["owner"] == OWNER or stale(cur):
            took = f" (took over stale lease of {cur['owner']})" if cur and cur["owner"] != OWNER else ""
            print(f"lease acquired by {OWNER} on {serial()}{took}")
            return
        if now >= end:
            die(f"device busy: held by {describe(cur)}. Work on code/tests meanwhile and retry "
                f"`lease acquire --wait N`; it frees on release or after {int(LEASE_TTL)}s idle.")
        if not told:
            print(f"waiting for lease held by {describe(cur)}…", flush=True)
            told = True
        time.sleep(5)


# -------------------------------------------------------------------- ui ----

def dump():
    """Fresh uiautomator dump → list of node dicts. A stale dump file is deleted first."""
    local = os.path.join(SCRATCH, "ui.xml")
    for attempt in range(4):
        sh(f"rm -f {DEVICE_UI}")
        sh(f"uiautomator dump {DEVICE_UI}", timeout=30)
        data = adb("exec-out", f"cat {DEVICE_UI}").stdout
        if data.strip().startswith("<?xml") and "</hierarchy>" in data:
            with open(local, "w") as f:
                f.write(data)
            break
        time.sleep(1 + attempt)
    else:
        die("uiautomator dump stayed empty (service stuck?). Use `shot` + `tapxy` instead, "
            "or `adb shell pkill -f uiautomator` and retry.")
    nodes = []
    for n in ET.parse(local).getroot().iter("node"):
        m = re.match(r"\[(\d+),(\d+)\]\[(\d+),(\d+)\]", n.get("bounds", ""))
        if not m:
            continue
        x1, y1, x2, y2 = map(int, m.groups())
        nodes.append({
            "text": n.get("text") or "",
            "desc": n.get("content-desc") or "",
            "id": (n.get("resource-id") or "").split("/")[-1],
            "cls": (n.get("class") or "").split(".")[-1],
            "pkg": n.get("package") or "",
            "click": n.get("clickable") == "true",
            "focused": n.get("focused") == "true",
            "checked": n.get("checked") == "true",
            "x": (x1 + x2) // 2, "y": (y1 + y2) // 2,
            "w": x2 - x1, "h": y2 - y1,
        })
    return nodes


def label(n):
    parts = []
    if n["text"]:
        parts.append(repr(n["text"][:60]))
    if n["desc"]:
        parts.append(f"desc={n['desc'][:40]!r}")
    if n["id"]:
        parts.append(f"#{n['id']}")
    return " ".join(parts) or f"<{n['cls']}>"


def find(nodes, query, exact=False):
    q = query.lower()
    keys = ("text", "desc", "id")
    hits = [n for n in nodes if any(n[k].lower() == q for k in keys)]
    if not hits and not exact:
        hits = [n for n in nodes if any(q in n[k].lower() for k in keys)]
    # On-screen, non-zero-size nodes only; keep reading order.
    return [n for n in hits if n["w"] > 0 and n["h"] > 0]


def cmd_ui(a):
    nodes = dump()
    size = sh("wm size").stdout.strip().splitlines()[-1]
    pkgs = sorted({n["pkg"] for n in nodes if n["pkg"]})
    root = nodes[0] if nodes else {"w": 0, "h": 0}
    print(f"# {size}; {'landscape' if root['w'] > root['h'] else 'portrait'}; windows: {', '.join(pkgs)}")
    if pkgs and PKG not in pkgs:
        print(f"# WARNING: Cloudstream is not in front. Don't tap other apps' dialogs; show the user (`shot`).")
    for i, n in enumerate(nodes):
        if a.all or n["text"] or n["desc"] or (n["click"] and n["id"]):
            flags = ("C" if n["click"] else " ") + ("F" if n["focused"] else " ") + ("✓" if n["checked"] else " ")
            print(f"{i:3d} {flags} ({n['x']:4d},{n['y']:4d}) {label(n)}")


def cmd_tap(a):
    nodes = dump()
    hits = find(nodes, a.query, a.exact)
    if not hits:
        die(f"no node matches {a.query!r}. Run `ui` to see what is on screen.")
    if a.nth >= len(hits):
        die(f"only {len(hits)} match(es) for {a.query!r}")
    n = hits[a.nth]
    # Never act on another app's window (Google account prompts, permission dialogs, the launcher…).
    if n["pkg"] and n["pkg"] != PKG and n["pkg"] != "com.android.systemui" and not a.force:
        die(f"{label(n)} belongs to {n['pkg']}, not Cloudstream. Something else is in front; "
            f"look with `shot` and ask the user before touching it (or pass --force for a dialog you understand).")
    sh(f"input tap {n['x']} {n['y']}")
    more = f" ({len(hits)} matches, used --nth {a.nth})" if len(hits) > 1 else ""
    print(f"tapped {label(n)} at ({n['x']},{n['y']}){more}")


def cmd_wait(a):
    end = time.time() + a.timeout
    while time.time() < end:
        hits = find(dump(), a.query, a.exact)
        if hits:
            print(f"found {label(hits[0])} at ({hits[0]['x']},{hits[0]['y']})")
            return
        time.sleep(1.5)
    die(f"timed out after {a.timeout}s waiting for {a.query!r}")


def cmd_type(a):
    # `input text` treats space as an argument break and %s as space.
    text = a.text.replace("%", r"\%").replace(" ", "%s")
    sh(f"input text {shlex.quote(text)}")
    print(f"typed {a.text!r}")


def cmd_key(a):
    for k in a.keys:
        k = k.upper()
        sh(f"input keyevent {k if k.startswith('KEYCODE_') or k.isdigit() else 'KEYCODE_' + k}")
    print("sent " + " ".join(a.keys))


def cmd_shot(a):
    path = os.path.join(SCRATCH, (a.name or time.strftime("shot-%H%M%S")) + ".png")
    with open(path, "wb") as f:
        f.write(subprocess.run(["adb", "-s", serial(), "exec-out", "screencap", "-p"], capture_output=True).stdout)
    if os.path.getsize(path) < 1000:
        die(f"screenshot empty ({path}); is the screen asleep? run `app`")
    print(path)


# ------------------------------------------------------------------- app ----

def first_run_screen(nodes):
    return any(n["id"] == "next_btt" for n in nodes) and any(n["text"] == "Extensions" for n in nodes)


def ensure_app(cold=False, quiet=True):
    wake()
    if cold:
        sh(f"am force-stop {PKG}")
    if not pid():
        sh(f"monkey -p {PKG} -c android.intent.category.LAUNCHER 1")
        time.sleep(4)
    # Bring it to front if it is backgrounded.
    if PKG not in sh("dumpsys activity activities | grep -m1 topResumedActivity").stdout:
        sh(f"monkey -p {PKG} -c android.intent.category.LAUNCHER 1")
        time.sleep(2)
    for _ in range(8):
        nodes = dump()
        if first_run_screen(nodes):
            n = next(n for n in nodes if n["id"] == "next_btt")
            sh(f"input tap {n['x']} {n['y']}")
            print("dismissed first-run Extensions screen")
            time.sleep(5)
            continue
        if any(n["id"] in ("home_api_fab", "navigation_home") for n in nodes):
            break
        time.sleep(1.5)
    if not quiet:
        print(f"app up (pid {pid()}) on {serial()}")


def cmd_app(a):
    ensure_app(quiet=False)


def cmd_restart(a):
    ensure_app(cold=True, quiet=False)


def cmd_search(a):
    ensure_app()
    view("cloudstreamsearch://" + a.query.replace(" ", "%20"))
    print(f"opened global search for {a.query!r}; results are grouped per provider, "
          f"filtered by the type chips (Movies/TV Series/Anime/Asian Dramas/NSFW…)")


# ------------------------------------------------------------ build/deploy --

def gradle(task, quiet=True):
    base = ["./gradlew", task]
    if quiet:
        base.append("-q")
    r = run(base + ["--offline"], capture=True, timeout=900)
    if r.returncode != 0 and re.search(r"offline|Could not resolve|No cached version", r.stdout + r.stderr):
        print("offline build could not resolve deps, retrying online…")
        r = run(base, capture=True, timeout=900)
    return r


def cmd_build(a):
    for p in a.plugins:
        if not os.path.isfile(f"{p}/build.gradle.kts"):
            die(f"{p}/build.gradle.kts not found (run from the repo root; plugin names are folder names)")
        r = gradle(f":{p}:make")
        if r.returncode != 0:
            print((r.stdout + r.stderr)[-6000:])
            die(f"build of {p} failed")
        print(f"built {p}/build/{p}.cs3 ({os.path.getsize(f'{p}/build/{p}.cs3')} bytes)")


def cmd_test(a):
    r = gradle(f":{a.plugin}:testDebugUnitTest", quiet=False)
    out = r.stdout + r.stderr
    results = f"{a.plugin}/build/test-results/testDebugUnitTest"
    total = failed = 0
    for f in (os.listdir(results) if os.path.isdir(results) else []):
        if f.endswith(".xml"):
            root = ET.parse(os.path.join(results, f)).getroot()
            total += int(root.get("tests", 0))
            failed += int(root.get("failures", 0)) + int(root.get("errors", 0))
            for case in root.iter("testcase"):
                for bad in list(case.iter("failure")) + list(case.iter("error")):
                    print(f"FAILED {case.get('classname')}.{case.get('name')}: {(bad.get('message') or '')[:300]}")
    if r.returncode and not failed:
        print(out[-4000:])  # compile error or gradle failure, not a test failure
    print(f"{'TESTS FAILED' if r.returncode else 'tests passed'}: {total - failed}/{total} passed")
    sys.exit(r.returncode)


def cmd_deploy(a):
    if not a.no_build:
        cmd_build(a)
    hold_lease()  # after the build: compiling doesn't need the phone
    ensure_app()
    for p in a.plugins:
        src = f"{p}/build/{p}.cs3"
        if not os.path.isfile(src):
            die(f"{src} missing; build first")
        adb("push", src, f"{DEVICE_PLUGINS}/", check=True)
    mark()
    # `cloudstreamapp:` is the gradle deployWithAdb hook: it unloads and reloads every local plugin in-process.
    view("cloudstreamapp:")
    want = {f"{p}.cs3" for p in a.plugins}
    ok, failed, end = set(), {}, time.time() + 25
    while time.time() < end and len(ok) + len(failed) < len(want):
        time.sleep(1.5)
        for line in logcat_since().splitlines():
            m = re.search(r"Loaded plugin (\S+\.cs3) successfully", line)
            if m and m.group(1) in want:
                ok.add(m.group(1))
            m = re.search(r"Failed to load (?:plugin\s+)?\S*?(\w+\.cs3)", line)
            if m and m.group(1) in want:
                failed[m.group(1)] = line
    for f in sorted(want):
        if f in ok:
            print(f"loaded {f}")
        elif f in failed:
            print(f"FAILED {f}: {failed[f][:300]}")
        else:
            print(f"NO LOAD LINE for {f} within 25s (check `log --grep PluginManager`)")
    if failed:
        print("\n".join(l for l in logcat_since().splitlines()
                        if re.search(r"PluginManager|Caused by|Exception|at com\.nropvox", l))[-4000:])
        sys.exit(1)


# ------------------------------------------------------------------- log ----

def mark():
    stamp = sh("date +'%m-%d %H:%M:%S.000'").stdout.strip()
    with open(STATE_MARK, "w") as f:
        f.write(stamp)
    return stamp


def logcat_since(all_=False):
    args = ["logcat", "-d", "-v", "threadtime"]
    if not all_ and os.path.exists(STATE_MARK):
        args += ["-T", open(STATE_MARK).read().strip()]
    return adb(*args, timeout=60).stdout


def app_lines(text, extra=None):
    p = pid()
    keep = re.compile(extra, re.I) if extra else None
    out = []
    for line in text.splitlines():
        parts = line.split()
        if len(parts) < 6:
            continue
        mine = p and parts[2] == p
        if keep and keep.search(line):
            out.append(line)
        elif not keep and mine and not NOISE.search(line):
            out.append(line)
    return out


def plugin_lines(lines, name):
    """Lines naming the plugin (tag, class, URL…) plus the stack-trace lines that follow them."""
    rx, out, follow = re.compile(re.escape(name), re.I), [], False
    for l in lines:
        msg = l.split(": ", 1)[-1].lstrip()
        if rx.search(l):
            out.append(l)
            follow = True
        elif follow and msg.startswith(("at ", "Caused by", "...")):
            out.append(l)
        else:
            follow = False
    return out


def cmd_mark(a):
    print("log mark at " + mark())


def cmd_log(a):
    if a.follow_secs:
        time.sleep(a.follow_secs)
    lines = app_lines(logcat_since(a.all), a.grep)
    if a.plugin:
        lines = plugin_lines(lines, a.plugin)
    print("\n".join(lines[-a.tail:]) if lines else "(no matching lines; note this phone drops Log.d — use Log.i/w)")


def cmd_links(a):
    """What happened since the last mark: link picked, time to first frame, subtitles, errors."""
    lines = app_lines(logcat_since(), None)
    t0, out, subs = None, [], []
    for l in lines:
        msg = l.split(": ", 1)[-1]
        ts = l.split()[1]
        if "loadOnlinePlayer" in l:
            t0 = ts
            out.append(f"{ts} PLAYING {msg.replace('loadOnlinePlayer ', '')[:300]}")
        elif "Rendered first frame" in l:
            out.append(f"{ts} FIRST FRAME" + (f" (link chosen at {t0})" if t0 else ""))
        elif re.search(r"playerError|onPlayerError|PlaybackException|Source error", l):
            out.append(f"{ts} PLAYER ERROR {msg[:300]}")
        elif "setPreferredSubtitles init" in l:
            u = re.search(r"url=(\S+?),", l)
            if u and u.group(1) not in subs:
                subs.append(u.group(1))
        elif re.search(r"Exception|Caused by|FATAL|PatternSyntax|NoClassDefFound|WebViewResolver|NiceHttp", l) \
                and not msg.lstrip().startswith("at "):
            out.append(f"{ts} {l.split(None, 5)[-1][:300]}")
    print("\n".join(out[-60:]) or "(nothing since mark: did loadLinks run? is the player open?)")
    if subs:
        print(f"subtitles offered: {len(subs)} (" + ", ".join(subs[:4]) + (" …" if len(subs) > 4 else "") + ")")


# --------------------------------------------------------- app screens ----
# View ids below come from the Cloudstream layouts (fragment_home/result/player) and work in both
# orientations; after the player the app often stays landscape with a nav rail instead of a bottom bar.

ERROR_IDS = ("result_error_text", "home_loading_error", "home_reload_connectionerror", "result_reload_connectionerror")


def screen_of(nodes):
    ids = {n["id"] for n in nodes}
    # The result page embeds a trailer player_view, so check result ids first.
    if any(i.startswith("result_") for i in ids):
        return "result"
    if "player_view" in ids:
        return "player"
    if "search_result_root" in ids or "search_src_text" in ids and "home_api_fab" not in ids:
        return "search"
    if "home_api_fab" in ids:
        return "home"
    return "other"


def print_errors(nodes):
    errs = [n for n in nodes if n["id"] in ERROR_IDS and n["text"]]
    for n in errs:
        print(f"ERROR ON SCREEN: {n['text'][:300]}")
    return bool(errs)


def cmd_screen(a):
    nodes = dump()
    kind = screen_of(nodes)
    if kind == "result":
        nodes = wait_result_loaded()
        title = find(nodes, "result_title", exact=True)
        eps = find(nodes, "result_episodes_text", exact=True)
        movie = find(nodes, "result_play_movie", exact=True)
        print(f"screen: result — {title[0]['text'] if title else '?'}; "
              f"{eps[0]['text'] if eps else ('movie' if movie else 'no episodes text')}")
        return
    print(f"screen: {kind}")
    print_errors(nodes)


def go_home():
    ensure_app()
    for _ in range(4):
        nodes = dump()
        if screen_of(nodes) == "home":
            break
        hit = find(nodes, "navigation_home", exact=True)
        if hit:
            sh(f"input tap {hit[0]['x']} {hit[0]['y']}")
        else:
            sh("input keyevent KEYCODE_BACK")
        time.sleep(1.5)
    # Scroll the home feed back to the top so search_button / the preview are reachable.
    for _ in range(6):
        nodes = dump()
        if find(nodes, "search_button", exact=True):
            return
        w, h = nodes[0]["w"], nodes[0]["h"]
        sh(f"input swipe {w // 2 + w // 6} {h // 5} {w // 2 + w // 6} {h - h // 6} 150; "
           f"input swipe {w // 2 + w // 6} {h // 5} {w // 2 + w // 6} {h - h // 6} 150")
        time.sleep(0.8)


def screen_wh():
    root = dump()[0]
    return root["w"], root["h"]


def cmd_home(a):
    go_home()
    nodes = dump()
    fab = find(nodes, "home_api_fab", exact=True)
    print(f"home; selected provider: {fab[0]['text'] if fab else '?'}")
    print_errors(nodes)


def cmd_provider(a):
    """Select a provider on the home screen (the home search then only searches that provider)."""
    go_home()
    nodes = dump()
    fab = find(nodes, "home_api_fab", exact=True)
    if fab and fab[0]["text"].lower() == a.name.lower():
        print(f"{a.name} already selected")
        return
    if not fab:
        die("provider chip (home_api_fab) not on screen; run `ui`")
    sh(f"input tap {fab[0]['x']} {fab[0]['y']}")
    time.sleep(1.5)
    for attempt in range(5):
        nodes = dump()
        rows = [n for n in nodes if n["id"] == "text1" and n["text"]]
        hit = [n for n in rows if re.sub(r"^\W+\s*", "", n["text"]).lower() == a.name.lower()] or \
              [n for n in rows if a.name.lower() in n["text"].lower()]
        if hit:
            sh(f"input tap {hit[0]['x']} {hit[0]['y']}")
            print(f"selected {hit[0]['text']}; main page loading")
            return
        # The list is scrollable: swipe it up and look again.
        if rows:
            x, top, bottom = rows[0]["x"], rows[0]["y"], rows[-1]["y"]
            sh(f"input swipe {x} {bottom} {x} {top} 300")
            time.sleep(1)
    chips = [n for n in nodes if n["id"].startswith("home_select_")]
    print("providers listed: " + ", ".join(n["text"] for n in nodes if n["id"] == "text1"))
    print("type chips: " + ", ".join(f"{n['text']}{'✓' if n['checked'] else ''}" for n in chips))
    die(f"{a.name} not in the picker. The picker only lists providers whose tvTypes match a ticked chip; "
        f"tap the right chip (e.g. `tap home_select_nsfw`) and retry. NSFW providers also need NSFW enabled "
        f"under Settings → Preferred media. If it is still missing, the plugin did not load: `log --grep PluginManager`.")


def cmd_rows(a):
    """List the main-page rows (getMainPage) and the first card titles of each, scrolling down."""
    go_home()
    w, h = screen_wh()
    seen, order, idle = {}, [], 0
    for _ in range(a.pages):
        nodes = dump()
        before = sum(map(len, seen.values())) + len(order)
        print_errors(nodes)
        cur = None
        for n in nodes:
            if n["id"] == "home_child_more_info" and n["text"]:
                cur = n["text"]
                if cur not in seen:
                    seen[cur] = []
                    order.append(cur)
            elif n["id"] == "imageText" and cur and n["text"] not in seen[cur]:
                seen[cur].append(n["text"])
        idle = idle + 1 if sum(map(len, seen.values())) + len(order) == before else 0
        if idle >= 2:  # reached the bottom
            break
        sh(f"input swipe {w // 2 + w // 6} {h - h // 4} {w // 2 + w // 6} {h // 4} 400")
        time.sleep(1.2)
    if not order:
        die("no rows found (still loading? hasMainPage=false? error?). `shot` to look.")
    for r in order:
        print(f"[{r}] {len(seen[r])} visible: " + " | ".join(t[:40] for t in seen[r][:4]))


def cmd_psearch(a):
    """Provider-scoped search from the home search box (searches only the selected provider)."""
    go_home()
    hit = find(dump(), "search_button", exact=True)
    if not hit:
        die("home search_button not visible; run `ui`")
    sh(f"input tap {hit[0]['x']} {hit[0]['y']}")
    time.sleep(1.2)
    cmd_type(argparse.Namespace(text=a.query))
    sh("input keyevent KEYCODE_ENTER")
    end = time.time() + a.timeout
    while time.time() < end:
        time.sleep(2)
        nodes = dump()
        titles = [n["text"] for n in nodes if n["id"] == "imageText" and n["text"]]
        if titles or print_errors(nodes):
            print(f"{len(titles)} result(s) visible: " + " | ".join(t[:50] for t in titles))
            return
    print("no results after timeout (search() returned nothing, threw, or is slow): check `log`")


def wait_result_loaded(timeout=45):
    """Wait until load() has produced a page (title/episodes) or the error view; exit on error."""
    end = time.time() + timeout
    while True:
        nodes = dump()
        if screen_of(nodes) != "result":
            die("not on a result page; open a title first (tap its card)")
        if print_errors(nodes):
            print("load() failed: see `log` (Retry: `tap result_reload_connectionerror`)")
            sys.exit(1)
        ids = {n["id"] for n in nodes}
        if ids & {"result_title", "result_play_movie", "episode_text", "result_episodes_text", "result_no_episodes"}:
            return nodes
        if time.time() > end:
            die(f"result page still loading after {timeout}s")
        time.sleep(1.5)


def cmd_play(a):
    """On a result page: play the movie, or episode N (1-based, by on-screen order after scrolling)."""
    nodes = wait_result_loaded()
    mark()
    movie = find(nodes, "result_play_movie", exact=True)
    if movie and not a.episode:
        sh(f"input tap {movie[0]['x']} {movie[0]['y']}")
        print("tapped Play Movie")
        return
    w, h = nodes[0]["w"], nodes[0]["h"]
    want = a.episode or 1
    for _ in range(8):
        eps = [n for n in nodes if n["id"] == "episode_text" and n["text"]]
        if eps:  # in landscape the episode list is its own pane; scroll that one
            w = eps[0]["x"] * 2
        for n in eps:
            m = re.match(r"(\d+)\.", n["text"])
            if m and int(m.group(1)) == want:
                sh(f"input tap {n['x']} {n['y']}")
                print(f"tapped {n['text']!r}")
                return
        sh(f"input swipe {w // 2} {h - h // 4} {w // 2} {h // 3} 400")
        time.sleep(1)
        nodes = dump()
    die(f"episode {want} not found (seasons? `tap result_season_button`); run `ui`")


def cmd_sources(a):
    """Open the player's Source list and print every emitted link (plus subtitles), then close it."""
    nodes = dump()
    if screen_of(nodes) != "player":
        die("player not open")
    w, h = nodes[0]["w"], nodes[0]["h"]
    btn = find(nodes, "player_sources_btt", exact=True)
    bx, by = (btn[0]["x"], btn[0]["y"]) if btn else (int(w * 0.664), int(h * 0.907))
    # Controls auto-hide within ~2 s, longer than a uiautomator round trip: show them and tap in one shell call.
    sh(f"input tap {w // 2} {h // 4}; sleep 0.4; input tap {bx} {by}")
    time.sleep(1.5)
    sources, subs = [], []
    for _ in range(a.pages):
        nodes = dump()
        if not find(nodes, "apply_btt", exact=True):
            die("source dialog did not open; retry `sources` (or tap player_view then player_sources_btt)")
        mid = nodes[0]["w"] * 0.55
        new = 0
        for n in nodes:
            if n["id"] == "text1" and n["text"]:
                bucket = sources if n["x"] < mid else subs
                if n["text"] not in bucket:
                    bucket.append(n["text"])
                    new += n["x"] < mid
        src = [n for n in nodes if n["id"] == "text1" and n["x"] < mid]
        if not new or len(src) < 2:
            break
        sh(f"input swipe {src[-1]['x']} {src[-1]['y']} {src[0]['x']} {src[0]['y']} 400")
        time.sleep(0.8)
    print(f"{len(sources)} source(s):")
    for s_ in sources:
        print("  " + s_)
    print("subtitles: " + ", ".join(t for t in subs if not t.startswith(("Load ", "No Subtitles"))))
    cancel = find(dump(), "cancel_btt", exact=True)
    if cancel:
        sh(f"input tap {cancel[0]['x']} {cancel[0]['y']}")


def cmd_devices(a):
    print(run(["adb", "devices", "-l"]).stdout.strip())
    print(f"using: {serial()}  package: {PKG}  installed: "
          f"{'yes' if PKG in sh('pm list packages ' + PKG).stdout else 'NO'}")


def cmd_tapxy(a):
    sh(f"input tap {a.x} {a.y}")
    print(f"tapped ({a.x},{a.y})")


def cmd_swipe(a):
    sh(f"input swipe {a.x1} {a.y1} {a.x2} {a.y2} {a.ms}")
    print("swiped")


def main():
    ap = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    sub = ap.add_subparsers(dest="cmd", required=True)
    sub.add_parser("devices").set_defaults(f=cmd_devices)
    p = sub.add_parser("lease"); p.add_argument("action", choices=["acquire", "release", "status"])
    p.add_argument("--wait", type=float, default=90); p.add_argument("--note"); p.add_argument("--force", action="store_true"); p.set_defaults(f=cmd_lease)
    p = sub.add_parser("build"); p.add_argument("plugins", nargs="+"); p.set_defaults(f=cmd_build)
    p = sub.add_parser("test"); p.add_argument("plugin"); p.set_defaults(f=cmd_test)
    p = sub.add_parser("deploy"); p.add_argument("plugins", nargs="+"); p.add_argument("--no-build", action="store_true"); p.set_defaults(f=cmd_deploy)
    sub.add_parser("app").set_defaults(f=cmd_app)
    sub.add_parser("restart").set_defaults(f=cmd_restart)
    p = sub.add_parser("ui"); p.add_argument("--all", action="store_true"); p.set_defaults(f=cmd_ui)
    p = sub.add_parser("tap"); p.add_argument("query"); p.add_argument("--nth", type=int, default=0); p.add_argument("--exact", action="store_true"); p.add_argument("--force", action="store_true"); p.set_defaults(f=cmd_tap)
    p = sub.add_parser("wait"); p.add_argument("query"); p.add_argument("--timeout", type=float, default=30); p.add_argument("--exact", action="store_true"); p.set_defaults(f=cmd_wait)
    p = sub.add_parser("tapxy"); p.add_argument("x", type=int); p.add_argument("y", type=int); p.set_defaults(f=cmd_tapxy)
    p = sub.add_parser("type"); p.add_argument("text"); p.set_defaults(f=cmd_type)
    p = sub.add_parser("key"); p.add_argument("keys", nargs="+"); p.set_defaults(f=cmd_key)
    p = sub.add_parser("swipe")
    for k in ("x1", "y1", "x2", "y2"):
        p.add_argument(k, type=int)
    p.add_argument("ms", type=int, nargs="?", default=300); p.set_defaults(f=cmd_swipe)
    p = sub.add_parser("search"); p.add_argument("query"); p.set_defaults(f=cmd_search)
    p = sub.add_parser("shot"); p.add_argument("name", nargs="?"); p.set_defaults(f=cmd_shot)
    sub.add_parser("mark").set_defaults(f=cmd_mark)
    p = sub.add_parser("log"); p.add_argument("--grep"); p.add_argument("--plugin"); p.add_argument("--all", action="store_true")
    p.add_argument("--tail", type=int, default=120); p.add_argument("--follow-secs", type=float, default=0); p.set_defaults(f=cmd_log)
    sub.add_parser("links").set_defaults(f=cmd_links)
    sub.add_parser("screen").set_defaults(f=cmd_screen)
    sub.add_parser("home").set_defaults(f=cmd_home)
    p = sub.add_parser("provider"); p.add_argument("name"); p.set_defaults(f=cmd_provider)
    p = sub.add_parser("rows"); p.add_argument("--pages", type=int, default=12); p.set_defaults(f=cmd_rows)
    p = sub.add_parser("psearch"); p.add_argument("query"); p.add_argument("--timeout", type=float, default=20); p.set_defaults(f=cmd_psearch)
    p = sub.add_parser("play"); p.add_argument("episode", type=int, nargs="?"); p.set_defaults(f=cmd_play)
    p = sub.add_parser("sources"); p.add_argument("--pages", type=int, default=4); p.set_defaults(f=cmd_sources)
    a = ap.parse_args()
    # deploy takes the lease itself, after building.
    if a.cmd not in ("devices", "lease", "build", "test", "deploy"):
        hold_lease()
    a.f(a)


if __name__ == "__main__":
    main()
