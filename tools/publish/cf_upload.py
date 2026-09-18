# -*- coding: utf-8 -*-
"""Vox Kbd CurseForge (CF) 上传脚本。

用法（在 tools/publish 目录下，用系统 Python 3.14 跑，它带 requests）：
    C:/Python314/python.exe cf_upload.py --version 1.0.1 --release beta --dry-run
    C:/Python314/python.exe cf_upload.py --version 1.0.1 --release beta
    C:/Python314/python.exe cf_upload.py --version 1.0.1 --release beta --loader fabric

凭据：config.json
    curseforge.project_id   项目数字 ID（VoxKbd = 1674735）
    curseforge.upload_token 老门户上传 token（UUID，authors-old.curseforge.com/account/api-tokens）
    curseforge.api_key      console 的 Core API key（只用于查询已有文件做去重，不能上传）

────────────────────────────────────────────────────────────────────────────
血泪经验（VoxLink 发布时踩出来的，别删）：

1. CurseForge 有两套 API：
   - console.curseforge.com 的 Core API（api.curseforge.com，x-api-key 头）只能读，
     上传端点一律 404。
   - 上传必须走老门户：https://authors-old.curseforge.com/api/projects/{id}/upload-file，
     认证头是 X-Api-Token（注意不是 x-api-key），token 是 UUID。
2. displayName 惯例 = jar 文件名本身（如 voxkbd-1.0.1-1.21.11-fabric.jar），不要自创格式。
3. 一个 jar 对应多个 MC 版本（目录名 1.21_1.21.5 表示 1.21~1.21.5 逐个列出），
   gameVersionNames 写版本名即可；但必须包含加载器名（Fabric/Forge/NeoForge）+ "Client"
   （Environment 组缺它报 400:1021）。三段式区间要展开，别只填 "1.20"。
4. Python requests 直连 *.curseforge.com 会被 Cloudflare JS 盾拦（403）。
   过一个盾后把 UA + Cookie 存进 _cf_shield.txt，脚本会带上同款 UA（cf_clearance 绑定 UA+IP）。
5. 发布前先跑 --dry-run 核对 jar 清单和版本展开。
────────────────────────────────────────────────────────────────────────────
"""
import argparse
import glob
import json
import os
import sys

import force_ipv4  # noqa: F401 强制 IPv4
import requests

HERE = os.path.dirname(os.path.abspath(__file__))
REPO = os.path.abspath(os.path.join(HERE, "..", ".."))
OUT_DIR = os.path.join(REPO, "成品")
CF_LEGACY = "https://authors-old.curseforge.com"
CF_CORE = "https://api.curseforge.com/v1"
SHIELD_FILE = os.path.join(HERE, "_cf_shield.txt")

MOD_STEM = "voxkbd"
LOADERS = {"fabric": "Fabric", "forge": "Forge", "neoforge": "NeoForge"}


def expand_mc_range(mc_dir):
    """目录名版本区间 → 完整 MC 版本列表。

      1.21.11       -> ["1.21.11"]
      1.20_1.20.1   -> ["1.20", "1.20.1"]
      1.21_1.21.5   -> ["1.21", "1.21.1" ... "1.21.5"]
      1.20.2_1.20.6 -> ["1.20.2" ... "1.20.6"]
      26.1_26.1.2   -> ["26.1", "26.1.1", "26.1.2"]
    """
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
        items.append({"path": p, "name": name, "loader": lk,
                      "mc_dir": mc_dir, "versions": expand_mc_range(mc_dir)})
    return items


def load_config():
    path = os.path.join(HERE, "config.json")
    if not os.path.exists(path):
        sys.exit("缺少 config.json（参考 config.example.json）")
    with open(path, "r", encoding="utf-8") as f:
        return json.load(f)


def existing_display_names(cfg, project_id):
    """用 Core API 拉项目全部文件名做去重。"""
    key = cfg["curseforge"].get("api_key", "")
    if not key or "获取" in key:
        return set()
    names = set()
    index = 0
    try:
        while True:
            r = requests.get(f"{CF_CORE}/mods/{project_id}/files", headers={
                "x-api-key": key, "Accept": "application/json"},
                params={"index": index, "pageSize": 100}, timeout=60)
            r.raise_for_status()
            data = r.json()["data"]
            names.update(f["displayName"] for f in data)
            if len(data) < 100:
                break
            index += 100
    except Exception as e:
        print(f"  [警告] 去重查询失败（{e}），将继续上传")
    return names


