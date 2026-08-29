"""
VoxKbd UI test: launch MC 26.2 directly via Java, straight into a singleplayer world.

1. Build the classpath (write to file, since MC has 130+ libs totalling >30k chars).
2. Build the @args file (everything after the classpath).
3. Launch Java with the classpath + Knot + args.
4. (Caller separately runs screenshot_voxkbd_ui.py to take the screenshot.)

Run this from Python: python tools/launch_mc_quickplay.py
"""

import os
import sys
import json
import time
import subprocess
import ctypes
import win32gui
import win32con
import pyautogui
from PIL import ImageGrab

# ---- config ----
GAME_DIR = r"D:\桌面\voxlink\客户端测试"
VERSION = "26.2-Fabric 0.19.3"
JAVA = r"C:\Program Files\Java\jdk-25.0.2\bin\java.exe"
WORLD = "新的世界"            # your existing world
NATIVES = os.path.join(GAME_DIR, "versions", VERSION, f"{VERSION}-natives")
ASSETS = os.path.join(GAME_DIR, "assets")
SAVES_DIR = os.path.join(GAME_DIR, "saves", WORLD)
WINDOW_TITLE = "Minecraft* 26.2"
SCREENSHOT_PATH = r"D:\桌面\VoxKbd\voxkbd_ui_test.png"

# target window placement for the screenshot bbox
WIN_X, WIN_Y = 100, 80
WIN_W, WIN_H = 1280, 720

# pyautogui
pyautogui.FAILSAFE = False
pyautogui.PAUSE = 0.05


def write_classpath():
    """Walk the version JSON AND libraries/ tree, return list of jar paths."""
    vdir = os.path.join(GAME_DIR, "versions", VERSION)
    libs_json = json.load(open(os.path.join(vdir, f"{VERSION}.json"), encoding="utf-8"))
    lib_dir = os.path.join(GAME_DIR, "libraries")
    seen = set()
    parts = []
    parts.append(os.path.join(vdir, f"{VERSION}.jar"))
    seen.add(os.path.normpath(parts[-1]))
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
    fl = os.path.normpath(os.path.join(lib_dir, "net", "fabricmc", "fabric-loader",
                                       "0.19.3", "fabric-loader-0.19.3.jar"))
    if os.path.isfile(fl) and fl not in seen:
        parts.append(fl)
        seen.add(fl)
    return parts


def wait_for_window(deadline_s, callback=None):
    end = time.time() + deadline_s
    while time.time() < end:
        found = []
        def cb(hwnd, _):
            if win32gui.IsWindowVisible(hwnd):
                t = win32gui.GetWindowText(hwnd)
                if WINDOW_TITLE in t:
                    found.append((hwnd, t))
            return True
        win32gui.EnumWindows(cb, None)
        if found:
            return found[0]
        if callback:
            callback()
        time.sleep(2)
    return None, None


def focus(hwnd):
    if win32gui.IsIconic(hwnd):
        win32gui.ShowWindow(hwnd, win32con.SW_RESTORE)
    cur = ctypes.windll.kernel32.GetCurrentThreadId()
    target = ctypes.windll.user32.GetWindowThreadProcessId(hwnd, 0)
    ctypes.windll.user32.AttachThreadInput(cur, target, True)
    try:
        win32gui.SetForegroundWindow(hwnd)
    except Exception:
        pass
    ctypes.windll.user32.AttachThreadInput(cur, target, False)
    time.sleep(0.3)


