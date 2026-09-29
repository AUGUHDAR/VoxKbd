# -*- coding: utf-8 -*-
"""把已发布的 v1.1.2-beta Release 改名为 1.0.2（标题 + tag），并删掉旧 tag。

用法：
    python gh_relabel.py --from 1.1.2-beta --to 1.0.2 --dry-run
    python gh_relabel.py --from 1.1.2-beta --to 1.0.2

凭据走 git credential（与 gh_release.py 相同）。
"""
import argparse
import json
import re
import subprocess
import sys
import urllib.error
import urllib.request

OWNER_REPO = "AUGUHDAR/VoxKbd"
API = "https://api.github.com"


def gh_token():
    p = subprocess.run(["git", "credential", "fill"],
                       input="protocol=https\nhost=github.com\n\n",
                       capture_output=True, text=True)
    for line in p.stdout.splitlines():
        if line.startswith("password="):
            return line.split("=", 1)[1]
    sys.exit("拿不到 github.com 凭证")


def call(method, url, token, payload=None):
    data = json.dumps(payload).encode() if payload is not None else None
    req = urllib.request.Request(url, data=data, method=method, headers={
        "Authorization": "token " + token,
        "Accept": "application/vnd.github+json",
        "User-Agent": "voxkbd-relabel",
        **({"Content-Type": "application/json"} if data else {}),
    })
    try:
        with urllib.request.urlopen(req, timeout=60) as r:
            body = r.read().decode()
            return r.status, (json.loads(body) if body else None)
    except urllib.error.HTTPError as e:
        return e.code, e.read().decode()


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--from", dest="src", required=True, help="旧版本号（不带 v 前缀）")
    ap.add_argument("--to", dest="dst", required=True, help="新版本号（不带 v 前缀）")
    ap.add_argument("--dry-run", action="store_true")
    a = ap.parse_args()
    token = gh_token()

    status, rel = call("GET", f"{API}/repos/{OWNER_REPO}/releases/tags/v{a.src}", token)
    if status != 200:
        sys.exit(f"找不到 Release v{a.src}: {status} {rel}")
    rid = rel["id"]
    body = (rel.get("body") or "").replace(a.src, a.dst)
    print(f"release id={rid} 资产 {len(rel.get('assets', []))} 个 标题={rel['name']!r} tag={rel['tag_name']!r}")
    if a.dry_run:
        print("（dry-run 未修改）")
        return

    status, out = call("PATCH", f"{API}/repos/{OWNER_REPO}/releases/{rid}", token, {
        "tag_name": f"v{a.dst}",
        "name": a.dst,
        "body": body,
    })
    print("改标题/tag:", status, out if status >= 300 else "OK")

    status, out = call("DELETE", f"{API}/repos/{OWNER_REPO}/git/refs/tags/v{a.src}", token)
    print("删旧 tag:", status, out if status >= 300 else "OK")


if __name__ == "__main__":
    main()
