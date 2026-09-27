package com.voxkbd.bridge;

import com.voxkbd.core.input.InputState;
import com.voxkbd.core.key.KeycodeTable;
import com.voxkbd.core.key.PhysicalKey;
import com.voxkbd.core.key.PhysicalKeyRegistry;
import com.voxkbd.fclbridge.FclInputInjector;
import com.voxkbd.fclbridge.PlatformBridge;
import org.lwjgl.glfw.GLFW;
import org.lwjgl.glfw.GLFWKeyCallback;
import org.lwjgl.glfw.GLFWKeyCallbackI;

/**
 * Plain (non-mixin) API holder backing {@code VoxKbdKeyCallbackMixin}. Mixin classes may only
 * contain private methods (plus annotated handlers), so every piece of real logic lives here and
 * the mixin delegate is a one-liner. Also the reflection target for
 * {@code VoxKbdMod.bindAndroidBackend} ({@link #bindInputState}). Lives OUTSIDE the
 * {@code com.voxkbd.mixin} package: classes in a mixin config package cannot be loaded as regular
 * classes (IllegalClassLoadError "cannot be referenced directly").
 *
 * <h3>Per-event policy (see mixin class for the full decision list)</h3>
 * Translates unlocked, focused, in-process key events into synthetic VOXKBD extended codes;
 * desktop mode ({@code inProcessTranslation == false}), unfocused windows, open Screens, reserved
 * tokens and locked keys all pass through unchanged. Synthetic codes ({@code >= SYNTH_BASE}) are
 * forwarded untouched to prevent re-translation loops.
 */
public final class VoxKbdMixinApi {

    /** Synthetic VOXKBD codes start at this base (mirrors {@code KeycodeTable.defaults().base()}). */
    private static final KeycodeTable KEYCODE_TABLE = KeycodeTable.defaults();
    private static final int SYNTH_BASE = KEYCODE_TABLE.base();

    /**
     * Shared runtime input state, populated by {@code voxkbd-mod}. Null-safe: when null the wrapper
     * is a pure pass-through. This is the single instance both the control side (mod) and the
     * translation side (the mixin wrapper) read/write.
     */
    private static volatile InputState inputState;

    /** Currently installed wrapper and the raw MC callback it wraps (to avoid double-wrapping). */
    private static volatile GLFWKeyCallbackI installed;
    private static volatile GLFWKeyCallbackI wrappedOriginal;

    /** Lazily resolved optional FCL/ZL2 native injection sink (null on plain desktop). */
    private static volatile PlatformBridge sink;
    private static volatile boolean sinkResolved;

    private VoxKbdMixinApi() {}

    /** Called by the mod (via reflection) to hand the wrapper the shared {@link InputState}. */
    public static void bindInputState(InputState state) {
        inputState = state;
    }

    /**
     * Intercept MC's registration of its keyboard callback inside
     * {@code InputConstants.setupKeyboardCallbacks} and install our wrapper instead. Invoked from
     * the mixin's redirect; covers only the key callback — char/preedit/IME flow through untouched.
     */
    public static GLFWKeyCallback wrapInstall(long window, GLFWKeyCallbackI callback) {
        GLFWKeyCallbackI toInstall = callback;
        if (callback != null && callback != installed) {
            // MC is installing a fresh raw callback (first setup, or window recreation): wrap it.
            wrappedOriginal = callback;
            installed = wrap(callback);
            toInstall = installed;
        }
        return GLFW.glfwSetKeyCallback(window, toInstall);
    }

    /**
     * Build the wrapper around MC's real keyboard callback. Never throws: the whole game's keyboard
     * flows through here, so any backend bug falls back to forwarding the untouched event.
     */
    private static GLFWKeyCallbackI wrap(GLFWKeyCallbackI original) {
        return (window, key, scancode, action, mods) -> {
            try {
                int translated = key;
                boolean deliverHere = false;
                InputState state = inputState;
                // Gate 1: only translate when the in-process backend is active (desktop = pass-through).
                // Gate 2: respect window focus (D7), Screen gating and the vanilla baseline (D20).
                // Gate 3: never re-translate an already-synthetic code (re-entrancy guard).
                if (state != null && state.inProcessTranslation() && state.focus()
                        && !state.paused() && state.activeKeyboard() > 0
                        && key >= 0 && key < SYNTH_BASE) {
                    // Mod-owned switch / config bindings are ALWAYS pass-through (§3.3 "防止卡死").
                    if (state.isModOwnedKeycode(key)) {
                        original.invoke(window, key, scancode, action, mods);
                        return;
                    }
                    PhysicalKey phys = GlfwKeyMap.physicalKeyOf(key);
                    if (phys != null && !PhysicalKeyRegistry.isReserved(phys.token())
                            && !state.isLocked(phys.token())) {
                        translated = KEYCODE_TABLE.keycodeFor(state.activeKeyboard(), phys);
                        PlatformBridge sink = resolveSink();
                        if (sink != null) {
                            // FCL/ZL2 native path: re-injects into GLFW, which re-enters this wrapper;
                            // the SYNTH_BASE guard forwards the synthetic code untouched to `original`.
                            sink.inject((int) window, translated, scancode, action, mods);
                            return;
                        }
                        deliverHere = true;
                    }
                }
                original.invoke(window, deliverHere ? translated : key, scancode, action, mods);
            } catch (Throwable t) {
                original.invoke(window, key, scancode, action, mods);
            }
        };
    }

    /** Resolve the optional FCL injection sink exactly once. */
    private static PlatformBridge resolveSink() {
        if (!sinkResolved) {
            synchronized (VoxKbdMixinApi.class) {
                if (!sinkResolved) {
                    sink = FclInputInjector.tryCreate();
                    sinkResolved = true;
                }
            }
        }
        return sink;
    }
}