def main():
    print("Building classpath...")
    cp_parts = write_classpath()
    cp_file = os.path.join(GAME_DIR, "voxkbd_cp.txt")
    with open(cp_file, "w", encoding="utf-8") as f:
        f.write("\n".join(cp_parts))
    print(f"  {len(cp_parts)} entries -> {cp_file}")

    # Args go on the command line. Use a single string (shell=True) so Windows'
    # CreateProcess / CommandLineToArgvW handles quoting correctly — `26.2-Fabric 0.19.3`
    # has a SPACE so it MUST be quoted; the only way to preserve quoting through Python's
    # subprocess on Windows is to let the shell parse it.
    args_quoted = " ".join(
        f'"{a}"' if (a.startswith('"') or " " in a or "\t" in a) else a
        for a in [
            "--version", VERSION,
            "--gameDir", GAME_DIR,
            "--assetsDir", ASSETS,
            "--quickPlaySingleplayer", WORLD,
            "--quickPlayPath", SAVES_DIR,
        ]
    )
    # VERSION is "26.2-Fabric 0.19.3" — that has a space, so we MUST quote it on the cmdline.
    # But subprocess Popen with a string passes through to CreateProcess unquoted.
    # The trick: on Windows, when you give a STRING to subprocess, it uses the
    # shell-style parsing internally, but it doesn't pass through cmd.exe — so quotes
    # are still preserved as their literal characters. We MUST use shell=False with a list
    # and rely on Python's argv-construction rules: each list element is a separate argv
    # entry, so "26.2-Fabric 0.19.3" is one entry. The trick is to pass it AS ONE list entry
    # containing the whole string (Python will quote it on Windows as one arg).
    cmd = [
        JAVA, "-Xms2G", "-Xmx4G",
        f"-Djava.library.path={NATIVES}",
        f"-Dorg.lwjgl.librarypath={NATIVES}",
        "-cp", f"@{cp_file}",
        "net.fabricmc.loader.impl.launch.knot.KnotClient",
        f'--version {VERSION}',          # ONE argv entry: "--version 26.2-Fabric 0.19.3"
        f'--gameDir "{GAME_DIR}"',       # ONE argv entry: --gameDir "D:\..." (incl. spaces)
        f'--assetsDir "{ASSETS}"',
        f'--quickPlaySingleplayer "{WORLD}"',
        f'--quickPlayPath "{SAVES_DIR}"',
    ]
    print("Launching:", JAVA, "...")
    # Use a temp file for stdout/stderr so we can read it as the game starts.
    log_path = os.path.join(GAME_DIR, "voxkbd_mc_stdout.log")
    log_f = open(log_path, "wb")
    proc = subprocess.Popen(cmd, cwd=GAME_DIR, stdout=log_f, stderr=subprocess.STDOUT)
    print(f"  pid = {proc.pid}  stdout -> {log_path}")

    print("Waiting for MC window (up to 180s)...")
    hwnd, title = wait_for_window(180)
    if not hwnd:
        out, err = proc.communicate(timeout=5)
        print(f"MC never appeared. exit={proc.returncode}")
        print("STDOUT:", out.decode("utf-8", errors="replace")[-2000:])
        print("STDERR:", err.decode("utf-8", errors="replace")[-2000:])
        sys.exit(1)
    print(f"  Found: hwnd={hwnd} '{title}'")

    # Move & resize to a stable bbox for the screenshot.
    win32gui.SetWindowPos(hwnd, None, WIN_X, WIN_Y, WIN_W, WIN_H,
                          win32con.SWP_NOZORDER | win32con.SWP_SHOWWINDOW)
    print(f"  Resized to ({WIN_X},{WIN_Y}) {WIN_W}x{WIN_H}")
    focus(hwnd)
    time.sleep(1.0)

    # Click center to focus the GL canvas, then press Esc + ` to open the VoxKbd UI.
    rect = win32gui.GetWindowRect(hwnd)
    cx = (rect[0] + rect[2]) // 2
    cy = (rect[1] + rect[3]) // 2
    print(f"  Clicking center ({cx},{cy})...")
    pyautogui.click(cx, cy)
    time.sleep(0.5)
    print("  Pressing Esc to dismiss any menu...")
    pyautogui.press('escape')
    time.sleep(1.0)
    print("  Pressing ` (backtick) to open VoxKbd master UI...")
    pyautogui.press('`')
    time.sleep(2.0)

    # Screenshot.
    rect = win32gui.GetWindowRect(hwnd)
    img = ImageGrab.grab(bbox=rect)
    img.save(SCREENSHOT_PATH)
    print(f"Saved: {SCREENSHOT_PATH} ({img.size[0]}x{img.size[1]})")

    print(f"MC left running (pid={proc.pid}).")


if __name__ == "__main__":
    main()
