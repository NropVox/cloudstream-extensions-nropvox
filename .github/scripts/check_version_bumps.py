#!/usr/bin/env python3
"""Fail when a plugin's code changed between two commits but its `version` didn't go up.

A plugin counts as changed when a file under <Plugin>/ changed (tests and Markdown excluded) or a file under
shared/<name>/ that its build.gradle.kts compiles in. Installed copies only update when `version` increases.

    .github/scripts/check_version_bumps.py <base-rev> [head-rev]    # head defaults to HEAD

A head commit whose message contains [no-bump] skips the check.
"""
import re
import subprocess
import sys
from pathlib import PurePosixPath

VERSION_RE = re.compile(r"^version\s*=\s*(\d+)", re.MULTILINE)


def git(*args, check=True):
    result = subprocess.run(["git", *args], capture_output=True, text=True)
    if check and result.returncode != 0:
        sys.exit(f"git {' '.join(args)} failed: {result.stderr.strip()}")
    return result


def show(rev, path):
    result = git("show", f"{rev}:{path}", check=False)
    return result.stdout if result.returncode == 0 else None


def version_of(build_file_text):
    match = VERSION_RE.search(build_file_text or "")
    return int(match.group(1)) if match else None


def main():
    if len(sys.argv) < 2:
        sys.exit(__doc__)
    base, head = sys.argv[1], sys.argv[2] if len(sys.argv) > 2 else "HEAD"

    if git("cat-file", "-e", f"{base}^{{commit}}", check=False).returncode != 0:
        print(f"Base {base} is not in this clone (first push or force-push); skipping the version check.")
        return
    if "[no-bump]" in git("log", "-1", "--format=%B", head).stdout:
        print("Head commit says [no-bump]; skipping the version check.")
        return

    tree = git("ls-tree", "-r", "--name-only", head).stdout.splitlines()
    shared_dirs = {p.split("/")[1] for p in tree if p.startswith("shared/") and p.count("/") >= 2}
    plugins = sorted(p.split("/")[0] for p in tree if p.count("/") == 1 and p.endswith("/build.gradle.kts"))

    # Plugins name shared folders as "../shared/<name>" or, like OmniStream, as a quoted name in a list.
    uses = {}
    for plugin in plugins:
        text = show(head, f"{plugin}/build.gradle.kts")
        tokens = set(re.findall(r"shared/([\w-]+)", text)) | set(re.findall(r'"([\w-]+)"', text))
        uses[plugin] = tokens & shared_dirs

    changed = git("diff", "--name-only", f"{base}...{head}").stdout.splitlines()
    reasons = {}
    for path in changed:
        parts = PurePosixPath(path).parts
        if len(parts) < 2 or path.endswith(".md") or "/src/test/" in path:
            continue
        if parts[0] in uses:
            reasons.setdefault(parts[0], set()).add(path)
        elif parts[0] == "shared":
            for plugin, folders in uses.items():
                if parts[1] in folders:
                    reasons.setdefault(plugin, set()).add(f"shared/{parts[1]}/")

    missing = []
    for plugin, why in sorted(reasons.items()):
        build_file = f"{plugin}/build.gradle.kts"
        old = show(base, build_file)
        if old is None:
            continue  # new plugin
        before, after = version_of(old), version_of(show(head, build_file))
        if after is None or (before is not None and after <= before):
            missing.append((plugin, before, after, sorted(why)))

    if not missing:
        print(f"Version check passed ({len(reasons)} changed plugin(s), all bumped).")
        return
    for plugin, before, after, why in missing:
        print(f"::error file={plugin}/build.gradle.kts::{plugin} changed but version stayed {before} -> {after}")
        for item in why[:5]:
            print(f"    {item}")
        if len(why) > 5:
            print(f"    ... and {len(why) - 5} more")
    print("Bump `version` in each plugin above, or put [no-bump] in the commit message if no user-visible change.")
    sys.exit(1)


if __name__ == "__main__":
    main()
