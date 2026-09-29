# -*- coding: utf-8 -*-
"""限缩键鼠拦截到 Minecraft 窗口 —— 加 OS 前台进程校验 + 钩子随焦点装卸。

Modrinth 审核退回理由：the mod intercepts keystrokes from all applications。
两处修复：
  A. 钩子回调里查 GetForegroundWindow + GetWindowThreadProcessId，前台窗口不属于本进程
     就直接放行（不拦截、不记录）；
  B. 钩子生命周期跟随窗口焦点：失焦卸载、回到前台再装（InProcessCapture.onFocus）。
脚本幂等，可重复跑；锚点缺失会直接 assert 报错。
"""
import io
import os
import sys

SKIP = {'其他项目', '.git', 'gradle', 'run'}
JNA_HOOK = 'WindowsKeyboardHookJna.java'
FFM_HOOK = 'WindowsKeyboardHook.java'

# ---------------------------------------------------------------- A1. JNA hook
JNA_IMPORT_OLD = """import com.sun.jna.Pointer;
import com.sun.jna.Structure;"""
JNA_IMPORT_NEW = """import com.sun.jna.Pointer;
import com.sun.jna.Structure;
import com.sun.jna.ptr.IntByReference;"""

JNA_API_OLD = """        int GetMessageW(Pointer lpMsg, Pointer hWnd, int wMsgFilterMin, int wMsgFilterMax);"""
JNA_API_NEW = """        int GetMessageW(Pointer lpMsg, Pointer hWnd, int wMsgFilterMin, int wMsgFilterMax);
        Pointer GetForegroundWindow();
        int GetWindowThreadProcessId(Pointer hWnd, IntByReference lpdwProcessId);
        int GetCurrentProcessId();"""

JNA_FIELD_OLD = """    private final InputState state;
"""
JNA_FIELD_NEW = """    private final InputState state;

    /** This JVM's process id — the hook only ever acts for this process's own window. */
    private final int myPid = currentPid();

    private static int currentPid() {
        try { return User32.INSTANCE.GetCurrentProcessId(); } catch (Throwable t) { return 0; }
    }
"""

JNA_PROC_OLD = """                    PhysicalKey pk = WinVkMap.physicalKeyOfWinVk(vk, extended);
                    if (shouldCapture(pk, down)) {
                        sink.accept(new CapturedKey(pk, captureAction(vk, down), currentMods(), vk));
                        return new ULONG_PTR(1); // suppress native delivery
                    }"""
JNA_PROC_NEW = """                    PhysicalKey pk = WinVkMap.physicalKeyOfWinVk(vk, extended);
                    // Never act for another application: unless the OS foreground window belongs
                    // to this Minecraft process, the event is passed straight down the chain.
                    if (!foregroundIsMinecraft()) {
                        synchronized (downVks) { downVks.clear(); }
                    } else if (shouldCapture(pk, down)) {
                        sink.accept(new CapturedKey(pk, captureAction(vk, down), currentMods(), vk));
                        return new ULONG_PTR(1); // suppress native delivery
                    }"""

JNA_HELPER = """    /** True only while the OS foreground window belongs to this Minecraft process. */
    private boolean foregroundIsMinecraft() {
        if (myPid == 0) return false;
        try {
            Pointer fg = User32.INSTANCE.GetForegroundWindow();
            if (fg == null || Pointer.nativeValue(fg) == 0) return false;
            IntByReference pid = new IntByReference();
            User32.INSTANCE.GetWindowThreadProcessId(fg, pid);
            return pid.getValue() == myPid;
        } catch (Throwable t) {
            return false;
        }
    }

    /** Same capture decision as the FFM backend (see its javadoc for the full policy). */"""
JNA_HELPER_ANCHOR = """    /** Same capture decision as the FFM backend (see its javadoc for the full policy). */"""

# ---------------------------------------------------------------- A2. FFM hook
FFM_API_OLD = """    private static final MethodHandle GET_CURRENT_THREAD_ID = downcall("GetCurrentThreadId",
            FunctionDescriptor.of(ValueLayout.JAVA_INT));"""
