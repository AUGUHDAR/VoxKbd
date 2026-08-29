package com.voxkbd.daemon.input;

import com.voxkbd.core.config.ComboPrefixMask;
import com.voxkbd.core.input.InputState;
import com.voxkbd.core.key.PhysicalKey;
import com.voxkbd.core.key.PhysicalKeyRegistry;

import java.lang.foreign.Arena;
import java.lang.foreign.FunctionDescriptor;
import java.lang.foreign.Linker;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.SymbolLookup;
import java.lang.foreign.ValueLayout;
import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.MethodType;
import java.util.HashSet;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;

/**
 * Production global keyboard capture on Windows via a low-level keyboard hook (WH_KEYBOARD_LL),
 * calling {@code user32} directly through the JDK's Foreign Function &amp; Memory API
 * ({@code java.lang.foreign}, final since Java 22). Minecraft 26.2 mandates a Java 25 runtime, so the
 * capability is guaranteed present in every JVM that can run this mod — NO bundled native library
 * (no JNA jar) is needed; the player's JVM is the only native-access layer.
 *
 * <p>An LL hook requires the installing thread to pump messages: {@link #start} spawns one dedicated
 * thread that both installs the hook and runs the {@code GetMessageW} loop.</p>
 *
 * <p>Suppression policy (§4.0 / D16):</p>
 * <ul>
 *   <li>Unmapped / reserved tokens (ESC + modifiers) → pass through natively (no action).</li>
 *   <li>Unfocused, or an MC Screen open ({@code paused}) → pass through natively so GUI typing and
 *       menus keep working untouched.</li>
 *   <li>Combo-prefix + digit (D8/D9) → capture even when locked (digits 1–9 are hotbar-bound by
 *       default; without this override combo switching could never fire out of the box).</li>
 *   <li>LOCKED key → pass through natively so MC receives it as vanilla.</li>
 *   <li>UNLOCKED key → <b>suppress</b> the native event and forward it to the sink (press=1,
 *       repeat=2, release=0), which delivers the synthetic extended keycode in-process.</li>
 * </ul>
 *
 * <p>Mouse / gamepad capture is a future extension (the translation pipeline already supports them
 * via {@link HeadlessCapture}).</p>
 */
public final class WindowsKeyboardHook implements PhysicalInputCapture {

    private static final int WH_KEYBOARD_LL = 13;
    private static final int WM_KEYDOWN = 0x0100, WM_SYSKEYDOWN = 0x0104;
    private static final int WM_KEYUP = 0x0101, WM_SYSKEYUP = 0x0105;
    private static final int WM_QUIT = 0x0012;

    /** KBDLLHOOKSTRUCT size on x64: vkCode/scanCode/flags/time (4×4) + ULONG_PTR dwExtraInfo (8). */
    private static final long KBDLLHOOKSTRUCT_SIZE = 24;

    // ---- FFM plumbing (bound once per class; user32/kernel32 live for the process lifetime) ----

    private static final Linker LINKER = Linker.nativeLinker();
    private static final SymbolLookup USER32 = SymbolLookup.libraryLookup("user32", Arena.global());
    private static final SymbolLookup KERNEL32 = SymbolLookup.libraryLookup("kernel32", Arena.global());

    private static final MethodHandle SET_WINDOWS_HOOK_EX_W = downcall("SetWindowsHookExW",
            FunctionDescriptor.of(ValueLayout.ADDRESS, ValueLayout.JAVA_INT,
                    ValueLayout.ADDRESS, ValueLayout.ADDRESS, ValueLayout.JAVA_INT));
    private static final MethodHandle UNHOOK_WINDOWS_HOOK_EX = downcall("UnhookWindowsHookEx",
            FunctionDescriptor.of(ValueLayout.JAVA_INT, ValueLayout.ADDRESS));
    private static final MethodHandle CALL_NEXT_HOOK_EX = downcall("CallNextHookEx",
            FunctionDescriptor.of(ValueLayout.JAVA_LONG, ValueLayout.ADDRESS, ValueLayout.JAVA_INT,
                    ValueLayout.JAVA_LONG, ValueLayout.JAVA_LONG));
    private static final MethodHandle GET_MESSAGE_W = downcall("GetMessageW",
            FunctionDescriptor.of(ValueLayout.JAVA_INT, ValueLayout.ADDRESS, ValueLayout.ADDRESS,
                    ValueLayout.JAVA_INT, ValueLayout.JAVA_INT));
    private static final MethodHandle GET_ASYNC_KEY_STATE = downcall("GetAsyncKeyState",
            FunctionDescriptor.of(ValueLayout.JAVA_SHORT, ValueLayout.JAVA_INT));
    private static final MethodHandle POST_THREAD_MESSAGE_W = downcall("PostThreadMessageW",
            FunctionDescriptor.of(ValueLayout.JAVA_INT, ValueLayout.JAVA_INT, ValueLayout.JAVA_INT,
                    ValueLayout.JAVA_LONG, ValueLayout.JAVA_LONG));
    private static final MethodHandle GET_CURRENT_THREAD_ID = downcall("GetCurrentThreadId",
            FunctionDescriptor.of(ValueLayout.JAVA_INT));

