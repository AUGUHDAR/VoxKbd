#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""Build every Vox Kbd port target and collect the jars.

Usage: python tools/port_build_all.py [--only target ...] [--no-gen] [--skip <target> ...]

Per target: (regenerate via port_gen) -> gradle build -> copy the jar to
成品/<Fabric|Forge|NeoForge>/voxkbd-<ver>-<group>-<loader>.jar.

Gradle is invoked through its distribution .bat by absolute path: the wrapper scripts are
unusable from Git Bash (arg re-assembly injects a phantom task).
"""
import os
import re
import shutil
import subprocess
import sys
import time

HERE = os.path.dirname(os.path.abspath(__file__))
ROOT = os.path.dirname(HERE)
sys.path.insert(0, HERE)
import port_gen  # noqa: E402

DISTS = os.path.join("D:/", ".gradle", "wrapper", "dists")
LOGDIR = os.path.join(ROOT, "port", "build-logs")


def find_gradle(version):
    prefix = f"gradle-{version}-bin"
    base = os.path.join(DISTS, prefix)
    if not os.path.isdir(base):
        raise SystemExit(f"gradle {version} distribution not found under {DISTS}")
    for inner in os.listdir(base):
        cand = os.path.join(base, inner, f"gradle-{version}", "bin", "gradle.bat")
        if os.path.isfile(cand):
            return cand
    raise SystemExit(f"gradle.bat not found inside {base}")


def collect(t):
    """Copy the target's jar into 成品/<Loader>/. Returns the copied file name or None."""
    wanted = port_gen.jar_name(t)
    libs = os.path.join(port_gen.tdir_of(t), "build", "libs")
    if not os.path.isfile(os.path.join(libs, wanted)):
        return None
    out_dir = os.path.join(ROOT, "成品", port_gen.DISPLAY[t["loader"]])
    os.makedirs(out_dir, exist_ok=True)
    shutil.copy2(os.path.join(libs, wanted), os.path.join(out_dir, wanted))
    return wanted


def build(key):
    t = port_gen.TARGETS[key]
    log = os.path.join(LOGDIR, key.replace("/", "-") + ".log")
    os.makedirs(LOGDIR, exist_ok=True)
    gradle = find_gradle(t["gradle"])
    t0 = time.time()
    with open(log, "w", encoding="utf-8", newline="\n") as lf:
        lf.write(f"=== {key} (gradle {t['gradle']}, jvm {t['jvm']}) ===\n")
        lf.flush()
        proc = subprocess.run(
            [gradle, "build", "--console=plain", "--stacktrace"],
            cwd=port_gen.tdir_of(t),
            stdout=lf, stderr=subprocess.STDOUT,
            env={**os.environ, "GRADLE_OPTS": "-Dorg.gradle.daemon=false"},
        )
        dt = time.time() - t0
        if proc.returncode != 0:
            return False, f"gradle exit {proc.returncode} after {dt:.0f}s (see {log})"
    got = collect(t)
    if not got:
        return False, f"no jar produced after {dt:.0f}s (see {log})"
    return True, f"ok {dt:.0f}s -> {got}"


def main():
    args = sys.argv[1:]
    no_gen = "--no-gen" in args
    only, skip = [], []
    bucket = None
    for a in args:
        if a == "--only":
            bucket = only
            continue
        if a == "--skip":
            bucket = skip
            continue
        if a.startswith("--"):
            continue
        if bucket is not None:
            bucket.append(a)
    keys = only or list(port_gen.TARGETS)
    keys = [k for k in keys if k not in skip]
    for k in keys:
        if k not in port_gen.TARGETS:
            sys.exit(f"unknown target {k}")

    os.makedirs(LOGDIR, exist_ok=True)
    if not no_gen:
        print("== regenerating targets ==", flush=True)
        r = subprocess.run([sys.executable, os.path.join(HERE, "port_gen.py"), *keys])
        if r.returncode != 0:
            sys.exit("port_gen failed")

    failed = []
    for i, k in enumerate(keys, 1):
        print(f"[{i}/{len(keys)}] {k} ...", flush=True)
        ok, msg = build(k)
        print(f"[{i}/{len(keys)}] {k}: {msg}", flush=True)
        if not ok:
            failed.append(k)
    print()
    if failed:
        print(f"FAILED ({len(failed)}): {', '.join(failed)}")
        sys.exit(1)
    print(f"ALL {len(keys)} TARGETS BUILT")


if __name__ == "__main__":
    main()