FFM_API_NEW = FFM_API_OLD + """
    private static final MethodHandle GET_FOREGROUND_WINDOW = downcall("GetForegroundWindow",
            FunctionDescriptor.of(ValueLayout.ADDRESS));
    private static final MethodHandle GET_WINDOW_THREAD_PROCESS_ID = downcall("GetWindowThreadProcessId",
            FunctionDescriptor.of(ValueLayout.JAVA_INT, ValueLayout.ADDRESS, ValueLayout.ADDRESS));
    private static final MethodHandle GET_CURRENT_PROCESS_ID = downcall("GetCurrentProcessId",
            FunctionDescriptor.of(ValueLayout.JAVA_INT));"""

FFM_PROC_OLD = """                    PhysicalKey pk = WinVkMap.physicalKeyOfWinVk(vk, extended);
                    if (shouldCapture(pk, down)) {
                        sink.accept(new CapturedKey(pk, captureAction(vk, down), currentMods(), vk));
                        return 1L; // suppress native delivery
                    }"""
FFM_PROC_NEW = """                    PhysicalKey pk = WinVkMap.physicalKeyOfWinVk(vk, extended);
                    // Never act for another application: unless the OS foreground window belongs
                    // to this Minecraft process, the event is passed straight down the chain.
                    if (!foregroundIsMinecraft()) {
                        synchronized (downVks) { downVks.clear(); }
                    } else if (shouldCapture(pk, down)) {
                        sink.accept(new CapturedKey(pk, captureAction(vk, down), currentMods(), vk));
                        return 1L; // suppress native delivery
                    }"""

FFM_FIELD_ANCHOR = """    private final InputState state;"""
FFM_HELPER = """    private final InputState state;

    /** This JVM's process id — the hook only ever acts for this process's own window. */
    private final int processId = currentPid();

    private static int currentPid() {
        try { return (int) GET_CURRENT_PROCESS_ID.invokeExact(); } catch (Throwable t) { return 0; }
    }

    /** True only while the OS foreground window belongs to this Minecraft process. */
    private boolean foregroundIsMinecraft() {
        if (processId == 0) return false;
        try (Arena arena = Arena.ofConfined()) {
            MemorySegment fg = (MemorySegment) GET_FOREGROUND_WINDOW.invokeExact();
            if (fg == null || fg.equals(MemorySegment.NULL)) return false;
            MemorySegment pidOut = arena.allocate(ValueLayout.JAVA_INT);
            GET_WINDOW_THREAD_PROCESS_ID.invokeExact(fg, pidOut);
            return pidOut.get(ValueLayout.JAVA_INT, 0) == processId;
        } catch (Throwable t) {
            return false;
        }
    }"""

# ---------------------------------------------------------------- B. InProcessCapture
IPC_START_OLD = """    private static volatile PhysicalInputCapture capture;

    private InProcessCapture() {}

    /** Start the in-process capture backend. Returns true if a capture backend is active. */
    public static boolean start(InputState state, SwitchManager switchManager) {"""
IPC_START_NEW = """    private static volatile PhysicalInputCapture capture;

    /** Backend factory arguments, kept so the hook can be re-installed on focus regain. */
    private static InputState stateRef;
    private static SwitchManager switchRef;

    private InProcessCapture() {}

    /** Start the in-process capture backend. Returns true if a capture backend is active. */
    public static synchronized boolean start(InputState state, SwitchManager switchManager) {
        stateRef = state;
        switchRef = switchManager;
        return startBackend(state, switchManager);
    }

    /**
     * Hook lifecycle follows the Minecraft window's focus: while the game sits in the background
     * the OS keyboard hook is uninstalled, so no other application's keystrokes are ever seen.
     * No-op on runtimes without a desktop backend (Android uses the in-process mixin instead).
     */
    public static synchronized void onFocus(boolean focused) {
        if (stateRef == null) return;
        if (focused) {
            if (capture == null) startBackend(stateRef, switchRef);
        } else {
            stopBackend();
        }
    }

    private static boolean startBackend(InputState state, SwitchManager switchManager) {"""