    /** LRESULT(int nCode, WPARAM wParam, LPARAM lParam): WPARAM is a plain pointer-width int,
     *  LPARAM arrives holding a pointer to KBDLLHOOKSTRUCT and therefore maps to ADDRESS/MemorySegment. */
    private static final FunctionDescriptor LL_PROC_DESC = FunctionDescriptor.of(
            ValueLayout.JAVA_LONG, ValueLayout.JAVA_INT, ValueLayout.JAVA_LONG, ValueLayout.ADDRESS);

    private static MethodHandle downcall(String fn, FunctionDescriptor desc) {
        return LINKER.downcallHandle(
                USER32.find(fn).or(() -> KERNEL32.find(fn))
                        .orElseThrow(() -> new UnsatisfiedLinkError(fn)),
                desc);
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
    private volatile MemorySegment hookHandle = MemorySegment.NULL;
    private volatile long pumpThreadId;
    /** First fatal error from the pump thread, surfaced by {@link #start} for diagnostics. */
    private volatile Throwable initError;

    public WindowsKeyboardHook(InputState state) {
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
        // The install result surfaces asynchronously; wait briefly so callers see real failures.
        if (!settled.await(5, TimeUnit.SECONDS)) {
            running = false;
            throw new IllegalStateException("LL keyboard hook thread did not initialize in time");
        }
        if (!installed) {
            throw new IllegalStateException("SetWindowsHookExW failed", initError);
        }
    }

    /** Runs on the dedicated pump thread: install the hook, then drain the message queue. */
    private void pumpLoop(CountDownLatch settled) {
        try {
            int tid = (int) GET_CURRENT_THREAD_ID.invokeExact();
            pumpThreadId = tid;
            MethodHandles.Lookup lookup = MethodHandles.lookup();
            MethodType mt = MethodType.methodType(long.class,
                    int.class, long.class, MemorySegment.class);
            MethodHandle proc = lookup.findVirtual(WindowsKeyboardHook.class, "llProc", mt).bindTo(this);
            MemorySegment stub = LINKER.upcallStub(proc, LL_PROC_DESC, Arena.global());
            MemorySegment handle = (MemorySegment) SET_WINDOWS_HOOK_EX_W.invokeExact(
                    WH_KEYBOARD_LL, stub, MemorySegment.NULL, 0);
            installed = handle != null && !handle.equals(MemorySegment.NULL);
            hookHandle = installed ? handle : MemorySegment.NULL;
            settled.countDown();
            if (!installed) return;

            try (Arena arena = Arena.ofConfined()) {
                MemorySegment msgBuf = arena.allocate(64); // MSG struct, contents unused
                while (running) {
                    int r = (int) GET_MESSAGE_W.invokeExact(msgBuf, MemorySegment.NULL, 0, 0);
                    if (r == 0 || r == -1) break; // WM_QUIT or error
                }
            }
        } catch (Throwable t) {
            initError = t;
            installed = false;
            settled.countDown();
        }
    }

    /**
     * Low-level hook procedure (upcalled by Windows). Returns a nonzero value to suppress native
     * delivery of the event, else passes it along the hook chain. Must never throw.
     */
    long llProc(int nCode, long wParam, MemorySegment lParam) {
        if (nCode >= 0 && lParam != null && lParam != MemorySegment.NULL) {
            try {
                int msg = (int) wParam;
                boolean down = (msg == WM_KEYDOWN || msg == WM_SYSKEYDOWN);
                boolean up = (msg == WM_KEYUP || msg == WM_SYSKEYUP);
                if (down || up) {
                    MemorySegment info = lParam.reinterpret(KBDLLHOOKSTRUCT_SIZE);
                    int vk = info.get(ValueLayout.JAVA_INT, 0);
                    if (shouldCapture(vk, down)) {
                        sink.accept(new CapturedKey(WinVkMap.physicalKeyOfWinVk(vk),
                                captureAction(vk, down), currentMods(), vk));
                        return 1L; // suppress native delivery
                    }
                }
            } catch (Throwable ignored) {
                // Never break the system-wide hook chain on a backend bug.
            }
        }
        try {
            return (long) CALL_NEXT_HOOK_EX.invokeExact(hookHandle, nCode, wParam,
                    MemorySegment.ofAddress(lParam.address()));
        } catch (Throwable t) {
            return 0L;
        }
    }

    /**
     * Capture decision for one low-level key event (see class javadoc for the full policy):
     * reserved tokens and unmapped keys never capture; unfocused/paused never capture; a
     * combo-prefix digit captures even when locked; otherwise only unlocked keys capture.
     */
    private boolean shouldCapture(int vk, boolean down) {
        PhysicalKey pk = WinVkMap.physicalKeyOfWinVk(vk);
        if (pk == null || PhysicalKeyRegistry.isReserved(pk.token())) return false;

        InputState s = state;
        if (!s.focus()) {
            synchronized (downVks) { downVks.clear(); } // tracked state went stale
            return false;
        }
        // GUI pass-through, EXCEPT while the vanilla key-binds screen is capturing a binding:
        // pressed keys must be captured and translated (a locked key would otherwise always
        // register as its vanilla key and a function could never be bound to a synthetic key).
        if (s.paused() && !s.bindCapture()) {
            synchronized (downVks) { downVks.clear(); } // tracked state went stale
            return false;
        }

        // Mod-owned switch / config bindings are ALWAYS pass-through (§3.3 "防止卡死"). Checked
        // even when the vk resolves to no PhysicalKey (e.g. `[`=91, `]`=93, `` ` ``=96 are the
        // mod's defaults and live outside the physical-key table) so a rebind can never block
        // access to the mod's own UI.
        if (s.isModOwnedKeycode(vk)) return false;

        int mods = currentMods();
        int prefixMask = s.comboPrefixMask();
        boolean comboDigit = prefixMask != 0
                && ComboPrefixMask.fromGlfwMods(mods) == prefixMask
                && ComboPrefixMask.isDigitToken(pk.token());

        // Vanilla baseline active (D20): nothing is translated, only combo digits are intercepted
        // so the player can switch into a virtual keyboard.
        if (s.activeKeyboard() == com.voxkbd.core.Constants.VANILLA_KB_INDEX) {
            return comboDigit;
        }
        // Normal lock rules apply during bind capture too (眼见为实): unlocked keys are captured
        // (translated synthetic), locked keys pass through natively. bindCapture above ONLY lifts
        // the GUI pass-through so unlocked keys reach the translator while a screen is open.
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
        MemorySegment handle = hookHandle;
        if (installed && handle != null && !handle.equals(MemorySegment.NULL)) {
            try {
                @SuppressWarnings("unused")
                int rc = (int) UNHOOK_WINDOWS_HOOK_EX.invokeExact(handle); // BOOL, best-effort
            } catch (Throwable ignored) {
                // best-effort
            }
        }
        installed = false;
        hookHandle = MemorySegment.NULL;
        // Wake the blocked GetMessage loop so the pump thread exits promptly.
        long tid = pumpThreadId;
        if (tid != 0) {
            try {
                @SuppressWarnings("unused")
                int posted = (int) POST_THREAD_MESSAGE_W.invokeExact((int) tid, WM_QUIT, 0L, 0L);
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
            if ((highBit((short) GET_ASYNC_KEY_STATE.invokeExact(0x10))) != 0) mods |= GLFW_MOD_SHIFT;   // VK_SHIFT
            if ((highBit((short) GET_ASYNC_KEY_STATE.invokeExact(0x11))) != 0) mods |= GLFW_MOD_CONTROL; // VK_CONTROL
            if ((highBit((short) GET_ASYNC_KEY_STATE.invokeExact(0x12))) != 0) mods |= GLFW_MOD_ALT;     // VK_MENU
            int win = highBit((short) GET_ASYNC_KEY_STATE.invokeExact(0x5B))      // VK_LWIN
                    | highBit((short) GET_ASYNC_KEY_STATE.invokeExact(0x5C));     // VK_RWIN
            if (win != 0) mods |= GLFW_MOD_SUPER;
            return mods;
        } catch (Throwable t) {
            return 0;
        }
    }

    private static int highBit(short s) { return (s & 0x8000) != 0 ? 1 : 0; }
}
