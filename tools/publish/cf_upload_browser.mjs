// cf_upload_browser.mjs — 在真实浏览器里上传 CurseForge 文件
//
// 为什么走浏览器：upload-file 这个路径会被 Cloudflare 的 managed challenge 拦（HTTP 403,
// "Just a moment..."），Python 直连拿不到放行。浏览器过盾后，在页面上下文里对
// authors-old.curseforge.com 发同源 fetch，请求会带上挑战通过后的会话指纹。
//
// 依赖：playwright-core（用本机已装的 Chrome / Edge，不下载浏览器）
//
// 用法：
//   VOXKBD_NODE_MODULES=<managed>/workspace/node_modules node tools/publish/cf_upload_browser.mjs \
//       --version 1.0.1 --release beta [--dry-run] [--only <jar文件名>]
//
// 凭据：读同目录的 config.json（curseforge.project_id / curseforge.upload_token）。

import { existsSync, readFileSync, readdirSync, statSync, writeFileSync } from "node:fs";
import { dirname, join } from "node:path";
import { fileURLToPath, pathToFileURL } from "node:url";

const HERE = dirname(fileURLToPath(import.meta.url));
const REPO = dirname(dirname(HERE));
const OUT_DIR = join(REPO, "成品");
const MOD_STEM = "voxkbd";
const LOADERS = { fabric: "Fabric", forge: "Forge", neoforge: "NeoForge" };

const mods = process.env.VOXKBD_NODE_MODULES;
const pw = mods
  ? await import(pathToFileURL(join(mods, "playwright-core", "index.js")).href)
  : await import("playwright-core");
const chromium = pw.chromium ?? pw.default?.chromium;
if (!chromium) {
  console.error("playwright-core 没导出 chromium。");
  process.exit(1);
}

// ---------------------------------------------------------------- args
const argv = process.argv.slice(2);
const arg = (k, d = null) => {
  const i = argv.indexOf(k);
  return i >= 0 ? argv[i + 1] : d;
};
const has = (k) => argv.includes(k);
const version = arg("--version");
const release = arg("--release", "beta");
const only = arg("--only");
const dryRun = has("--dry-run");
if (!version) {
  console.error("用法: node cf_upload_browser.mjs --version 1.0.1 --release beta [--dry-run]");
  process.exit(1);
}

// ---------------------------------------------------------------- plan
// 与 cf_upload.py 的 expand_mc_range 同规则，改动需两边同步。
function expandMcRange(dir) {
  if (!dir.includes("_")) return [dir];
  const [a, b] = dir.split("_");
  const pa = a.split(".");
  const pb = b.split(".");
  if (pa.length === 3 && pb.length === 3) {
    const out = [];
    for (let i = +pa[2]; i <= +pb[2]; i++) out.push(`${pa[0]}.${pa[1]}.${i}`);
    return out;
  }
  if (pa.length === 2 && pb.length === 3) {
    const out = [a];
    for (let i = 1; i <= +pb[2]; i++) out.push(`${pa[0]}.${pa[1]}.${i}`);
    return out;
  }
  throw new Error(`无法解析版本区间: ${dir}`);
}

const jars = [];
for (const loaderDir of readdirSync(OUT_DIR)) {
  const full = join(OUT_DIR, loaderDir);
  if (!statSync(full).isDirectory()) continue;
  for (const f of readdirSync(full).sort()) {
    if (!f.startsWith(`${MOD_STEM}-${version}-`) || !f.endsWith(".jar")) continue;
    if (only && f !== only) continue;
    const stem = f.slice(`${MOD_STEM}-${version}-`.length, -4);
    const idx = stem.lastIndexOf("-");
    const mcDir = stem.slice(0, idx);
    const lk = stem.slice(idx + 1).toLowerCase();
    if (!LOADERS[lk]) {
      console.log("  [跳过] 未知加载器:", f);
      continue;
    }
    jars.push({ path: join(full, f), name: f, loader: LOADERS[lk], versions: expandMcRange(mcDir) });
  }
}
if (!jars.length) {
  console.error(`成品/ 下没找到版本为 ${version} 的 jar`);
  process.exit(1);
}
console.log(`tag=1.0.1  共 ${jars.length} 个 jar  releaseType=${release}`);
for (const j of jars) console.log(`   ${j.name}  ->  ${j.loader} ${j.versions.join(",")}`);
if (dryRun) {
  console.log("\n（dry-run 未上传）");
  process.exit(0);
}

// ---------------------------------------------------------------- creds
const cfg = JSON.parse(readFileSync(join(HERE, "config.json"), "utf-8"));
const pid = cfg.curseforge.project_id;
const token = cfg.curseforge.upload_token;
const coreKey = cfg.curseforge.api_key;
let changelog = "";
const clPath = join(HERE, `changelog-${version}.md`);
if (existsSync(clPath)) changelog = readFileSync(clPath, "utf-8");