IPC_STOP_OLD = """    /** Stop the capture backend (best-effort). */
    public static void stop() {
        PhysicalInputCapture backend = capture;"""
IPC_STOP_NEW = """    /** Stop the capture backend (best-effort) and forget the factory arguments. */
    public static void stop() {
        stateRef = null;
        switchRef = null;
        stopBackend();
    }

    private static synchronized void stopBackend() {
        PhysicalInputCapture backend = capture;"""

# ---------------------------------------------------------------- C. ModRuntime
MR_VARIANTS = [
    ("""        inputState.setPaused(client.screen != null);""",
     """        inputState.setPaused(client.screen != null);
        // Desktop capture follows window focus: the OS keyboard hook exists only while the
        // Minecraft window is the foreground window (no interception outside the game).
        if (!android) {
            InProcessCapture.onFocus(inputState.focus());
        }"""),
    ("""        inputState.setPaused(client.gui.screen() != null);""",
     """        inputState.setPaused(client.gui.screen() != null);
        // Desktop capture follows window focus: the OS keyboard hook exists only while the
        // Minecraft window is the foreground window (no interception outside the game).
        if (!android) {
            InProcessCapture.onFocus(inputState.focus());
        }"""),
]


def patch(path, pairs, required=True):
    s = io.open(path, encoding='utf-8').read()
    changed = False
    for old, new in pairs:
        if new.split('\n')[0] in s and old not in s:
            continue  # already applied
        if old not in s:
            if required:
                raise AssertionError('anchor missing in ' + path + ': ' + old.splitlines()[0][:70])
            continue
        s = s.replace(old, new, 1)
        changed = True
    if changed:
        io.open(path, 'w', encoding='utf-8', newline='').write(s)
    return changed


def main(target=None):
    stats = {'jna': 0, 'ffm': 0, 'ipc': 0, 'mr': 0}
    for root, dirs, files in os.walk('.'):
        if target and target not in root.replace(os.sep, '/'):
            dirs[:] = []
            continue
        dirs[:] = [d for d in dirs if d not in SKIP and d != 'build']
        p = os.path.join(root, JNA_HOOK)
        if os.path.isfile(p):
            if patch(p, [(JNA_IMPORT_OLD, JNA_IMPORT_NEW), (JNA_API_OLD, JNA_API_NEW),
                         (JNA_FIELD_OLD, JNA_FIELD_NEW), (JNA_PROC_OLD, JNA_PROC_NEW),
                         (JNA_HELPER_ANCHOR, JNA_HELPER)]):
                stats['jna'] += 1
        p = os.path.join(root, FFM_HOOK)
        if os.path.isfile(p):
            if patch(p, [(FFM_API_OLD, FFM_API_NEW), (FFM_FIELD_ANCHOR, FFM_HELPER),
                         (FFM_PROC_OLD, FFM_PROC_NEW)]):
                stats['ffm'] += 1
        p = os.path.join(root, 'InProcessCapture.java')
        if os.path.isfile(p):
            if patch(p, [(IPC_START_OLD, IPC_START_NEW), (IPC_STOP_OLD, IPC_STOP_NEW)]):
                stats['ipc'] += 1
        p = os.path.join(root, 'ModRuntime.java')
        if os.path.isfile(p):
            s = io.open(p, encoding='utf-8').read()
            if 'InProcessCapture.onFocus' not in s:
                for old, new in MR_VARIANTS:
                    if old in s:
                        io.open(p, 'w', encoding='utf-8', newline='').write(s.replace(old, new, 1))
                        stats['mr'] += 1
                        break
                else:
                    raise AssertionError('ModRuntime anchor missing: ' + p)
    print(stats)


if __name__ == '__main__':
    main(sys.argv[1] if len(sys.argv) > 1 else None)
