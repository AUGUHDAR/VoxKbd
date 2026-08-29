"""Build the MC 26.2 classpath as a Windows-path-per-line file for `java -cp @file`.

Walks both the version JSON (honours the recorded `downloads.artifact.path` values) AND
the `libraries/` tree (catches local-only mirrors such as ASM that the JSON leaves as
artifact.path = null). Each jar is written on its own line so the resulting file is
well below the 32k command-line cap.
"""

import os
import sys
import json


def main():
    if len(sys.argv) < 3:
        print("usage: build_mc_classpath.py <game_dir> <version>", file=sys.stderr)
        sys.exit(1)
    game_dir = sys.argv[1]
    version = sys.argv[2]
    vdir = os.path.join(game_dir, "versions", version)
    libs_json = json.load(open(os.path.join(vdir, f"{version}.json"), encoding="utf-8"))
    lib_dir = os.path.join(game_dir, "libraries")

    seen = set()
    parts = []
    version_jar = os.path.join(vdir, f"{version}.jar")
    parts.append(version_jar)
    seen.add(os.path.normpath(version_jar))

    for lib in libs_json["libraries"]:
        if lib.get("natives"):
            continue
        path = lib.get("downloads", {}).get("artifact", {}).get("path")
        if path:
            full = os.path.normpath(os.path.join(lib_dir, path))
            if full not in seen and os.path.isfile(full):
                parts.append(full)
                seen.add(full)

    for root, _, files in os.walk(lib_dir):
        for f in files:
            if not f.endswith(".jar"):
                continue
            full = os.path.normpath(os.path.join(root, f))
            if full not in seen:
                parts.append(full)
                seen.add(full)

    fl = os.path.join(lib_dir, "net", "fabricmc", "fabric-loader", "0.19.3",
                      "fabric-loader-0.19.3.jar")
    if os.path.isfile(fl) and os.path.normpath(fl) not in seen:
        parts.append(fl)
        seen.add(os.path.normpath(fl))

    out = "\n".join(parts) + "\n"
    sys.stdout.write(out)


if __name__ == "__main__":
    main()
