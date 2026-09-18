package com.voxkbd.mod.capture;

import com.voxkbd.core.config.ComboPrefixMask;
import com.voxkbd.core.input.InputState;
import com.voxkbd.daemon.input.CapturedKey;
import com.voxkbd.daemon.input.PhysicalInputCapture;
import com.voxkbd.daemon.input.WindowsKeyboardHookJna;
import com.voxkbd.daemon.translate.KeyTranslator;
import com.voxkbd.daemon.translate.TranslateResult;
import com.voxkbd.mod.ModRuntime;
import com.voxkbd.mod.glfw.GlfwDeliverer;
import com.voxkbd.mod.switch_.SwitchManager;

import java.util.Locale;
import java.util.function.Consumer;

/**
 * In-process desktop capture pipeline (single-JAR delivery) — 1.20.x–1.21.x port.
 *
 * <p>Backend selection: on JVMs with the final FFM API (Java 22+) the FFM low-level keyboard hook
 * ({@code com.voxkbd.daemon.input.WindowsKeyboardHook}, loaded reflectively) is preferred; on
 * Java 17/21 runtimes (which older MC mandates) the bundled-JNA equivalent
 * {@link WindowsKeyboardHookJna} is used instead. Both implement the identical
 * suppress/pass-through policy of the 26.2 hook.</p>
 */
public final class InProcessCapture {

    private static volatile PhysicalInputCapture capture;

    private InProcessCapture() {}

    /** Start the in-process capture backend. Returns true if a capture backend is active. */
    public static boolean start(InputState state, SwitchManager switchManager) {
        KeyTranslator translator = new KeyTranslator();
        PhysicalInputCapture backend = chooseCapture(state);
        if (backend == null) {
            ModRuntime.LOGGER.warn("In-process capture unavailable on this OS; virtual keyboards are inactive.");
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
                if (switchManager != null && switchManager.matchesPrefix(mods)
                        && ComboPrefixMask.isDigitToken(k.phys().token())) {
                    switchManager.onComboDigit(k.phys().token(), mods);
                    return;
                }
                GlfwDeliverer.deliver(r.keyboard(), r.phys().token(), k.action(), mods);
            } catch (Throwable t) {
                ModRuntime.LOGGER.error("voxkbd in-process capture sink error", t);
            }
        };
        try {
            backend.start(sink);
        } catch (Throwable t) {
            ModRuntime.LOGGER.error("Failed to start in-process capture", t);
            return false;
        }
        capture = backend;
        ModRuntime.LOGGER.info("Vox Kbd in-process capture started (backend={})",
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
            // FFM hook (Java 22+ runtimes, e.g. some launchers on new MC versions).
            if (Runtime.version().feature() >= 22) {
                try {
                    return (PhysicalInputCapture) Class
                            .forName("com.voxkbd.daemon.input.WindowsKeyboardHook")
                            .getConstructor(InputState.class).newInstance(state);
                } catch (Throwable t) {
                    ModRuntime.LOGGER.warn("FFM low-level keyboard hook unavailable; falling back to JNA", t);
                }
            }
            try {
                return new WindowsKeyboardHookJna(state);
            } catch (Throwable t) {
                ModRuntime.LOGGER.warn("JNA low-level keyboard hook unavailable", t);
            }
        }
        // Non-Windows desktop has no global hook implementation; the in-process path is Windows-only.
        return null;
    }
}
