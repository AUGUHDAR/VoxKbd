# -*- coding: utf-8 -*-
"""在 GitHub 上创建 Release 并挂上 成品/ 里的全部 jar。

用法（任意 Python 3.9+，只用标准库）：
    python gh_release.py --version 1.0.1 --dry-run
    python gh_release.py --version 1.0.1

凭据：不读文件。直接用 git 已保存的 github.com 凭证（git credential fill），
所以本机 git 能 push 就能发 Release，不需要额外配 token。

要点：
- tag = v<版本>，Release 标题 = <版本>，与 v1.0.0 的惯例一致。
- 已存在的 Release 不会重复创建；同名 asset 会先删掉再传（可重跑）。
- Release 正文默认取 tools/publish/changelog-<版本>.md。
"""
import argparse
import glob
import json
import os
import subprocess
import sys
import urllib.error
import urllib.request

HERE = os.path.dirname(os.path.abspath(__file__))
REPO = os.path.abspath(os.path.join(HERE, "..", ".."))
OUT_DIR = os.path.join(REPO, "成品")
OWNER_REPO = "AUGUHDAR/VoxKbd"
API = "https://api.github.com"
UPLOADS = "https://uploads.github.com"


def gh_token():
    p = subprocess.run(["git", "credential", "fill"],
                       input="protocol=https\nhost=github.com\n\n",
                       capture_output=True, text=True, cwd=REPO)
    for line in p.stdout.splitlines():
        if line.startswith("password="):
            return line.split("=", 1)[1]
    sys.exit("拿不到 github.com 凭证（先 git push 一次确认本机已登录）")


def api(method, url, token, data=None, ctype="application/json", raw=None):
    body = raw if raw is not None else (json.dumps(data).encode() if data is not None else None)
    req = urllib.request.Request(url, data=body, method=method, headers={
        "Authorization": "token " + token,
        "Accept": "application/vnd.github+json",
        "User-Agent": "VoxKbd-release",
        "Content-Type": ctype,
    })
    try:
        with urllib.request.urlopen(req, timeout=300) as r:
            return r.status, json.loads(r.read().decode() or "{}")
    except urllib.error.HTTPError as e:
        detail = e.read().decode("utf-8", "replace")[:300]
        return e.code, {"_error": detail}


def main():
    ap = argparse.ArgumentParser(description="Vox Kbd GitHub Release")
    ap.add_argument("--version", required=True)
    ap.add_argument("--body", default=None, help="正文文件（默认 tools/publish/changelog-<版本>.md）")
    ap.add_argument("--dry-run", action="store_true")
    args = ap.parse_args()

    tag = "v" + args.version
    body_path = args.body or os.path.join(HERE, f"changelog-{args.version}.md")
    with open(body_path, "r", encoding="utf-8") as f:
        body = f.read()

    jars = sorted(glob.glob(os.path.join(OUT_DIR, "*", f"voxkbd-{args.version}-*.jar")))
    if not jars:
        sys.exit(f"成品/ 下没找到版本为 {args.version} 的 jar")
    print(f"tag={tag}  附件 {len(jars)} 个  正文={os.path.basename(body_path)}")
    for j in jars:
        print("   ", os.path.relpath(j, REPO))

    if args.dry_run:
        print("\n（dry-run 未创建）")
        return

    token = gh_token()

    st, rel = api("GET", f"{API}/repos/{OWNER_REPO}/releases/tags/{tag}", token)
    if st == 200:
        print(f"\n[已存在] release {tag} id={rel['id']}，只补传缺失的附件")
    else:
        st, rel = api("POST", f"{API}/repos/{OWNER_REPO}/releases", token, data={
            "tag_name": tag, "name": args.version, "body": body,
            "draft": False, "prerelease": False, "target_commitish": "main",
        })
        if st not in (200, 201):
            sys.exit(f"创建 Release 失败 HTTP {st}: {rel}")
        print(f"\n[已创建] release {tag} id={rel['id']}")

    rid = rel["id"]
    have = {a["name"]: a["id"] for a in rel.get("assets", [])}

    ok = fail = 0
    for j in jars:
        name = os.path.basename(j)
        if name in have:
            api("DELETE", f"{API}/repos/{OWNER_REPO}/releases/assets/{have[name]}", token)
        with open(j, "rb") as fh:
            raw = fh.read()
        st, res = api("POST",
                      f"{UPLOADS}/repos/{OWNER_REPO}/releases/{rid}/assets?name={name}",
                      token, ctype="application/java-archive", raw=raw)
        if st in (200, 201):
            ok += 1
            print(f"  [OK] {name} ({len(raw)//1024} KB)")
        else:
            fail += 1
            print(f"  [FAIL {st}] {name}: {res}")

    print(f"\n完成：{ok} 成功，{fail} 失败")
    print(f"https://github.com/{OWNER_REPO}/releases/tag/{tag}")
    if fail:
        sys.exit(1)


if __name__ == "__main__":
    main()
