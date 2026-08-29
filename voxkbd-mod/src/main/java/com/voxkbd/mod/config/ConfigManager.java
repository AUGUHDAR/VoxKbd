package com.voxkbd.mod.config;

import com.voxkbd.core.Constants;
import com.voxkbd.core.config.Config;
import com.voxkbd.core.io.Json;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.Reader;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileSystems;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardWatchEventKinds;
import java.nio.file.WatchEvent;
import java.nio.file.WatchKey;
import java.nio.file.WatchService;
import java.nio.file.ClosedWatchServiceException;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.function.Consumer;

/**
 * Loads / saves / hot-reloads the Vox Kbd {@link Config} as JSON (decision D11 / D14).
 *
 * <p>Resides in {@code .minecraft/voxkbd.json} (or the supplied config directory). {@link Config#normalize()}
 * is always applied after load so a partial/missing file still produces a valid config. A {@link WatchService}
 * watches the file for external edits and re-loads + notifies listeners (hot-reload, §4.4 / D14).</p>
 *
 * <p>This class is loader-agnostic (only JDK + core); it is part of the javac-verifiable surface of
 * voxkbd-mod.</p>
 */
public final class ConfigManager {

    private final Path configPath;
    private volatile Config config;
    private volatile WatchService watchService;
    private final ExecutorService watcherThread = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "voxkbd-config-watcher");
        t.setDaemon(true);
        return t;
    });
    private final List<Consumer<Config>> reloadListeners = new ArrayList<>();
    private volatile boolean watching = false;

    /**
     * @param configDir directory that should contain {@link Constants#CONFIG_FILE_NAME}
     *                  (typically {@code .minecraft/config} or {@code .minecraft}).
     */
    public ConfigManager(Path configDir) {
        this.configPath = configDir.resolve(Constants.CONFIG_FILE_NAME);
    }

    public Path configPath() {
        return configPath;
    }

    /** Load (or create a default) config. Safe to call before {@link #startWatcher()}. */
    public Config load() throws IOException {
        if (Files.exists(configPath)) {
            try (Reader r = Files.newBufferedReader(configPath, StandardCharsets.UTF_8)) {
                Config parsed = Json.GSON.fromJson(r, Config.class);
                if (parsed != null) {
                    config = parsed;
                }
            }
        }
        if (config == null) {
            config = new Config();
        }
        config.normalize();
        return config;
    }

    public Config get() {
        if (config == null) {
            throw new IllegalStateException("ConfigManager.load() must be called first");
        }
        return config;
    }

    /** Persist the current config (pretty-printed). Creates parent dirs as needed. */
    public void save() throws IOException {
        if (config == null) return;
        config.normalize();
        Path parent = configPath.getParent();
        if (parent != null) Files.createDirectories(parent);
        try (Writer w = Files.newBufferedWriter(configPath, StandardCharsets.UTF_8)) {
            Json.GSON_PRETTY.toJson(config, w);
        }
    }

    /** Register a listener invoked on external (file-watch) reloads. */
    public void addReloadListener(Consumer<Config> listener) {
        reloadListeners.add(listener);
    }

    /** Begin watching the config file for external edits (hot-reload). Idempotent. */
    public void startWatcher() throws IOException {
        if (watching) return;
        Path dir = configPath.getParent();
        if (dir == null) return;
        if (!Files.exists(dir)) Files.createDirectories(dir);
        watchService = FileSystems.getDefault().newWatchService();
        dir.register(watchService, StandardWatchEventKinds.ENTRY_MODIFY, StandardWatchEventKinds.ENTRY_CREATE);
        watching = true;
        watcherThread.submit(this::watchLoop);
    }

    public void stopWatcher() {
        watching = false;
        try {
            if (watchService != null) watchService.close();
        } catch (IOException ignored) {
            // best-effort
        }
    }

    private void watchLoop() {
        while (watching && watchService != null) {
            WatchKey key;
            try {
                key = watchService.take();
            } catch (InterruptedException | ClosedWatchServiceException e) {
                Thread.currentThread().interrupt();
                break;
            }
            for (WatchEvent<?> event : key.pollEvents()) {
                Path changed = configPath.getParent().resolve((Path) event.context());
                if (changed.equals(configPath) && event.kind() == StandardWatchEventKinds.ENTRY_MODIFY) {
                    reloadFromDisk();
                }
            }
            if (!key.reset()) break;
        }
    }

    private void reloadFromDisk() {
        try {
            Config reloaded;
            try (BufferedReader r = Files.newBufferedReader(configPath, StandardCharsets.UTF_8)) {
                reloaded = Json.GSON.fromJson(r, Config.class);
            }
            if (reloaded == null) return;
            // Merge into the EXISTING instance: managers/UIs captured it at startup and must see
            // hot-reloaded values without re-wiring (object identity preserved, D14).
            Config current = config;
            if (current == null) return;
            synchronized (current) {
                current.copyFrom(reloaded);
                current.normalize();
            }
            for (Consumer<Config> l : reloadListeners) {
                try {
                    l.accept(current);
                } catch (RuntimeException ex) {
                    // a misbehaving listener must not kill the watch loop
                }
            }
        } catch (IOException ignored) {
            // concurrent write in progress; ignore this cycle
        }
    }
}
