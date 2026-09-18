package com.voxkbd.daemon.input;

import com.voxkbd.core.key.PhysicalKey;
import com.voxkbd.core.key.PhysicalKeyRegistry;

import java.util.Scanner;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.function.Consumer;

/**
 * Test double for {@link PhysicalInputCapture}. Events are fed programmatically via
 * {@link #feed(PhysicalKey, int, int)} (or {@link #feed(CapturedKey)}), and optionally from
 * stdin lines of the form {@code <TOKEN> <ACTION> <MODS>} (e.g. {@code W 1 0}).
 *
 * <p>Used for headless verification of the capture→translate→forward pipeline without a display
 * or native keyboard hook.</p>
 */
public final class HeadlessCapture implements PhysicalInputCapture {
    private final BlockingQueue<CapturedKey> queue = new LinkedBlockingQueue<>();
    private volatile Consumer<CapturedKey> sink;
    private volatile boolean running;
    private Thread stdinThread;

    /** Push a captured key into the pipeline. Safe to call after {@link #start(Consumer)}. */
    public void feed(CapturedKey key) {
        if (running && sink != null) sink.accept(key);
        else queue.add(key);
    }

    public void feed(PhysicalKey phys, int action, int mods) {
        feed(new CapturedKey(phys, action, mods));
    }

    /** Parse a token string (e.g. "W", "ML", "GP_A") to a PhysicalKey, or null if unknown. */
    public static PhysicalKey parseToken(String token) {
        return PhysicalKeyRegistry.byToken(token.trim().toUpperCase());
    }

    @Override
    public void start(Consumer<CapturedKey> sink) {
        this.sink = sink;
        this.running = true;
        // Drain any events fed before start().
        CapturedKey k;
        while ((k = queue.poll()) != null) sink.accept(k);
    }

    @Override
    public void stop() {
        running = false;
        sink = null;
        if (stdinThread != null) stdinThread.interrupt();
    }

    @Override
    public boolean isRunning() { return running; }

    /** Optional: read "<TOKEN> <ACTION> <MODS>" lines from stdin until the stream ends. */
    public void enableStdin() {
        stdinThread = new Thread(() -> {
            try (Scanner sc = new Scanner(System.in)) {
                while (running && sc.hasNextLine()) {
                    String line = sc.nextLine().trim();
                    if (line.isEmpty()) continue;
                    String[] p = line.split("\\s+");
                    PhysicalKey pk = parseToken(p[0]);
                    if (pk == null) continue;
                    int action = p.length > 1 ? Integer.parseInt(p[1]) : 1;
                    int mods = p.length > 2 ? Integer.parseInt(p[2]) : 0;
                    feed(pk, action, mods);
                }
            } catch (Exception ignored) {
                // stream closed
            }
        }, "voxkbd-headless-stdin");
        stdinThread.setDaemon(true);
        stdinThread.start();
    }
}