def load_shield():
    """读取浏览器过盾后的 UA + Cookie（_cf_shield.txt: 行1 UA，行2 完整 Cookie 头）。
    Cloudflare 的 cf_clearance 绑定 UA+IP，Python 侧必须用同款 UA 才能复用。"""
    if not os.path.exists(SHIELD_FILE):
        return None, {}
    with open(SHIELD_FILE, "r", encoding="utf-8") as f:
        lines = [l.strip() for l in f.read().splitlines() if l.strip()]
    if len(lines) < 2:
        return None, {}
    ua, cookie = lines[0], lines[1]
    jar = {}
    for part in cookie.replace("Cookie:", "").split(";"):
        if "=" in part:
            k, v = part.split("=", 1)
            jar[k.strip()] = v.strip()
    return ua, jar


def main():
    ap = argparse.ArgumentParser(description="Vox Kbd CurseForge 上传")
    ap.add_argument("--version", required=True, help="mod 版本，如 1.0.1")
    ap.add_argument("--release", choices=["release", "beta", "alpha"], default="beta")
    ap.add_argument("--changelog", default=None, help="changelog 文件（默认 changelog-<version>.md）")
    ap.add_argument("--loader", choices=["fabric", "forge", "neoforge"], default=None)
    ap.add_argument("--dry-run", action="store_true")
    args = ap.parse_args()

    cfg = load_config()
    project_id = cfg["curseforge"]["project_id"]
    token = cfg["curseforge"]["upload_token"]
    if not token:
        sys.exit("config.json 缺 curseforge.upload_token（authors-old 门户生成的 UUID token）")

    changelog_file = args.changelog or f"changelog-{args.version}.md"
    with open(os.path.join(HERE, changelog_file), "r", encoding="utf-8") as f:
        changelog = f.read()

    jars = collect_jars(args.version, args.loader)
    if not jars:
        sys.exit(f"成品/ 下没找到版本为 {args.version} 的 jar")
    print(f"共 {len(jars)} 个 jar，releaseType={args.release}\n")

    already = existing_display_names(cfg, project_id)
    shield_ua, shield_cookies = load_shield()
    if shield_ua:
        print(f"[盾] 已加载 _cf_shield.txt ({len(shield_cookies)} cookies)")
    sess = requests.Session()
    sess.cookies.update(shield_cookies)

    for j in jars:
        game_versions = ["Client", j["loader"]] + j["versions"]
        meta = {
            # displayName 惯例 = jar 文件名（不要改格式）
            "displayName": j["name"],
            "releaseType": args.release,
            "gameVersionNames": game_versions,
            "changelog": changelog,
            "changelogType": "markdown",
        }
        plan = f"{j['name']}  ->  {j['loader']} {j['versions']}"
        if j["name"] in already:
            print(f"  [跳过-已存在] {plan}")
            continue
        if args.dry_run:
            print(f"  [计划] {plan}")
            continue

        with open(j["path"], "rb") as fh:
            r = sess.post(
                f"{CF_LEGACY}/api/projects/{project_id}/upload-file",
                headers={"X-Api-Token": token, "Accept": "application/json",
                         "User-Agent": shield_ua or "Mozilla/5.0 (VoxKbd publisher)"},
                files={"metadata": (None, json.dumps(meta), "application/json"),
                       "file": (j["name"], fh, "application/java-archive")},
                timeout=600,
            )
        if r.status_code == 403:
            print(f"  [失败] {j['name']}: 被 Cloudflare 盾拦截。\n"
                  "         用浏览器打开 authors-old.curseforge.com 过盾，把 UA 与 Cookie"
                  " 写进 tools/publish/_cf_shield.txt（行1 UA，行2 Cookie），再重跑。")
            sys.exit(1)
        ok = r.status_code == 200
        detail = ""
        if ok:
            detail = "OK #" + str(r.json().get("id"))
        else:
            detail = f"FAIL {r.status_code} {r.text[:150]}"
        print(f"  [{detail}] {plan}")
        if not ok:
            sys.exit(1)

    print("\n完成。文件会先进入人工审核（Under Manual Review），通过后自动出现在下载页。"
          if not args.dry_run else "\n（dry-run 未实际上传）")


if __name__ == "__main__":
    main()
