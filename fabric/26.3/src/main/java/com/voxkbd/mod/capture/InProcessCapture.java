package com.voxkbd.mod.capture;

import com.voxkbd.core.config.ComboPrefixMask;
import com.voxkbd.core.input.InputState;
import com.voxkbd.daemon.input.CapturedKey;
import com.voxkbd.daemon.input.PhysicalInputCapture;
import com.voxkbd.daemon.input.WindowsKeyboardHook;
import com.voxkbd.daemon.translate.KeyTranslator;
import com.voxkbd.daemon.translate.TranslateResult;
import com.voxkbd.mod.ModRuntime;
import com.voxkbd.mod.glfw.GlfwDeliverer;
import com.voxkbd.mod.switch_.SwitchManager;

import java.util.Locale;
import java.util.function.Consumer;

/**
 * In-process desktop capture pipeline (single-JAR delivery) — 26.x era.
 *
 * <p>The Windows low-level keyboard hook uses the JDK's built-in Foreign Function &amp; Memory API
 * (final since Java 22), and MC 26.x mandates a Java 25 runtime, so no native binding library is
 * needed and none is bundled.</p>
 */
public final class InProcessCapture {

    private static volatile PhysicalInputCapture capture;

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

    private static boolean startBackend(InputState state, SwitchManager switchManager) {
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
                    return;
                }
                int mods = k.mods();
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

    /** Stop the capture backend (best-effort) and forget the factory arguments. */
    public static void stop() {
        stateRef = null;
        switchRef = null;
        stopBackend();
    }

    private static synchronized void stopBackend() {
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
                ModRuntime.LOGGER.warn("FFM low-level keyboard hook unavailable", t);
            }
        }
        return null;
    }
}
