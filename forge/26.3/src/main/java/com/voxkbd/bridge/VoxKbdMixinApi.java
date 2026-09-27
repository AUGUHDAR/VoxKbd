package com.voxkbd.bridge;

import com.voxkbd.core.input.InputState;
import com.voxkbd.core.key.KeycodeTable;
import com.voxkbd.core.key.PhysicalKey;
import com.voxkbd.core.key.PhysicalKeyRegistry;
import com.voxkbd.mod.glfw.GlfwDeliverer;
import net.minecraft.client.input.KeyEvent;

/**
 * SDL-era (MC 26.3) in-process translation backend. MC 26.3 runs on SDL3 and has no GLFW key
 * callback to wrap any more; every physical key — desktop SDL event pump or FCL/ZL2 launcher
 * bridge — funnels through {@code KeyboardHandler#keyPress(long, int, KeyEvent)}, which
 * {@code VoxKbdKeyInputMixin} intercepts. Translation happens in place, so no launcher-side
 * injection bridge ({@code FclInputInjector}) is needed on this era.
 *
 * <p>This class is the reflection target of {@code ModRuntime.bindAndroidBackend}
 * ({@link #bindInputState}). Lives OUTSIDE the {@code com.voxkbd.mixin} package: classes in a
 * mixin config package cannot be loaded as regular classes.</p>
 */
public final class VoxKbdMixinApi {

    /** Fallback table until {@code ModRuntime.init} installs the configured one. */
    private static final KeycodeTable DEFAULTS = KeycodeTable.defaults();

    /**
     * Shared runtime input state, populated by {@code voxkbd-mod}. Null-safe: when null the
     * translator is a pure pass-through.
     */
    private static volatile InputState inputState;

    private VoxKbdMixinApi() {}

    /** Called by the mod (via reflection) to hand the translator the shared {@link InputState}. */
    public static void bindInputState(InputState state) {
        inputState = state;
    }

    /**
     * Translate a physical key event into a synthetic VOXKBD event. Returns {@code null} when the
     * event must pass through untouched: backend inactive (desktop or binding failure), unfocused
     * window (D7), open Screen, vanilla baseline (D20, {@code activeKeyboard == 0}), mod-owned
     * switch/config keys (§3.3), reserved or locked physical keys, and already-synthetic codes
     * (re-entrancy guard — covers the re-invocation our own mixin performs).
     */
    public static KeyEvent translate(KeyEvent event) {
        InputState state = inputState;
        if (state == null || !state.inProcessTranslation() || !state.focus() || state.paused()
                || state.activeKeyboard() <= 0) {
            return null;
        }
        int key = event.key();
        KeycodeTable table = GlfwDeliverer.table();
        if (table == null) table = DEFAULTS;
        if (key < 0 || key >= table.base()) {
            return null; // synthetic or out-of-table code — never re-translate
        }
        if (state.isModOwnedKeycode(key)) {
            return null;
        }
        PhysicalKey phys = GlfwKeyMap.physicalKeyOf(key);
        if (phys == null || PhysicalKeyRegistry.isReserved(phys.token())
                || state.isLocked(phys.token())) {
            return null;
        }
        int code = table.keycodeFor(state.activeKeyboard(), phys);
        return new KeyEvent(code, code, event.modifiers());
    }
}
