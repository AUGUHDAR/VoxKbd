package com.voxkbd.daemon.input;

import com.voxkbd.core.config.ComboPrefixMask;
import com.voxkbd.core.input.InputState;
import com.voxkbd.core.key.PhysicalKey;
import com.voxkbd.core.key.PhysicalKeyRegistry;
import com.sun.jna.IntegerType;
import com.sun.jna.Memory;
import com.sun.jna.Native;
import com.sun.jna.Pointer;
import com.sun.jna.Structure;
import com.sun.jna.win32.StdCallLibrary;

import java.util.HashSet;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;

/**
 * Windows low-level keyboard hook (WH_KEYBOARD_LL) via JNA — drop-in equivalent of the FFM
 * {@code WindowsKeyboardHook} for Java 17/21 runtimes (FFM is preview there and unavailable
 * without {@code --enable-preview}, which MC does not set). JNA is bundled into the mod jar for
 * these targets; no other third-party dependency exists.
 *
 * <p>Identical suppress/pass-through policy to the FFM backend:</p>
 * <ul>
 *   <li>Unmapped / reserved tokens (ESC + modifiers) → pass through natively.</li>
 *   <li>Unfocused, or an MC Screen open (paused, unless bind-capture) → pass through natively.</li>
 *   <li>Combo-prefix + digit → capture even when locked.</li>
 *   <li>LOCKED key → pass through natively; UNLOCKED key → suppress and forward to the sink.</li>
 * </ul>
 */
public final class WindowsKeyboardHookJna implements PhysicalInputCapture {

    private static final int WH_KEYBOARD_LL = 13;
    private static final int WM_KEYDOWN = 0x0100, WM_SYSKEYDOWN = 0x0104;
    private static final int WM_KEYUP = 0x0101, WM_SYSKEYUP = 0x0105;
    private static final int WM_QUIT = 0x0012;

    /** Minimal user32 surface — declared locally so only jna core (not jna-platform) is needed. */
    private interface User32 extends StdCallLibrary {
        User32 INSTANCE = Native.load("user32", User32.class,
                com.sun.jna.win32.W32APIOptions.DEFAULT_OPTIONS);

        Pointer SetWindowsHookExW(int idHook, LowLevelKeyboardProc lpfn, Pointer hMod, int dwThreadId);
        int UnhookWindowsHookEx(Pointer hhk);
        Pointer CallNextHookEx(Pointer hhk, int nCode, ULONG_PTR wParam, LPARAM lParam);
        int GetMessageW(Pointer lpMsg, Pointer hWnd, int wMsgFilterMin, int wMsgFilterMax);
        short GetAsyncKeyState(int vKey);
        int PostThreadMessageW(int idThread, int msg, ULONG_PTR wParam, ULONG_PTR lParam);
        int GetCurrentThreadId();
    }

    /** Pointer-width unsigned integer (WPARAM / HRESULT-style fields). */
    public static final class ULONG_PTR extends IntegerType {
        public ULONG_PTR() { this(0); }
        public ULONG_PTR(long value) { super(Native.POINTER_SIZE, value, true); }
    }

    /** Pointer-width signed integer (LPARAM). */
    public static final class LPARAM extends IntegerType {
        public LPARAM() { this(0); }
        public LPARAM(long value) { super(Native.POINTER_SIZE, value, true); }
    }

    /** KBDLLHOOKSTRUCT (x64 layout: 4×4 bytes + pointer-sized extra info). */
    public static final class KbdllHookStruct extends Structure {
        public int vkCode;
        public int scanCode;
        public int flags;
        public int time;
        public ULONG_PTR dwExtraInfo = new ULONG_PTR();

        @Override protected java.util.List<String> getFieldOrder() {
            return java.util.List.of("vkCode", "scanCode", "flags", "time", "dwExtraInfo");
        }
    }

    /** LRESULT LowLevelKeyboardProc(int nCode, WPARAM wParam, KBDLLHOOKSTRUCT *lParam). */
    public interface LowLevelKeyboardProc extends StdCallLibrary.StdCallCallback {
        ULONG_PTR callback(int nCode, ULONG_PTR wParam, KbdllHookStruct lParam);
    }

    // GLFW modifier bit values (stable ABI) reported alongside each captured event.
    private static final int GLFW_MOD_SHIFT = 0x0001;
    private static final int GLFW_MOD_CONTROL = 0x0002;
    private static final int GLFW_MOD_ALT = 0x0004;
    private static final int GLFW_MOD_SUPER = 0x0008;

    private final InputState state;

    /** Currently-held VKs, to distinguish auto-repeat (action 2) from fresh presses (action 1). */
    private final Set<Integer> downVks = new HashSet<>();

    private Thread pump;
    private volatile Consumer<CapturedKey> sink;
    private volatile boolean running;
    private volatile boolean installed;
    private volatile Pointer hookHandle;
    private volatile long pumpThreadId;

    public WindowsKeyboardHookJna(InputState state) {
        this.state = state;
    }

    @Override
    public void start(Consumer<CapturedKey> sink) throws Exception {
        this.sink = sink;
        this.running = true;
        CountDownLatch settled = new CountDownLatch(1);
        pump = new Thread(() -> pumpLoop(settled), "voxkbd-ll-keyboard-pump");
        pump.setDaemon(true);
        pump.start();
        if (!settled.await(5, TimeUnit.SECONDS)) {
            running = false;
            throw new IllegalStateException("LL keyboard hook thread did not initialize in time");
        }
        if (!installed) {
            throw new IllegalStateException("SetWindowsHookExW failed");
        }
    }

