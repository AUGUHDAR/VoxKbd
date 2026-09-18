#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""Integrity verification for the built Vox Kbd jars.

Checks every target in port_gen.TARGETS against the jar in 成品/<Loader>/: name, loader metadata,
mod version, mixin config, key classes and bytecode level. Usage: python tools/port_verify.py
Exits 1 on any failure.
"""
import json
import os
import re
import sys
import zipfile

HERE = os.path.dirname(os.path.abspath(__file__))
ROOT = os.path.dirname(HERE)
sys.path.insert(0, HERE)
import port_gen  # noqa: E402

problems = []

# Core classes every jar must contain.
COMMON_CLASSES = [
    "com/voxkbd/mod/ModRuntime.class",
    "com/voxkbd/core/key/KeycodeTable.class",
    "com/voxkbd/core/naming/VoxKbdNames.class",
    "com/voxkbd/mod/glfw/GlfwDeliverer.class",
    "com/voxkbd/mod/glfw/KeybindingRegistry.class",
    "com/voxkbd/mod/lock/LockManager.class",
    "com/voxkbd/mod/switch_/SwitchManager.class",
    "com/voxkbd/mod/ui/MasterScreen.class",
    "com/voxkbd/mod/ui/ConfigScreen.class",
    "com/voxkbd/mod/ui/KeyboardEditorScreen.class",
    "com/voxkbd/mixin/KeyMappingAccessor.class",
    "com/voxkbd/mixin/VoxKbdHudMixin.class",
    "assets/voxkbd/lang/zh_cn.json",
    "assets/voxkbd/lang/en_us.json",
]

PLATFORM_CLASS = {
    "fabric": "com/voxkbd/mod/platform/fabric/VoxKbdFabric.class",
    "forge": "com/voxkbd/mod/platform/forge/VoxKbdForge.class",
    "neoforge": "com/voxkbd/mod/platform/neoforge/VoxKbdNeoForge.class",
}

# era -> expected capture backend class
BACKEND = {
    "legacy": "com/voxkbd/daemon/input/WindowsKeyboardHookJna.class",
    "modern": "com/voxkbd/daemon/input/WindowsKeyboardHook.class",
}


def manifest_value(raw, key):
    for line in raw.decode("utf-8", "replace").splitlines():
        if line.startswith(key + ":"):
            return line.split(":", 1)[1].strip()
    return None


def check(key):
    t = port_gen.TARGETS[key]
    want = port_gen.jar_name(t)
    path = os.path.join(ROOT, "成品", port_gen.DISPLAY[t["loader"]], want)
    if not os.path.isfile(path):
        problems.append(f"{key}: missing {path}")
        return
    expected_version = f"{port_gen.MOD_VERSION}-{t['group']}-{t['loader']}"

    try:
        with zipfile.ZipFile(path) as z:
            names = set(z.namelist())

            # --- mixin config
            if "voxkbd.mixins.json" not in names:
                problems.append(f"{key}: voxkbd.mixins.json missing")
            else:
                cfg = json.loads(z.read("voxkbd.mixins.json").decode("utf-8"))
                want_mixins = {"KeyMappingAccessor", "VoxKbdHudMixin"}
                if t["loader"] == "fabric" and not t.get("sdl"):
                    want_mixins.add("VoxKbdKeyCallbackMixin")
                if set(cfg.get("client", [])) != want_mixins:
                    problems.append(f"{key}: mixin list {cfg.get('client')} != {sorted(want_mixins)}")

            # --- loader metadata
            if t["loader"] == "fabric":
                if "fabric.mod.json" not in names:
                    problems.append(f"{key}: fabric.mod.json missing")
                else:
                    fmj = json.loads(z.read("fabric.mod.json").decode("utf-8"))
                    if fmj.get("version") != expected_version:
                        problems.append(f"{key}: version {fmj.get('version')} != {expected_version}")
                    if fmj.get("depends", {}).get("minecraft") != t["mc_range"]:
                        problems.append(f"{key}: mc range {fmj.get('depends', {}).get('minecraft')}")
                    ep = fmj.get("entrypoints", {}).get("client")
                    if ep != ["com.voxkbd.mod.platform.fabric.VoxKbdFabric"]:
                        problems.append(f"{key}: entrypoint {ep}")
            elif t["loader"] == "forge":
                if "META-INF/mods.toml" not in names:
                    problems.append(f"{key}: mods.toml missing")
                else:
                    toml = z.read("META-INF/mods.toml").decode("utf-8")
                    if f'version = "{port_gen.MOD_VERSION}"' not in toml:
                        problems.append(f"{key}: mods.toml version != {port_gen.MOD_VERSION}")
                    if t["mc_range"] not in toml:
                        problems.append(f"{key}: mods.toml mc range {t['mc_range']} missing")
                mf = manifest_value(z.read("META-INF/MANIFEST.MF"), "Implementation-Version")
                if mf != expected_version:
                    problems.append(f"{key}: Implementation-Version {mf} != {expected_version}")
            else:
                toml_name = port_gen.neo_toml_name(t)
                if f"META-INF/{toml_name}" not in names:
                    problems.append(f"{key}: {toml_name} missing")
                else:
                    toml = z.read(f"META-INF/{toml_name}").decode("utf-8")
                    if f'version = "{port_gen.MOD_VERSION}"' not in toml:
                        problems.append(f"{key}: {toml_name} version != {port_gen.MOD_VERSION}")
                    if t["mc_range"] not in toml:
                        problems.append(f"{key}: {toml_name} mc range {t['mc_range']} missing")
                for other in ("mods.toml", "neoforge.mods.toml"):
                    if other != toml_name and f"META-INF/{other}" in names:
                        problems.append(f"{key}: unexpected stale META-INF/{other}")

            # --- classes
            for cls in COMMON_CLASSES + [PLATFORM_CLASS[t["loader"]]]:
                if cls not in names:
                    problems.append(f"{key}: {cls} missing")
            backend = BACKEND[t["era"]]
            if backend not in names:
                problems.append(f"{key}: capture backend {backend} missing")

            # --- bytecode level (major = release + 44)
            probe = "com/voxkbd/mod/ModRuntime.class"
            if probe in names:
                major = int.from_bytes(z.read(probe)[6:8], "big")
                want_major = t["release"] + 44
                if major != want_major:
                    problems.append(
                        f"{key}: bytecode major {major} != {want_major} (release {t['release']})")
    except zipfile.BadZipFile as e:
        problems.append(f"{key}: bad zip {e}")


def main():
    for key in port_gen.TARGETS:
        check(key)

    counts = {}
    flat = os.path.join(ROOT, "成品")
    for display in ("Fabric", "Forge", "NeoForge"):
        d = os.path.join(flat, display)
        if not os.path.isdir(d):
            counts[display] = 0
            continue
        jars = [f for f in os.listdir(d) if f.endswith(".jar")]
        counts[display] = len(jars)
        unexpected = [f for f in jars if not re.fullmatch(
            r"voxkbd-%s-.+-(fabric|forge|neoforge)\.jar" % re.escape(port_gen.MOD_VERSION), f)]
        if unexpected:
            problems.append(f"成品/{display}: unexpected jars {unexpected}")

    expect = {}
    for t in port_gen.TARGETS.values():
        expect[port_gen.DISPLAY[t["loader"]]] = expect.get(port_gen.DISPLAY[t["loader"]], 0) + 1
    for display, exp in expect.items():
        print(f"成品/{display}: {counts.get(display, 0)} jar(s), expected {exp}")
        if counts.get(display, 0) != exp:
            problems.append(f"成品/{display}: {counts.get(display, 0)} jars != {exp}")

    if problems:
        print(f"\n{len(problems)} PROBLEM(S):")
        for p in problems:
            print(" -", p)
        sys.exit(1)
    print(f"\nALL {len(port_gen.TARGETS)} TARGET JARS VERIFIED")


if __name__ == "__main__":
    main()
