package com.voxkbd.mod.update;

import net.minecraft.client.Minecraft;
import net.minecraft.network.chat.Component;

import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * GitHub Releases update check — runs at most once per day per instance, fully silent on
 * failure (offline, rate-limited, blocked). The latest release tag is cached in
 * {@code voxkbd-update.json} next to voxkbd.json; a pending announcement is flushed by
 * {@link #tick()} once the player is in a world so the chat line is never lost.
 */
public final class UpdateChecker {

    private static final String RELEASES_API =
            "https://api.github.com/repos/AUGUHDAR/VoxKbd/releases/latest";
    private static final String RELEASES_PAGE = "https://github.com/AUGUHDAR/VoxKbd/releases";
    private static final long INTERVAL_MS = 24L * 60 * 60 * 1000;

    private static volatile boolean started;
    private static volatile String pending;

    private UpdateChecker() {}

    /** Kick the daily check on a daemon thread; never blocks or throws. */
    public static void maybeStart(Path configDir) {
        if (started) return;
        started = true;
        Thread t = new Thread(() -> run(configDir), "voxkbd-update-check");
        t.setDaemon(true);
        t.start();
    }

    /** Flush a pending update announcement into the chat (client-tick hook, cheap no-op). */
    public static void tick() {
        String msg = pending;
        if (msg == null) return;
        Minecraft client = Minecraft.getInstance();
        if (client == null || client.player == null || client.gui == null) return;
        pending = null;
        client.gui.getChat().addMessage(Component.translatable("voxkbd.update.available", msg,
                RELEASES_PAGE));
    }

    private static void run(Path configDir) {
        try {
            Path cache = configDir.resolve("voxkbd-update.json");
            long now = System.currentTimeMillis();
            long last = 0L;
            String seen = "";
            if (Files.exists(cache)) {
                String cached = Files.readString(cache, StandardCharsets.UTF_8);
                Matcher m = Pattern.compile("\"lastCheck\"\\s*:\\s*(\\d+)").matcher(cached);
                if (m.find()) last = Long.parseLong(m.group(1));
                Matcher v = Pattern.compile("\"latest\"\\s*:\\s*\"([^\"]+)\"").matcher(cached);
                if (v.find()) seen = v.group(1);
            }

            String latest = seen;
            if (now - last >= INTERVAL_MS) {
                String fresh = fetchLatest();
                if (!fresh.isEmpty()) {
                    latest = fresh;
                    Files.writeString(cache,
                            "{\"lastCheck\": " + now + ", \"latest\": \"" + fresh + "\"}",
                            StandardCharsets.UTF_8);
                }
            }
            if (latest.isEmpty()) return;

            String current = currentVersion();
            if (current != null && isNewer(latest, current)) {
                pending = latest;
            }
        } catch (Throwable ignored) {
            // Update check is best-effort; every failure stays silent.
        }
    }

    private static String fetchLatest() throws Exception {
        HttpURLConnection c = (HttpURLConnection) URI.create(RELEASES_API).toURL().openConnection();
        c.setConnectTimeout(5000);
        c.setReadTimeout(5000);
        c.setRequestProperty("Accept", "application/vnd.github+json");
        c.setRequestProperty("User-Agent", "VoxKbd-UpdateCheck");
        if (c.getResponseCode() != 200) return "";
        try (InputStream in = c.getInputStream()) {
            String body = new String(in.readAllBytes(), StandardCharsets.UTF_8);
            Matcher m = Pattern.compile("\"tag_name\"\\s*:\\s*\"([^\"]+)\"").matcher(body);
            return m.find() ? m.group(1) : "";
        }
    }

    /** Version of the jar this class was loaded from ("1.0.2" out of "1.0.2-1.21_1.21.5-fabric"). */
    private static String currentVersion() {
        Package pkg = UpdateChecker.class.getPackage();
        String v = pkg == null ? null : pkg.getImplementationVersion();
        if (v == null || v.isBlank()) return null; // dev run: no jar manifest, skip
        int dash = v.indexOf('-');
        return dash > 0 ? v.substring(0, dash) : v;
    }

    private static boolean isNewer(String latest, String current) {
        int[] a = parse(latest);
        int[] b = parse(current);
        if (a == null || b == null) return false;
        for (int i = 0; i < 3; i++) {
            if (a[i] != b[i]) return a[i] > b[i];
        }
        return false;
    }

    /** Pull the first numeric triple out of a version-like string ("v1.2.3" and "1.2.3-rc" both work). */
    private static int[] parse(String v) {
        Matcher m = Pattern.compile("(\\d+)\\.(\\d+)\\.(\\d+)").matcher(v);
        if (!m.find()) return null;
        try {
            return new int[]{Integer.parseInt(m.group(1)),
                    Integer.parseInt(m.group(2)), Integer.parseInt(m.group(3))};
        } catch (NumberFormatException e) {
            return null;
        }
    }
}