    /** Runs on the dedicated pump thread: install the hook, then drain the message queue. */
    private void pumpLoop(CountDownLatch settled) {
        try {
            pumpThreadId = User32.INSTANCE.GetCurrentThreadId();
            LowLevelKeyboardProc proc = this::llProc;
            Pointer handle = User32.INSTANCE.SetWindowsHookExW(WH_KEYBOARD_LL, proc, null, 0);
            installed = handle != null;
            hookHandle = handle;
            settled.countDown();
            if (!installed) return;

            // MSG struct placeholder (contents unused; 48 bytes covers x64 MSG).
            Memory msgBuf = new Memory(48);
            while (running) {
                int r = User32.INSTANCE.GetMessageW(msgBuf, null, 0, 0);
                if (r == 0 || r == -1) break; // WM_QUIT or error
            }
        } catch (Throwable t) {
            settled.countDown();
        }
    }

    /** Low-level hook procedure. Nonzero return suppresses native delivery. Must never throw. */
    private ULONG_PTR llProc(int nCode, ULONG_PTR wParam, KbdllHookStruct info) {
        if (nCode >= 0 && info != null) {
            try {
                int msg = wParam.intValue();
                boolean down = (msg == WM_KEYDOWN || msg == WM_SYSKEYDOWN);
                boolean up = (msg == WM_KEYUP || msg == WM_SYSKEYUP);
                if (down || up) {
                    int vk = info.vkCode;
                    boolean extended = (info.flags & WinVkMap.LLKHF_EXTENDED) != 0;
                    PhysicalKey pk = WinVkMap.physicalKeyOfWinVk(vk, extended);
                    if (shouldCapture(pk, down)) {
                        sink.accept(new CapturedKey(pk, captureAction(vk, down), currentMods(), vk));
                        return new ULONG_PTR(1); // suppress native delivery
                    }
                }
            } catch (Throwable ignored) {
                // Never break the system-wide hook chain on a backend bug.
            }
        }
        try {
            Pointer h = hookHandle;
            Pointer result = User32.INSTANCE.CallNextHookEx(h, nCode, wParam,
                    new LPARAM(Pointer.nativeValue(info != null ? info.getPointer() : Pointer.NULL)));
            return new ULONG_PTR(Pointer.nativeValue(result));
        } catch (Throwable t) {
            return new ULONG_PTR(0);
        }
    }

    /** Same capture decision as the FFM backend (see its javadoc for the full policy). */
    private boolean shouldCapture(PhysicalKey pk, boolean down) {
        if (pk == null || PhysicalKeyRegistry.isReserved(pk.token())) return false;

        InputState s = state;
        if (!s.focus()) {
            synchronized (downVks) { downVks.clear(); }
            return false;
        }
        if (s.paused() && !s.bindCapture()) {
            synchronized (downVks) { downVks.clear(); }
            return false;
        }
        // Mod-owned keycodes are GLFW codes — compare against the key's GLFW code, not the VK.
        if (s.isModOwnedKeycode(pk.glfwCode())) return false;

        int mods = currentMods();
        int prefixMask = s.comboPrefixMask();
        boolean comboDigit = prefixMask != 0
                && ComboPrefixMask.fromGlfwMods(mods) == prefixMask
                && ComboPrefixMask.isDigitToken(pk.token());

        if (s.activeKeyboard() == com.voxkbd.core.Constants.VANILLA_KB_INDEX) {
            return comboDigit;
        }
        return comboDigit || !s.isLocked(pk.token());
    }

    /** First press vs auto-repeat vs release (GLFW actions 1 / 2 / 0). */
    private int captureAction(int vk, boolean down) {
        if (!down) {
            synchronized (downVks) { downVks.remove(vk); }
            return 0;
        }
        synchronized (downVks) { return downVks.add(vk) ? 1 : 2; }
    }

    @Override
    public void stop() {
        running = false;
        Pointer handle = hookHandle;
        if (installed && handle != null) {
            try {
                User32.INSTANCE.UnhookWindowsHookEx(handle); // BOOL, best-effort
            } catch (Throwable ignored) {
                // best-effort
            }
        }
        installed = false;
        hookHandle = null;
        long tid = pumpThreadId;
        if (tid != 0) {
            try {
                User32.INSTANCE.PostThreadMessageW((int) tid, WM_QUIT,
                        new ULONG_PTR(0), new ULONG_PTR(0));
            } catch (Throwable ignored) {
                // daemon thread anyway
            }
        }
        sink = null;
    }

    @Override
    public boolean isRunning() { return running && installed; }

    /** Snapshot the currently-held GLFW modifier bits via async key-state polling. */
    private int currentMods() {
        try {
            int mods = 0;
            if (highBit(User32.INSTANCE.GetAsyncKeyState(0x10)) != 0) mods |= GLFW_MOD_SHIFT;   // VK_SHIFT
            if (highBit(User32.INSTANCE.GetAsyncKeyState(0x11)) != 0) mods |= GLFW_MOD_CONTROL; // VK_CONTROL
            if (highBit(User32.INSTANCE.GetAsyncKeyState(0x12)) != 0) mods |= GLFW_MOD_ALT;     // VK_MENU
            int win = highBit(User32.INSTANCE.GetAsyncKeyState(0x5B))    // VK_LWIN
                    | highBit(User32.INSTANCE.GetAsyncKeyState(0x5C));   // VK_RWIN
            if (win != 0) mods |= GLFW_MOD_SUPER;
            return mods;
        } catch (Throwable t) {
            return 0;
        }
    }

    private static int highBit(short s) { return (s & 0x8000) != 0 ? 1 : 0; }
}
