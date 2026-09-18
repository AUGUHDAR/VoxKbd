package com.voxkbd.daemon;

import com.voxkbd.core.Constants;
import com.voxkbd.core.input.InputState;
import com.voxkbd.core.key.PhysicalKey;
import com.voxkbd.daemon.config.DaemonConfigWatcher;
import com.voxkbd.daemon.input.CapturedKey;
import com.voxkbd.daemon.input.HeadlessCapture;
import com.voxkbd.daemon.input.PhysicalInputCapture;
import com.voxkbd.daemon.input.WindowsKeyboardHook;
import com.voxkbd.daemon.inject.KeyInjector;
import com.voxkbd.daemon.inject.NoopKeyInjector;
import com.voxkbd.daemon.inject.RecordingInjector;
import com.voxkbd.daemon.inject.RobotKeyInjector;
import com.voxkbd.daemon.translate.KeyTranslator;
import com.voxkbd.daemon.translate.TranslateResult;

import java.awt.AWTException;
import java.nio.file.Path;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;

/**
 * Vox Kbd desktop daemon entry point.
 *
 * <p>Lifecycle (§5.5 desktop): connect to the mod's TCP server → handshake → start capture →
 * translate each captured key: LOCKED → OS Robot pass-through; UNLOCKED → forward {@code key_event}
 * to the mod (which delivers the synthetic GLFW code in-process). Mutual supervision: on mod exit
 * or heartbeat timeout, this process exits so the mod can restart it (§3.5 / §4.5).</p>
 */
public final class VoxKbdDaemon {
    private VoxKbdDaemon() {}

    public static void main(String[] args) throws Exception {
        String host = "127.0.0.1";
        int port = 27655;
        String configPath = null;
        int mcPid = -1;
        String ver = Constants.MOD_NAME + "/" + "0.1";
        boolean headless = false;
        boolean stdin = false;

        for (int i = 0; i < args.length; i++) {
            switch (args[i]) {
                case "--host" -> host = args[++i];
                case "--port" -> port = Integer.parseInt(args[++i]);
                case "--config" -> configPath = args[++i];
                case "--mcpid" -> mcPid = Integer.parseInt(args[++i]);
                case "--ver" -> ver = args[++i];
                case "--headless" -> headless = true;
                case "--stdin" -> { headless = true; stdin = true; }
                default -> System.err.println("voxkbd-daemon: unknown arg " + args[i]);
            }
        }

        InputState state = new InputState();
        KeyTranslator translator = new KeyTranslator();

        // Injector for LOCKED pass-through (real OS Robot). Degrade to no-op if unavailable.
        final KeyInjector injector = createInjector();

        CountDownLatch readyLatch = new CountDownLatch(1);
        CountDownLatch stopLatch = new CountDownLatch(1);
        AtomicBoolean stopping = new AtomicBoolean(false);

        DaemonConnection conn = new DaemonConnection(state, host, port, new DaemonConnection.Listener() {
            @Override public void onReady() { readyLatch.countDown(); }
            @Override public void onReload() { System.out.println("voxkbd-daemon: config reload requested"); }
            @Override public void onShutdown() { stopLatch.countDown(); }
            @Override public void onTimeout() {
                System.err.println("voxkbd-daemon: mod peer lost (timeout) — exiting for restart");
                stopLatch.countDown();
            }
        });

        conn.connect(mcPid, ver);
        readyLatch.await(); // block until handshake ack (or process exits on failure)

        // Capture backend: real Windows LL hook on Windows, HeadlessCapture otherwise (test/non-Windows).
        PhysicalInputCapture capture = chooseCapture(state, headless);
        if (capture instanceof HeadlessCapture h && stdin) h.enableStdin();

        Consumer<CapturedKey> sink = (CapturedKey k) -> {
            TranslateResult r = translator.translate(k, state);
            switch (r.outcome()) {
                case TranslateResult.Outcome.IGNORE -> { /* gated / unfocused */ }
                case TranslateResult.Outcome.PASS_THROUGH -> injector.injectPhysical(k.phys(), k.action(), k.mods());
                case TranslateResult.Outcome.SYNTHETIC -> conn.send(
                        com.voxkbd.core.protocol.Message.keyEvent(r.keyboard(), r.phys().token(), k.action(), k.mods()));
            }
        };
        capture.start(sink);

        DaemonConfigWatcher watcher = null;
        if (configPath != null) {
            try {
                watcher = new DaemonConfigWatcher(Path.of(configPath), () ->
                        System.out.println("voxkbd-daemon: config file changed, reloading"));
            } catch (Exception e) {
                System.err.println("voxkbd-daemon: config watch failed: " + e.getMessage());
            }
        }

        System.out.println("voxkbd-daemon: running (activeKeyboard=" + state.activeKeyboard()
                + ", capture=" + capture.getClass().getSimpleName() + ")");

        stopLatch.await(); // wait for shutdown/timeout

        stopping.set(true);
        capture.stop();
        if (watcher != null) watcher.close();
        conn.close();
        System.out.println("voxkbd-daemon: stopped");
        System.exit(0);
    }

    private static KeyInjector createInjector() {
        try {
            return new RobotKeyInjector();
        } catch (AWTException e) {
            System.err.println("voxkbd-daemon: Robot unavailable, locked keys will not be re-injected: " + e.getMessage());
            return new NoopKeyInjector();
        }
    }

    private static PhysicalInputCapture chooseCapture(InputState state, boolean headless) {        if (headless) return new HeadlessCapture();
        String os = System.getProperty("os.name", "").toLowerCase();
        if (os.contains("win")) {
            try {
                return new WindowsKeyboardHook(state);
            } catch (Throwable t) {
                System.err.println("voxkbd-daemon: Windows hook unavailable, using headless capture: " + t.getMessage());
                return new HeadlessCapture();
            }
        }
        System.err.println("voxkbd-daemon: no global hook for OS '" + os + "', using headless capture (verify via --stdin)");
        return new HeadlessCapture();
    }
}
