# -*- coding: utf-8 -*-
"""清掉 focus-limit 补丁重复应用产生的重复块（每个块只保留第一份）。"""
import io
import os

SKIP = {'其他项目', '.git', 'gradle', 'run'}

BLOCKS = {
    'WindowsKeyboardHookJna.java': [
        # User32 接口里重复追加的三行
        """        Pointer GetForegroundWindow();
        int GetWindowThreadProcessId(Pointer hWnd, IntByReference lpdwProcessId);
        int GetCurrentProcessId();
""",
        # myPid 字段块
        """
    /** This JVM's process id — the hook only ever acts for this process's own window. */
    private final int myPid = currentPid();

    private static int currentPid() {
        try { return User32.INSTANCE.GetCurrentProcessId(); } catch (Throwable t) { return 0; }
    }
""",
        # foregroundIsMinecraft 方法块（JNA 版，可能重复追加）
        """
    /** True only while the OS foreground window belongs to this Minecraft process. */
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
""",
    ],
    'WindowsKeyboardHook.java': [
        """
    private static final MethodHandle GET_FOREGROUND_WINDOW = downcall("GetForegroundWindow",
            FunctionDescriptor.of(ValueLayout.ADDRESS));
    private static final MethodHandle GET_WINDOW_THREAD_PROCESS_ID = downcall("GetWindowThreadProcessId",
            FunctionDescriptor.of(ValueLayout.JAVA_INT, ValueLayout.ADDRESS, ValueLayout.ADDRESS));
    private static final MethodHandle GET_CURRENT_PROCESS_ID = downcall("GetCurrentProcessId",
            FunctionDescriptor.of(ValueLayout.JAVA_INT));
""",
        """
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
    }
""",
    ],
    'ModRuntime.java': [
        """
        // Desktop capture follows window focus: the OS keyboard hook exists only while the
        // Minecraft window is the foreground window (no interception outside the game).
        if (!android) {
            InProcessCapture.onFocus(inputState.focus());
        }
""",
    ],
}

fixed = 0
for root, dirs, files in os.walk('.'):
    dirs[:] = [d for d in dirs if d not in SKIP and d != 'build']
    for name, blocks in BLOCKS.items():
        if name not in files:
            continue
        p = os.path.join(root, name)
        s = io.open(p, encoding='utf-8').read()
        orig = s
        for b in blocks:
            while s.count(b) > 1:
                idx = s.rfind(b)
                s = s[:idx] + s[idx + len(b):]
        if s != orig:
            io.open(p, 'w', encoding='utf-8', newline='').write(s)
            fixed += 1
            print('deduped', p)
print('files fixed:', fixed)
