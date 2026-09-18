package com.voxkbd.daemon.translate;

import com.voxkbd.core.input.InputState;
import com.voxkbd.daemon.input.CapturedKey;

/**
 * Pure translation logic (no I/O). Decides, for a captured physical key and the current runtime
 * {@link InputState}, whether to ignore it, pass it through as a vanilla key, or forward it as a
 * synthetic virtual-keyboard key.
 *
 * <p>Rules (decision D2 / D4 / D7 / D16, §5.6):</p>
 * <ol>
 *   <li>If the MC window is not focused → {@link TranslateResult.Outcome#IGNORE} (focus gating).</li>
 *   <li>If the physical key is locked → {@link TranslateResult.Outcome#PASS_THROUGH} (always vanilla).</li>
 *   <li>Otherwise → {@link TranslateResult.Outcome#SYNTHETIC} for the active virtual keyboard.</li>
 * </ol>
 *
 * <p>This class is intentionally independent of capture/injection so it can be unit-tested headless.</p>
 */
public final class KeyTranslator {

    /** Translate a captured key against the current state. Returns {@link TranslateResult#IGNORE} when unfocused. */
    public TranslateResult translate(CapturedKey key, InputState state) {
        if (state == null || !state.focus()) {
            return TranslateResult.IGNORE;
        }
        // Mod-owned switch / config bindings ALWAYS pass through (§3.3 "防止卡死") — even when the
        // physical key is outside the PhysicalKeyRegistry table (e.g. `[`, `]`, `` ` ``).
        if (state.isModOwnedKeycode(key.glfwKey())) {
            return TranslateResult.PASS_THROUGH;
        }
        // Vanilla baseline active (D20): every physical key behaves exactly like vanilla.
        if (state.activeKeyboard() == com.voxkbd.core.Constants.VANILLA_KB_INDEX) {
            return TranslateResult.PASS_THROUGH;
        }
        // 眼见为实 (v0.5): the lock is the single source of truth — locked = vanilla, unlocked =
        // synthetic. NO exceptions, not even while the vanilla key-binds screen captures a binding:
        // a red key in the UI MUST register as its vanilla key, an unlocked key MUST register as
        // its synthetic key. (bindCapture only lifts the capture backends' GUI pass-through so
        // unlocked keys reach this translator at all while a screen is open.)
        if (state.isLocked(key.phys().token())) {
            return TranslateResult.PASS_THROUGH;
        }
        return TranslateResult.synthetic(state.activeKeyboard(), key.phys());
    }
}
