"""
VoxKbd UI test: launch MC 26.2 by re-using your PCL bat's exact java command,
appending --quickPlaySingleplayer so we skip the menu and land straight in a world.

Reads the launch bat, finds the long java command line, copies it as a string into a new
.cmd, and appends --quickPlaySingleplayer / --quickPlayPath. Spawns that, waits for the
MC window, drives the UI, screenshots.

Why this works: PCL's bat encodes the *correct* full classpath, the right natives dir, the
right Java flags, the right asset index, and the auth tokens. Re-typing it would re-introduce
every bug we hit. The only thing the bat doesn't include is --quickPlaySingleplayer, so
we append it.
"""

import os
import re
import sys
import time
import shutil
import ctypes
import subprocess
import win32gui
import win32con
import pyautogui
from PIL import ImageGrab

LAUNCH_BAT = r"D:\桌面\voxlink\客户端测试\versions\26.2-Fabric 0.19.3\启动 26.2-Fabric 0.19.3.bat"
GAME_DIR = r"D:\桌面\voxlink\客户端测试\versions\26.2-Fabric 0.19.3"
# The on-disk world folder is `world` (its level.dat carries LevelName="新的世界").
# --quickPlaySingleplayer wants the FOLDER name, --quickPlayPath wants the FOLDER path.
WORLD = "world"
SAVES = os.path.join(GAME_DIR, "saves", WORLD)
WINDOW_TITLE = "Minecraft* 26.2"
SCREENSHOT_PATH = r"D:\桌面\VoxKbd\voxkbd_ui_test.png"
WINDOW_X, WINDOW_Y = 20, 20
WINDOW_W, WINDOW_H = 1900, 1200

pyautogui.FAILSAFE = False
pyautogui.PAUSE = 0.05


def read_java_command_from_bat(bat_path):
    """Find the java invocation line and return it as a single string.

    The bat has one huge line: "C:\...\java.exe" -Xms... -cp "..." net.fabricmc...KnotClient --username ...
    We capture the line that starts with the java path, then strip the trailing `echo` line.
    """
    with open(bat_path, "r", encoding="utf-8") as f:
        text = f.read()
    # The java invocation is the line containing the .jar; in the bat it's the longest line
    # and starts with the absolute path to java.exe (in double quotes).
    # Find the first line that starts with "C:\..." or "<drive>:\...".
    java_line = None
    for raw in text.splitlines():
        line = raw.strip()
        # Match e.g. "C:\Program Files\Java\...\java.exe" -XX:...
        if re.match(r'^"[A-Z]:\\.*java\.exe"', line) or re.match(r'^[A-Z]:\\.*java\.exe\b', line):
            java_line = line
            break
    if not java_line:
        raise RuntimeError("could not find the java invocation in " + bat_path)
    return java_line


def build_quickplay_cmd(java_line):
    """Insert -Dvox.open=true (a JVM-level flag) and append quickPlay args.

    The -D must be inserted *before* the -cp classpath arg (Java's own flags go in
    the "java [options] class [args]" first section; anything after the class is
    program args to Knot). We splice it right after the last -D in the bat's
    java line so the rest of the bat's own JVM tuning is preserved.
    """
    java_line = re.sub(r"--width\s+\d+\s+--height\s+\d+\s*$", "", java_line).rstrip()
    # Splice -Dvox.open=true right before "-cp" (the last flag before the main class).
    return java_line.replace(
        ' -cp "',
        ' -Dvox.open=true -cp "',
        1,
    ) + (
        f' --quickPlaySingleplayer "{WORLD}"'
        f' --quickPlayPath "{SAVES}"'
    )


def find_window():
    found = []
    def cb(hwnd, _):
        if win32gui.IsWindowVisible(hwnd):
            t = win32gui.GetWindowText(hwnd)
            if WINDOW_TITLE in t:
                found.append((hwnd, t))
        return True
    win32gui.EnumWindows(cb, None)
    return found[0] if found else (None, None)


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


def wait_for_window(deadline_s):
    end = time.time() + deadline_s
    while time.time() < end:
        hwnd, title = find_window()
        if hwnd:
            return hwnd, title
        time.sleep(2)
    return None, None


def main():
    print(f"Reading java command from {LAUNCH_BAT} ...")
    java_line = read_java_command_from_bat(LAUNCH_BAT)
    cmd_str = build_quickplay_cmd(java_line)
    print(f"  java line length: {len(java_line)} chars")
    print(f"  +quickPlay args, total cmd length: {len(cmd_str)} chars")

    # Build a small .cmd that runs the command. Using a string + cmd /c is the only
    # reliable way to pass a multi-kilobyte classpath through CreateProcess on Windows.
    runner = os.path.join(GAME_DIR, "voxkbd_runner.cmd")
    with open(runner, "w", encoding="utf-8") as f:
        f.write("@echo off\n")
        f.write("chcp 65001 >nul\n")
        f.write(cmd_str + "\n")
        f.write("echo [voxkbd] game exited with code %ERRORLEVEL%.\n")
    print(f"  runner -> {runner}")

    # Launch.
    print("Launching MC ...")
    proc = subprocess.Popen(["cmd", "/c", runner],
                            creationflags=subprocess.CREATE_NEW_CONSOLE)
    print(f"  cmd pid = {proc.pid}")

    print("Waiting for MC window (up to 240s)...")
    hwnd, title = wait_for_window(240)
    if not hwnd:
        print("ERROR: MC window never appeared", file=sys.stderr)
        sys.exit(1)
    print(f"  Found: hwnd={hwnd} title='{title}'")

    # Resize & position for a stable screenshot bbox.
    win32gui.SetWindowPos(hwnd, None, WINDOW_X, WINDOW_Y, WINDOW_W, WINDOW_H,
                          win32con.SWP_NOZORDER | win32con.SWP_SHOWWINDOW)
    print(f"  Resized to ({WINDOW_X},{WINDOW_Y}) {WINDOW_W}x{WINDOW_H}")
    focus(hwnd)
    time.sleep(1.0)

    # The mod was launched with --voxOpen, so it auto-shows the Master UI as soon as
    # the player is in-game. We just wait for the screen to appear (a few seconds),
    # then screenshot — no keystrokes needed.
    print("  Waiting for the mod to auto-open the Master UI (--voxOpen)...")
    time.sleep(6.0)

    rect = win32gui.GetWindowRect(hwnd)
    img = ImageGrab.grab(bbox=rect)
    img.save(SCREENSHOT_PATH)
    print(f"Saved: {SCREENSHOT_PATH} ({img.size[0]}x{img.size[1]})")
    print(f"MC left running.")


if __name__ == "__main__":
    main()
