package com.voxkbd.daemon.translate;

import com.voxkbd.core.key.PhysicalKey;

/**
 * Outcome of translating a captured physical key against the current {@link com.voxkbd.core.input.InputState}.
 *
 * <ul>
 *   <li>{@link #IGNORE}      — not focused / suppressed; daemon does nothing.</li>
 *   <li>{@link #PASS_THROUGH}— key is LOCKED; daemon must OS-inject the physical key so MC receives
 *        it exactly as vanilla (desktop delivery split, §4.0 / Protocol javadoc).</li>
 *   <li>{@link #SYNTHETIC}   — key is unlocked; daemon forwards it to the mod, which delivers the
 *        synthetic extended GLFW code in-process.</li>
 * </ul>
 */
public final class TranslateResult {
    public enum Outcome { IGNORE, PASS_THROUGH, SYNTHETIC }

    public static final TranslateResult IGNORE = new TranslateResult(Outcome.IGNORE, 0, null);
    public static final TranslateResult PASS_THROUGH = new TranslateResult(Outcome.PASS_THROUGH, 0, null);

    public static TranslateResult synthetic(int keyboard, PhysicalKey phys) {
        return new TranslateResult(Outcome.SYNTHETIC, keyboard, phys);
    }

    private final Outcome outcome;
    private final int keyboard;
    private final PhysicalKey phys;

    private TranslateResult(Outcome outcome, int keyboard, PhysicalKey phys) {
        this.outcome = outcome;
        this.keyboard = keyboard;
        this.phys = phys;
    }

    public Outcome outcome() { return outcome; }
    public int keyboard() { return keyboard; }
    public PhysicalKey phys() { return phys; }

    @Override
    public String toString() {
        return "TranslateResult{" + outcome + (outcome == Outcome.SYNTHETIC ? " kb=" + keyboard + " " + phys.token() : "") + "}";
    }
}
