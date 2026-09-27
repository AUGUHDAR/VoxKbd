package com.voxkbd.daemon.config;

import com.voxkbd.core.io.Json;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.FileSystems;
import java.nio.file.Path;
import java.nio.file.WatchEvent;
import java.nio.file.WatchKey;
import java.nio.file.WatchService;
import java.util.concurrent.atomic.AtomicBoolean;

import static java.nio.file.StandardWatchEventKinds.ENTRY_MODIFY;
import static java.nio.file.StandardWatchEventKinds.ENTRY_CREATE;

/**
 * Watches the config file for changes and triggers a reload callback (decision D14: hot-reload
 * without restarting the process). Uses a {@link WatchService} on the parent directory, filtered to
 * the specific file. The mod also pushes {@code reload} messages; both paths call the same callback.
 */
public final class DaemonConfigWatcher implements AutoCloseable {
    private final Path file;
    private final Runnable onReload;
    private final WatchService watch;
    private final Thread thread;
    private final AtomicBoolean running = new AtomicBoolean(true);

    public DaemonConfigWatcher(Path configFile, Runnable onReload) throws IOException {
        this.file = configFile;
        this.onReload = onReload;
        this.watch = FileSystems.getDefault().newWatchService();
        Path dir = configFile.toAbsolutePath().getParent();
        if (dir != null) dir.register(watch, ENTRY_MODIFY, ENTRY_CREATE);
        this.thread = new Thread(this::run, "voxkbd-config-watch");
        this.thread.setDaemon(true);
        this.thread.start();
    }

    private void run() {
        while (running.get()) {
            WatchKey key = watch.poll();
            if (key == null) {
                try { Thread.sleep(200); } catch (InterruptedException ignored) { break; }
                continue;
            }
            for (WatchEvent<?> ev : key.pollEvents()) {
                Path changed = ((WatchEvent<Path>) ev).context();
                if (changed != null && changed.getFileName().equals(file.getFileName())) {
                    onReload.run();
                }
            }
            key.reset();
        }
    }

    @Override
    public void close() {
        running.set(false);
        try { watch.close(); } catch (IOException ignored) {}
        thread.interrupt();
    }

    /** Load the current config as a core {@link com.voxkbd.core.config.Config}. */
    public static com.voxkbd.core.config.Config load(Path configFile) {
        try {
            String text = java.nio.file.Files.readString(configFile);
            com.voxkbd.core.config.Config c = Json.GSON.fromJson(text, com.voxkbd.core.config.Config.class);
            if (c != null) c.normalize();
            return c;
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
