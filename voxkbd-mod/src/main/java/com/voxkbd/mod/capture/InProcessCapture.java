package com.voxkbd.mod.capture;

import com.voxkbd.core.config.ComboPrefixMask;
import com.voxkbd.core.input.InputState;
import com.voxkbd.daemon.input.CapturedKey;
import com.voxkbd.daemon.input.PhysicalInputCapture;
import com.voxkbd.daemon.input.WindowsKeyboardHook;
import com.voxkbd.daemon.translate.KeyTranslator;
import com.voxkbd.daemon.translate.TranslateResult;
import com.voxkbd.mod.VoxKbdMod;
import com.voxkbd.mod.glfw.GlfwDeliverer;
import com.voxkbd.mod.switch_.SwitchManager;

import java.util.Locale;
import java.util.function.Consumer;

/**
 * In-process desktop capture pipeline (single-JAR delivery, decision D12 / §4.0).
 *
 * <p>Collapses the old external {@code voxkbd-daemon} process into the mod: the mod itself installs the
 * Windows low-level keyboard hook, translates each captured physical key against the shared
 * {@link InputState}, and delivers the synthetic {@code VOXKBD_<kb>_<phys>} keycode directly into MC's
 * GLFW via {@link GlfwDeliverer} — no TCP socket and no second downloadable file. The hook suppresses
 * native delivery for unlocked keys and lets locked keys pass through to MC as vanilla (§4.0).</p>
 *
 * <p>The same {@link InputState} instance is shared with the rest of the mod, so focus gating
 * ({@code FocusListener}) and the lock set ({@code LockManager}) take effect immediately.</p>
 */
public final class InProcessCapture {

    private static volatile PhysicalInputCapture capture;

    private InProcessCapture() {}

    /** Start the in-process capture backend. Returns true if a capture backend is active. */
    public static boolean start(InputState state, SwitchManager switchManager) {
        KeyTranslator translator = new KeyTranslator();
        PhysicalInputCapture backend = chooseCapture(state);
        if (backend == null) {
            VoxKbdMod.LOGGER.warn("In-process capture unavailable on this OS; virtual keyboards are inactive.");
            return false;
        }
        Consumer<CapturedKey> sink = (CapturedKey k) -> {
            try {
                TranslateResult r = translator.translate(k, state);
                if (r.outcome() != TranslateResult.Outcome.SYNTHETIC) {
                    // IGNORE (unfocused) or PASS_THROUGH (locked): the native event was already handled.
                    return;
                }
                int mods = k.mods();
                // Combo + digit switching (D8 / D9): only when the prefix is held and the key is a digit.
                // The hook forwards prefix+digit even when locked, so hotbar-bound digits still switch.
                if (switchManager != null && switchManager.matchesPrefix(mods)
                        && ComboPrefixMask.isDigitToken(k.phys().token())) {
                    switchManager.onComboDigit(k.phys().token(), mods);
                    return;
                }
                GlfwDeliverer.deliver(r.keyboard(), r.phys().token(), k.action(), mods);
            } catch (Throwable t) {
                VoxKbdMod.LOGGER.error("voxkbd in-process capture sink error", t);
            }
        };
        try {
            backend.start(sink);
        } catch (Throwable t) {
            VoxKbdMod.LOGGER.error("Failed to start in-process capture", t);
            return false;
        }
        capture = backend;
        VoxKbdMod.LOGGER.info("Vox Kbd in-process capture started (backend={})",
                backend.getClass().getSimpleName());
        return true;
    }

    /** Stop the capture backend (best-effort). */
    public static void stop() {
        PhysicalInputCapture backend = capture;
        if (backend != null) {
            try {
                backend.stop();
            } catch (Throwable ignored) {
                // best-effort
            }
            capture = null;
        }
    }

    private static PhysicalInputCapture chooseCapture(InputState state) {
        String os = System.getProperty("os.name", "").toLowerCase(Locale.ROOT);
        if (os.contains("win")) {
            try {
                return new WindowsKeyboardHook(state);
            } catch (Throwable t) {
                VoxKbdMod.LOGGER.warn("Windows low-level keyboard hook unavailable", t);
            }
        }
        // Non-Windows desktop has no global hook implementation; the in-process path is Windows-only.
        return null;
    }
}
