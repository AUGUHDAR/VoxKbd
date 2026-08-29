"""
VoxKbd UI test: post-MC-launch automation via pyautogui.

WORKFLOW:
  1. You launch the game manually via PCL (or however you prefer).
  2. You press the key to open the VoxKbd master UI (default: ` backtick).
     OR, run this script with the flag `--auto` to have it open the UI itself.
  3. This script:
     - Waits for the MC window to appear.
     - Brings it to foreground.
     - Repositions it to a fixed size (1280x720 @ 100,80).
     - If --auto: presses Esc then ` to dismiss menu and open the VoxKbd master UI.
     - Screenshots just the MC window region to voxkbd_ui_test.png.

We do NOT launch MC ourselves — manual launch via PCL is reliable, our Python launch was
fighting 130+ libs / natives / JVM detection that PCL already handles.
"""

import os
import sys
import time
import ctypes
import argparse
import win32gui
import win32con
import pyautogui
from PIL import ImageGrab

WINDOW_TITLE = "Minecraft* 26.2"
SCREENSHOT_PATH = r"D:\桌面\VoxKbd\voxkbd_ui_test.png"
WINDOW_X = 100
WINDOW_Y = 80
WINDOW_W = 1280
WINDOW_H = 720
WAIT_TIMEOUT_S = 240
AUTO_OPEN_WAIT = 2.0

pyautogui.FAILSAFE = False
pyautogui.PAUSE = 0.05


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
    fg = win32gui.GetForegroundWindow()
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
    ap = argparse.ArgumentParser()
    ap.add_argument("--auto", action="store_true",
                    help="press Esc then ` to open VoxKbd master UI automatically")
    args = ap.parse_args()

    print(f"Waiting up to {WAIT_TIMEOUT_S}s for MC window '{WINDOW_TITLE}'...")
    hwnd, title = wait_for_window(WAIT_TIMEOUT_S)
    if not hwnd:
        print(f"ERROR: window '{WINDOW_TITLE}' never appeared", file=sys.stderr)
        sys.exit(1)
    print(f"  Found: hwnd={hwnd} title='{title}'")

    win32gui.SetWindowPos(hwnd, None, WINDOW_X, WINDOW_Y, WINDOW_W, WINDOW_H,
                          win32con.SWP_NOZORDER | win32con.SWP_SHOWWINDOW)
    print(f"  Resized to ({WINDOW_X},{WINDOW_Y}) {WINDOW_W}x{WINDOW_H}")
    focus(hwnd)
    time.sleep(0.5)

    if args.auto:
        rect = win32gui.GetWindowRect(hwnd)
        cx = (rect[0] + rect[2]) // 2
        cy = (rect[1] + rect[3]) // 2
        print(f"  Clicking center ({cx},{cy}) to focus GL canvas...")
        pyautogui.click(cx, cy)
        time.sleep(0.5)

        print("  Pressing Esc to dismiss any open menu...")
        pyautogui.press('escape')
        time.sleep(1.0)

        print("  Pressing ` (backtick) to open VoxKbd master UI...")
        pyautogui.press('`')
        time.sleep(AUTO_OPEN_WAIT)

    # Screenshot the window region (works whether or not the UI is open).
    rect = win32gui.GetWindowRect(hwnd)
    img = ImageGrab.grab(bbox=rect)
    img.save(SCREENSHOT_PATH)
    print(f"Saved: {SCREENSHOT_PATH}  size={img.size}")


if __name__ == "__main__":
    main()
