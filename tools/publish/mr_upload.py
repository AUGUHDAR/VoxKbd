# -*- coding: utf-8 -*-
"""Vox Kbd Modrinth (MR) 上传脚本。

用法（在 tools/publish 目录下，用系统 Python 3.14 跑，它带 requests）：
    C:/Python314/python.exe mr_upload.py --version 1.0.1 --release beta --dry-run
    C:/Python314/python.exe mr_upload.py --version 1.0.1 --release beta
    C:/Python314/python.exe mr_upload.py --version 1.0.1 --release beta --loader fabric

凭据：config.json
    modrinth.token        MR 个人 token（mrp_ 开头，modrinth.com/settings/pats 生成，需要 Write 权限）
    modrinth.project_id   VoxKbd = "IGxAOAYv"

要点：
- 每个 jar 一个独立 version：
    version_number = mod 版本（如 1.0.1）
    name（副标题）  = "VoxKbd-{Loader}-{版本}"
- game_versions 挂完整区间（1.21_1.21.5 → 1.21~1.21.5 全列），与 CF 口径一致。
- 重复防护：上传前拉项目已有 version，相同 version_number + 相同文件名则跳过。
- MR 没有 Cloudflare 盾，requests 直连即可。
"""
import argparse
import glob
import json
import os
import sys

import force_ipv4  # noqa: F401 强制 IPv4（国内 IPv6 TLS 握手卡死）
import requests

HERE = os.path.dirname(os.path.abspath(__file__))
REPO = os.path.abspath(os.path.join(HERE, "..", ".."))
OUT_DIR = os.path.join(REPO, "成品")
MR_API = "https://api.modrinth.com/v2"

MOD_STEM = "voxkbd"
MOD_NAME = "VoxKbd"
LOADERS = {"fabric": "Fabric", "forge": "Forge", "neoforge": "NeoForge"}


def expand_mc_range(mc_dir):
    """目录名版本区间 → 完整 MC 版本列表（与 cf_upload.py 同规则，改动需两边同步）。"""
    if "_" not in mc_dir:
        return [mc_dir]
    a, b = mc_dir.split("_")
    pa, pb = a.split("."), b.split(".")
    if len(pa) == len(pb) == 3:
        return [f"{pa[0]}.{pa[1]}.{i}" for i in range(int(pa[2]), int(pb[2]) + 1)]
    if len(pa) == 2 and len(pb) == 3:
        return [a] + [f"{pa[0]}.{pa[1]}.{i}" for i in range(1, int(pb[2]) + 1)]
    raise ValueError(f"无法解析版本区间: {mc_dir}")


def collect_jars(version, only_loader=None):
    items = []
    for p in sorted(glob.glob(os.path.join(OUT_DIR, "*", f"{MOD_STEM}-{version}-*.jar"))):
        name = os.path.basename(p)
        stem = name[len(MOD_STEM) + len(version) + 2: -len(".jar")]
        mc_dir, _, loader = stem.rpartition("-")
        lk = LOADERS.get(loader.lower())
        if not lk:
            print(f"  [跳过] 未知加载器: {name}")
            continue
        if only_loader and loader.lower() != only_loader:
            continue
        items.append({"path": p, "name": name, "loader_key": loader.lower(),
                      "versions": expand_mc_range(mc_dir)})
    return items


def load_config():
    path = os.path.join(HERE, "config.json")
    if not os.path.exists(path):
        sys.exit("缺少 config.json（参考 config.example.json）")
    with open(path, "r", encoding="utf-8") as f:
        return json.load(f)


def resolve_project_id(headers, pid):
    """允许填 slug 或 ID，统一解析成项目 ID。未过审的项目也能按 ID 解析。"""
    if len(pid) == 8 and pid.isalnum():
        return pid
    r = requests.get(f"{MR_API}/project/{pid}", headers=headers, timeout=30)
    r.raise_for_status()
    return r.json()["id"]


def existing_versions(headers, project_id):
    """已有 version 列表：[(version_number, {文件名})]，用于去重。"""
    r = requests.get(f"{MR_API}/project/{project_id}/version", headers=headers, timeout=60)
    if r.status_code == 404:
        return []
    r.raise_for_status()
    out = []
    for v in r.json():
        names = set()
        for f in v.get("files", []):
            fn = f.get("filename") if isinstance(f, dict) else f
            if fn:
                names.add(fn)
        out.append((v["version_number"], names))
    return out


def main():
    ap = argparse.ArgumentParser(description="Vox Kbd Modrinth 上传")
    ap.add_argument("--version", required=True, help="mod 版本，如 1.0.1")
    ap.add_argument("--release", choices=["release", "beta", "alpha"], default="beta",
                    help="version_type，默认 beta")
    ap.add_argument("--changelog", default=None, help="changelog 文件（默认 changelog-<version>.md）")
    ap.add_argument("--loader", choices=["fabric", "forge", "neoforge"], default=None)
    ap.add_argument("--dry-run", action="store_true")
    args = ap.parse_args()

    cfg = load_config()
    headers = {
        "Authorization": cfg["modrinth"]["token"],
        "User-Agent": f"{MOD_NAME}-publisher/1.0 (github.com/AUGUHDAR/{MOD_NAME})",
    }

    # 先确认 token 可用，避免上传到一半才发现是 401。
    probe = requests.get(f"{MR_API}/user", headers=headers, timeout=30)
    if probe.status_code != 200:
        sys.exit(f"[MR] token 不可用（HTTP {probe.status_code}）。"
                 "去 modrinth.com/settings/pats 重新生成，填进 config.json 的 modrinth.token。")

    pid = resolve_project_id(headers, cfg["modrinth"]["project_id"])
    print(f"[MR] 项目 ID: {pid}")

    changelog_file = args.changelog or f"changelog-{args.version}.md"
    with open(os.path.join(HERE, changelog_file), "r", encoding="utf-8") as f:
        changelog = f.read()

    jars = collect_jars(args.version, args.loader)
    if not jars:
        sys.exit(f"成品/ 下没找到版本为 {args.version} 的 jar")
    print(f"共 {len(jars)} 个 jar，version_type={args.release}\n")

    existing = [] if args.dry_run else existing_versions(headers, pid)

    for j in jars:
        data = {
            "name": f"{MOD_NAME}-{LOADERS[j['loader_key']]}-{args.version}",
            "version_number": args.version,
            "changelog": changelog,
            "dependencies": [],
            "game_versions": j["versions"],
            "version_type": args.release,
            "loaders": [j["loader_key"]],
            "featured": False,
            "project_id": pid,
            "client_side": "required",
            "server_side": "unsupported",
            "file_parts": [j["name"]],
            "primary_file": j["name"],
        }
        plan = f"{j['name']}  ->  {j['loader_key']} {j['versions']}"
        dup = any(vn == args.version and j["name"] in files for vn, files in existing)
        if dup:
            print(f"  [跳过-已存在] {plan}")
            continue
        if args.dry_run:
            print(f"  [计划] {plan}")
            continue

        with open(j["path"], "rb") as fh:
            r = requests.post(
                f"{MR_API}/version",
                headers=headers,
                data={"data": json.dumps(data)},
                files=[(j["name"], (j["name"], fh, "application/java-archive"))],
                timeout=600,
            )
        ok = r.status_code < 300
        print(f"  [{'OK' if ok else 'FAIL ' + str(r.status_code) + ' ' + r.text[:200]}] {plan}")
        if not ok:
            sys.exit(1)

    print("\n完成。" if not args.dry_run else "\n（dry-run 未实际上传）")


if __name__ == "__main__":
    main()