// ---------------------------------------------------------------- dedup
// Core API（api.curseforge.com）没有盾，可以直接拉项目已有文件名。
// 只读，失败不阻塞上传。
async function existingDisplayNames() {
  if (!coreKey || coreKey.includes("获取")) return new Set();
  const names = new Set();
  try {
    for (let index = 0; ; index += 100) {
      const r = await fetch(
        `https://api.curseforge.com/v1/mods/${pid}/files?index=${index}&pageSize=100`,
        { headers: { "x-api-key": coreKey, Accept: "application/json" } });
      if (!r.ok) break;
      const data = (await r.json()).data ?? [];
      for (const f of data) names.add(f.displayName);
      if (data.length < 100) break;
    }
  } catch (e) {
    console.log("  [警告] 去重查询失败:", String(e).split("\n")[0]);
  }
  return names;
}

const already = await existingDisplayNames();
if (already.size) console.log(`\n[去重] Core API 可见 ${already.size} 个文件`);

// Core API 对新上传的文件有缓存延迟（审核队列里的看不到），所以再叠一份本地完成清单。
// 重跑时靠它跳过，避免同一个 jar 传两遍。
const DONE_FILE = join(HERE, "_cf_done.json");
let done = [];
if (existsSync(DONE_FILE)) {
  try {
    done = JSON.parse(readFileSync(DONE_FILE, "utf-8"));
  } catch {
    done = [];
  }
}
console.log(`[去重] 本地已传 ${done.length} 个`);

const todo = jars.filter((j) => !already.has(j.name) && !done.includes(j.name));
for (const j of jars) {
  if (already.has(j.name)) console.log(`  [跳过-项目已有] ${j.name}`);
  else if (done.includes(j.name)) console.log(`  [跳过-本地已传] ${j.name}`);
}
if (!todo.length) {
  console.log("全部已存在，无需上传。");
  process.exit(0);
}

// ---------------------------------------------------------------- browser
const CHROME = [
  "C:/Program Files/Google/Chrome/Application/chrome.exe",
  "C:/Program Files (x86)/Google/Chrome/Application/chrome.exe",
  "C:/Program Files (x86)/Microsoft/Edge/Application/msedge.exe",
  "C:/Program Files/Microsoft/Edge/Application/msedge.exe",
].find((p) => existsSync(p));
if (!CHROME) {
  console.error("没找到 Chrome / Edge。");
  process.exit(1);
}

const ctx = await chromium.launchPersistentContext(join(HERE, "_cf_profile"), {
  executablePath: CHROME,
  headless: false,
  viewport: { width: 1280, height: 900 },
});
const page = ctx.pages()[0] ?? (await ctx.newPage());
page.setDefaultTimeout(20000);

console.log("\n打开 authors-old.curseforge.com 过盾 ...");
try {
  await page.goto("https://authors-old.curseforge.com/", { waitUntil: "domcontentloaded", timeout: 60000 });
} catch (e) {
  console.log("导航提示（可忽略）:", String(e).split("\n")[0]);
}
await page.waitForTimeout(4000);

const CHUNK = 600000;
let ok = 0;
let fail = 0;

for (const j of todo) {
  const b64 = readFileSync(j.path).toString("base64");
  await page.evaluate(() => {
    window.__parts = [];
    window.__up = { done: false };
    return "reset";
  });
  for (let i = 0; i < b64.length; i += CHUNK) {
    await page.evaluate((piece) => {
      window.__parts.push(piece);
      return window.__parts.length;
    }, b64.slice(i, i + CHUNK));
  }

  const meta = {
    displayName: j.name,
    releaseType: release,
    gameVersionNames: ["Client", j.loader, ...j.versions],
    changelog,
    changelogType: "markdown",
  };

  await page.evaluate((a) => {
    const full = window.__parts.join("");
    window.__parts = [];
    window.__up = { done: false };
    (async () => {
      try {
        const bytes = Uint8Array.from(atob(full), (c) => c.charCodeAt(0));
        const fd = new FormData();
        fd.append("metadata", JSON.stringify(a.meta));
        fd.append("file", new Blob([bytes], { type: "application/java-archive" }), a.name);
        const r = await fetch(new URL(`/api/projects/${a.pid}/upload-file`, location.origin), {
          method: "POST",
          headers: { "X-Api-Token": a.token, Accept: "application/json" },
          body: fd,
        });
        const text = await r.text();
        window.__up = { done: true, status: r.status, body: text.slice(0, 200) };
      } catch (e) {
        window.__up = { done: true, error: String(e) };
      }
    })();
    return "kicked";
  }, { meta, name: j.name, pid, token });

  let res = { error: "timeout 80s" };
  for (let i = 0; i < 40; i++) {
    await page.waitForTimeout(2000);
    const st = await page.evaluate(() =>
      window.__up && window.__up.done ? JSON.stringify(window.__up) : "pending");
    if (st !== "pending") {
      res = JSON.parse(st);
      break;
    }
  }

  const good = res.status === 200;
  if (good) {
    ok++;
    done.push(j.name);
    writeFileSync(DONE_FILE, JSON.stringify(done, null, 2));
    console.log(`  [OK] ${j.name}`);
  } else {
    fail++;
    console.log(`  [FAIL] ${j.name}  status=${res.status ?? "-"}  ${(res.body || res.error || "").slice(0, 160)}`);
    if (fail >= 3) {
      console.log("  连续失败，先停下来。");
      break;
    }
  }
}

console.log(`\n完成：${ok} 成功，${fail} 失败`);
await ctx.close();
if (fail) process.exit(1);
