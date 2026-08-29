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
import net.minecraft.resources.Identifier;
import net.minecraft.world.BossEvent;

import java.lang.reflect.Field;
import java.util.Map;
import java.util.UUID;

/**
 * Shows the switch notification (decision D10 / §3.7.1): BOSS_BAR / ACTION_BAR / TITLE (multi-select),
 * with configurable duration and optional long-lived pin on the home (central keyboard) page.
 *
 * <p>All visible text is produced via translation keys (§3.8); the message reads
 * {@code voxkbd.ui.switch_to} with the keyboard display name. Boss-bar / title entries auto-expire
 * after {@code durationSec} (driven by {@link #tick()} from the client tick loop).</p>
 *
 * <p>Note: in 26.2 the client-side boss-bar overlay ({@code BossHealthOverlay}) exposes no public
 * add/remove API (boss bars are server-driven), so the BOSS_BAR notification registers the
 * {@link LerpingBossEvent} via best-effort reflection into the overlay's internal map; if that map is
 * absent the entry simply does not render (the other two notification types are unaffected).</p>
 */
public final class SwitchNotifier {

    private static final Identifier BOSS_BAR_ID = Identifier.fromNamespaceAndPath("voxkbd", "switch");
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
                    if (client != null && client.gui != null && client.gui.hud != null) {
                        client.gui.hud.setOverlayMessage(text, false);
                    }
                }
                case "TITLE" -> {
                    Minecraft client = Minecraft.getInstance();
                    if (client != null && client.gui != null && client.gui.hud != null) {
                        client.gui.hud.setTitle(text);
                        client.gui.hud.setSubtitle(Component.empty());
                        client.gui.hud.setTimes(10, Math.max(10, ticks - 20), 10);
                        titleTicksLeft = persist ? Integer.MAX_VALUE : ticks;
                    }
                }
                case "BOSS_BAR" -> {
                    Minecraft client = Minecraft.getInstance();
                    if (client != null && client.gui != null && client.gui.hud != null) {
                        if (bossBar == null) {
                            bossBar = new LerpingBossEvent(BOSS_BAR_UUID, text, 1.0f,
                                    BossEvent.BossBarColor.BLUE, BossEvent.BossBarOverlay.PROGRESS,
                                    false, false, false);
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

    /**
     * Drive expiry of TITLE / BOSS_BAR (call every client tick) and refresh the "current keyboard
     * always shown" action-bar mode (tri-state 关 / 动作栏 / tap栏 — the tap bar lives in
     * {@link AlwaysShowHud}; the action bar is a re-issued overlay message, refreshed every second
     * because the vanilla overlay fades after ~2s).
     */
    public void tick() {
        Config.NotificationConfig n = config.switchCfg == null ? null : config.switchCfg.notification;
        if (n != null && "ACTION_BAR".equals(n.alwaysShow)
                && inputState != null && inputState.activeKeyboard() != Constants.VANILLA_KB_INDEX) {
            Minecraft client = Minecraft.getInstance();
            if (client != null && client.gui != null && client.gui.hud != null
                    && client.gui.screen() == null && ++alwaysShowTicks % 20 == 0) {
                client.gui.hud.setOverlayMessage(
                        Component.translatable("voxkbd.ui.active", MasterScreen.displayName(
                                inputState.activeKeyboard())), false);
            }
        } else {
            alwaysShowTicks = 0;
        }
        if (titleTicksLeft > 0 && titleTicksLeft != Integer.MAX_VALUE) {
            if (--titleTicksLeft == 0) {
                Minecraft client = Minecraft.getInstance();
                if (client != null && client.gui != null && client.gui.hud != null) {
                    client.gui.hud.setTitle(Component.empty());
                    client.gui.hud.setSubtitle(Component.empty());
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
        if (client == null || client.gui == null || client.gui.hud == null) return;
        try {
            BossHealthOverlay overlay = client.gui.hud.getBossOverlay();
            Field field = BossHealthOverlay.class.getDeclaredField("events");
            field.setAccessible(true);
            @SuppressWarnings("unchecked")
            Map<UUID, LerpingBossEvent> bars = (Map<UUID, LerpingBossEvent>) field.get(overlay);
            bars.put(BOSS_BAR_UUID, bar);
        } catch (ReflectiveOperationException ignored) {
            // Field layout changed; notification simply won't show.
        }
    }

    /** Remove a previously registered boss bar from the HUD. */
    private void removeBossBarFromHud() {
        Minecraft client = Minecraft.getInstance();
        if (client == null || client.gui == null || client.gui.hud == null) return;
        try {
            BossHealthOverlay overlay = client.gui.hud.getBossOverlay();
            Field field = BossHealthOverlay.class.getDeclaredField("events");
            field.setAccessible(true);
            @SuppressWarnings("unchecked")
            Map<UUID, LerpingBossEvent> bars = (Map<UUID, LerpingBossEvent>) field.get(overlay);
            bars.remove(BOSS_BAR_UUID);
        } catch (ReflectiveOperationException ignored) {
            // Field layout changed; nothing to clean up.
        }
    }
}
