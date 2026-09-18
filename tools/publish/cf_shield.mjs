// cf_shield.mjs — 过 CurseForge 的 Cloudflare 盾，把 UA + Cookie 存进 _cf_shield.txt
//
// 为什么要这样：上传接口在 authors-old.curseforge.com，Cloudflare 会拦 Python requests。
// 用真实浏览器过一次盾，拿到 cf_clearance，再让 cf_upload.py 带上同样的 UA + cookie 就能直连。
// cf_clearance 绑定 UA + IP，所以浏览器和上传必须跑在同一台机器上。
//
// 依赖：playwright-core（不下载浏览器，直接用本机已装的 Chrome）
//   node <managed>/node_modules/npm/bin/npm-cli.js install playwright-core --prefix <managed>/workspace
//
// 用法：
//   NODE_PATH=<managed>/workspace/node_modules node tools/publish/cf_shield.mjs
//
// 成功后写出的文件格式（两行）：
//   行1 = User-Agent
//   行2 = Cookie 头

import { writeFileSync, existsSync } from "node:fs";
import { dirname, join } from "node:path";
import { fileURLToPath, pathToFileURL } from "node:url";

const HERE = dirname(fileURLToPath(import.meta.url));
const OUT = join(HERE, "_cf_shield.txt");

// ESM 不认 NODE_PATH，用 VOXKBD_NODE_MODULES 指到装 playwright-core 的 node_modules。
// playwright-core 是 CJS，从 ESM import 时导出挂在 default 上，两边都取一下。
const mods = process.env.VOXKBD_NODE_MODULES;
const pw = mods
  ? await import(pathToFileURL(join(mods, "playwright-core", "index.js")).href)
  : await import("playwright-core");
const chromium = pw.chromium ?? pw.default?.chromium;
if (!chromium) {
  console.error("playwright-core 没导出 chromium，检查安装路径。");
  process.exit(1);
}

const CHROME_CANDIDATES = [
  "C:/Program Files/Google/Chrome/Application/chrome.exe",
  "C:/Program Files (x86)/Google/Chrome/Application/chrome.exe",
  "C:/Program Files (x86)/Microsoft/Edge/Application/msedge.exe",
  "C:/Program Files/Microsoft/Edge/Application/msedge.exe",
];

const exe = CHROME_CANDIDATES.find((p) => existsSync(p));
if (!exe) {
  console.error("没找到 Chrome / Edge，装一个再来。");
  process.exit(1);
}
console.log("浏览器:", exe);

const ctx = await chromium.launchPersistentContext(join(HERE, "_cf_profile"), {
  executablePath: exe,
  headless: false,
  viewport: { width: 1280, height: 900 },
});

const page = ctx.pages()[0] ?? (await ctx.newPage());
page.setDefaultTimeout(15000);

console.log("打开 authors-old.curseforge.com ...");
try {
  await page.goto("https://authors-old.curseforge.com/", { waitUntil: "domcontentloaded", timeout: 60000 });
} catch (e) {
  console.log("导航提示（可忽略）:", String(e).split("\n")[0]);
}

// Cloudflare 挑战页标题是 "Just a moment..."，过了之后会变成站点标题。
const deadline = Date.now() + 90000;
let title = "";
while (Date.now() < deadline) {
  try {
    title = await page.title();
  } catch {
    title = "";
  }
  if (title && !/just a moment|attention required|checking/i.test(title)) break;
  await page.waitForTimeout(2000);
}
console.log("页面标题:", title || "(空)");

// 挑战通过后 cf_clearance 才会出现在 cookie 里。
const cookies = await ctx.cookies("https://authors-old.curseforge.com/");
const clearance = cookies.find((c) => c.name === "cf_clearance");
if (!clearance) {
  console.error("没拿到 cf_clearance，盾没过。把窗口留给人手动点一下再重跑。");
  await ctx.close();
  process.exit(2);
}

const ua = await page.evaluate(() => navigator.userAgent);
const cookieHeader = cookies.map((c) => `${c.name}=${c.value}`).join("; ");
writeFileSync(OUT, `${ua}\n${cookieHeader}\n`, "utf-8");
console.log(`已写出 ${OUT}（${cookies.length} 个 cookie，UA 长度 ${ua.length}）`);

await ctx.close();
