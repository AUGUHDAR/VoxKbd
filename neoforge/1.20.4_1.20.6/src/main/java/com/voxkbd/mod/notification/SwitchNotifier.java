package com.voxkbd.mod.notification;

import com.voxkbd.core.Constants;
import com.voxkbd.core.config.Config;
import com.voxkbd.core.input.InputState;
import com.voxkbd.core.naming.VoxKbdNames;
import com.voxkbd.mod.ui.MasterScreen;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.components.BossHealthOverlay;
import net.minecraft.client.gui.components.LerpingBossEvent;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.BossEvent;

import java.lang.reflect.Field;
import java.util.Map;
import java.util.UUID;

/**
 * Shows the switch notification (decision D10 / §3.7.1): BOSS_BAR / ACTION_BAR / TITLE
 * (1.20.x–1.21.x port; HUD calls route through Minecraft#gui, the InGameHud of these versions).
 *
 * <p>The client-side boss-bar overlay exposes no public add/remove API, so the BOSS_BAR
 * notification registers the {@link LerpingBossEvent} via best-effort reflection into the
 * overlay's internal map; if that map is absent the entry simply does not render (the other two
 * notification types are unaffected).</p>
 */
public final class SwitchNotifier {

    private static final ResourceLocation BOSS_BAR_ID =
            new ResourceLocation("voxkbd", "switch");
    private static final UUID BOSS_BAR_UUID = UUID.nameUUIDFromBytes(BOSS_BAR_ID.toString().getBytes());

    private final Config config;
    private final InputState inputState;
    private LerpingBossEvent bossBar;
    private int bossBarTicksLeft;
    private int titleTicksLeft;
    private int alwaysShowTicks;

    public SwitchNotifier(Config config, InputState inputState) {
        this.config = config;
        this.inputState = inputState;
    }

    /** Notify that the active keyboard changed to {@code kb}. */
    public void notify(int kb) {
        Config.NotificationConfig n = config.switchCfg.notification;
        if (n == null || n.type == null || n.type.isEmpty()) return;

        Component text = Component.translatable("voxkbd.ui.switch_to", MasterScreen.displayName(kb));

        boolean persist = n.persistOnHome;
        int ticks = n.durationSec * 20;

        for (String type : n.type) {
            switch (type) {
                case "ACTION_BAR" -> {
                    Minecraft client = Minecraft.getInstance();
                    if (client != null && client.gui != null) {
                        client.gui.setOverlayMessage(text, false);
                    }
                }
                case "TITLE" -> {
                    Minecraft client = Minecraft.getInstance();
                    if (client != null && client.gui != null) {
                        client.gui.setTitle(text);
                        client.gui.setSubtitle(Component.empty());
                        client.gui.setTimes(10, Math.max(10, ticks - 20), 10);
                        titleTicksLeft = persist ? Integer.MAX_VALUE : ticks;
                    }
                }
                case "BOSS_BAR" -> {
                    Minecraft client = Minecraft.getInstance();
                    if (client != null && client.gui != null) {
                        if (bossBar == null) {
                            bossBar = createBossBar(text);
                            addBossBarToHud(bossBar);
                        }
                        bossBar.setName(text);
                        bossBar.setProgress(1.0f);
                        bossBarTicksLeft = persist ? Integer.MAX_VALUE : ticks;
                    }
                }
                default -> { /* unknown type ignored */ }
            }
        }
    }

    /** Version-dependent LerpingBossEvent construction (isolated for per-group overrides). */
    private static LerpingBossEvent createBossBar(Component text) {
        return new LerpingBossEvent(BOSS_BAR_UUID, text, 1.0f,
                BossEvent.BossBarColor.BLUE, BossEvent.BossBarOverlay.PROGRESS,
                false, false, false);
    }

    /**
     * Drive expiry of TITLE / BOSS_BAR (call every client tick) and refresh the "current keyboard
     * always shown" action-bar mode (re-issued overlay message every second because the vanilla
     * overlay fades after ~2s; the tap-bar mode lives in {@link CurrentKbTab}).
     */
    public void tick() {
        Config.NotificationConfig n = config.switchCfg == null ? null : config.switchCfg.notification;
        if (n != null && "ACTION_BAR".equals(n.alwaysShow)
                && inputState != null && inputState.activeKeyboard() != Constants.VANILLA_KB_INDEX) {
            Minecraft client = Minecraft.getInstance();
            if (client != null && client.gui != null
                    && client.screen == null && ++alwaysShowTicks % 20 == 0) {
                client.gui.setOverlayMessage(
                        Component.translatable("voxkbd.ui.active", MasterScreen.displayName(
                                inputState.activeKeyboard())), false);
            }
        } else {
            alwaysShowTicks = 0;
        }
        if (titleTicksLeft > 0 && titleTicksLeft != Integer.MAX_VALUE) {
            if (--titleTicksLeft == 0) {
                Minecraft client = Minecraft.getInstance();
                if (client != null && client.gui != null) {
                    client.gui.setTitle(Component.empty());
                    client.gui.setSubtitle(Component.empty());
                }
            }
        }
        if (bossBarTicksLeft > 0 && bossBarTicksLeft != Integer.MAX_VALUE) {
            if (--bossBarTicksLeft == 0 && bossBar != null) {
                removeBossBarFromHud();
                bossBar = null;
            }
        }
    }

    /** Register a boss bar on the HUD by writing into BossHealthOverlay's private events map. */
    private void addBossBarToHud(LerpingBossEvent bar) {
        Minecraft client = Minecraft.getInstance();
        if (client == null || client.gui == null) return;
        try {
            BossHealthOverlay overlay = client.gui.getBossOverlay();
            Field field = overlayField();
            if (field == null) return;
            @SuppressWarnings("unchecked")
            Map<UUID, LerpingBossEvent> bars = (Map<UUID, LerpingBossEvent>) field.get(overlay);
            bars.put(BOSS_BAR_UUID, bar);
        } catch (ReflectiveOperationException | RuntimeException ignored) {
            // Field layout changed; notification simply won't show.
        }
    }

    /** Remove a previously registered boss bar from the HUD. */
    private void removeBossBarFromHud() {
        Minecraft client = Minecraft.getInstance();
        if (client == null || client.gui == null) return;
        try {
            BossHealthOverlay overlay = client.gui.getBossOverlay();
            Field field = overlayField();
            if (field == null) return;
            @SuppressWarnings("unchecked")
            Map<UUID, LerpingBossEvent> bars = (Map<UUID, LerpingBossEvent>) field.get(overlay);
            bars.remove(BOSS_BAR_UUID);
        } catch (ReflectiveOperationException | RuntimeException ignored) {
            // Field layout changed; nothing to clean up.
        }
    }

    /** Resolve BossHealthOverlay's events map field (official name "events"; SRG fallback scanned). */
    private static volatile Field fieldCache;

    private static Field overlayField() throws ReflectiveOperationException {
        Field f = fieldCache;
        if (f != null) return f;
        try {
            f = BossHealthOverlay.class.getDeclaredField("events");
        } catch (NoSuchFieldException e) {
            // SRG/obfuscated runtime: scan for the Map<UUID, LerpingBossEvent> field.
            for (Field cand : BossHealthOverlay.class.getDeclaredFields()) {
                if (Map.class.isAssignableFrom(cand.getType())) {
                    cand.setAccessible(true);
                    f = cand;
                    break;
                }
            }
            if (f == null) return null;
        }
        f.setAccessible(true);
        fieldCache = f;
        return f;
    }
}
