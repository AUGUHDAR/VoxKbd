package com.voxkbd.core.config;

import com.voxkbd.core.Constants;
import com.voxkbd.core.key.PhysicalKeyRegistry;

import java.util.ArrayList;
import java.util.List;

/**
 * Vox Kbd configuration schema (decision D11 / D14 / D15; concrete JSON in §5.4).
 *
 * <p>All fields default to safe values so a missing/partial config still loads. The {@code lock}
 * list is generated dynamically at runtime (never hardcode locked keys — §3.4.3/§3.4.4).</p>
 */
public class Config {

    public int version = Constants.PROTOCOL_VERSION;

    /** Cached Java absolute path (desktop only). Absent on Android. */
    public String javaPath;

    /** Whether to fall back to in-process GLFW mode when Java detection fully fails (D12). */
    public boolean fallbackEnabled = true;

    /** Only enable virtual keyboards while the MC window is focused (D7 / §3.2). */
    public boolean focusGating = true;

    /** Vanilla key lock following (§3.4.2). */
    public boolean vanillaLockFollow = false;

    public SwitchConfig switchCfg = new SwitchConfig();
    public List<KeyboardEntry> keyboards = new ArrayList<>();
    public KeycodeConfig keycode = new KeycodeConfig();
    public LockConfig lock = new LockConfig();

    // ---- nested ----

    public static class SwitchConfig {
        /**
         * "Previous keyboard" keybinding (editable in vanilla Controls, §3.3). Stored either as a
         * physical-key token from the fixed table or as a raw GLFW keycode string. Defaults are
         * unbound-in-vanilla keys so switching works out of the box without conflicts.
         */
        public String previousKey = "91";  // GLFW_KEY_LEFT_BRACKET '['
        /** "Next keyboard" keybinding. */
        public String nextKey = "93";      // GLFW_KEY_RIGHT_BRACKET ']'
        /** "Open config UI" keybinding. */
        public String openConfigKey = "96"; // GLFW_KEY_GRAVE_ACCENT '`'

        /**
         * Combo prefix for "combo + digit" switching (decision D8: fully open). Stored as a
         * bitmask name string, e.g. {@code "CTRL_ALT"}; see {@link ComboPrefixMask}.
         */
        public String comboPrefix = "CTRL_ALT";

        /** Multi-digit debounce window in ms (decision D9). */
        public int comboDigitDebounceMs = 1000;

        public NotificationConfig notification = new NotificationConfig();
    }

    public static class NotificationConfig {
        /** One or more of BOSS_BAR / ACTION_BAR / TITLE (§3.7.1). The Config UI cycles a single mode. */
        public List<String> type = new ArrayList<>(List.of("ACTION_BAR"));
        /** Display duration in seconds (§3.7.1). */
        public int durationSec = 3;
        /** Keep switch status pinned on the home (central keyboard) page (§3.7.1). Legacy boolean. */
        public boolean persistOnHome = false;
        /**
         * "Current keyboard always shown" mode (Config UI tri-state): "OFF" / "ACTION_BAR"
         * (persistent action-bar text) / "TAB" (always-on HUD tab).
         */
        public String alwaysShow = "OFF";
    }

    public static class KeyboardEntry {
        public int id;
        /** Arbitrary user display name; only this may be overridden by a resource pack (D15). */
        public String displayName;
        /** Arbitrary user description, shown next to the name in the Config UI list. */
        public String description;
        /** i18n / resource-pack override key (decision D15). */
        public String nameKey;
        /** Whether resource packs may override {@link #displayName}. */
        public boolean resourcePackOverride = true;
    }

    public static class KeycodeConfig {
        public int base = Constants.DEFAULT_KEYCODE_BASE;
        public int stride = Constants.DEFAULT_KEYCODE_STRIDE;
    }

    public static class LockConfig {
        /** Dynamic snapshot of locked physical-key tokens. Never hardcode (§3.4). */
        public List<String> lockedKeys = new ArrayList<>();
        /** Explicit unlocks of default-locked keys — persisted so 眼见为实 survives restarts. */
        public List<String> unlockedKeys = new ArrayList<>();
    }

    // ---- helpers ----

    /** Look up a virtual keyboard by id, or null. */
    public KeyboardEntry keyboardById(int id) {
        for (KeyboardEntry e : keyboards) if (e.id == id) return e;
        return null;
    }

    /**
     * Merge every field of {@code other} into this instance, keeping object identity so components
     * that captured this Config at startup (lock/switch managers, UIs) observe hot-reloads without
     * re-wiring (D14).
     */
    public void copyFrom(Config other) {
        this.version = other.version;
        this.javaPath = other.javaPath;
        this.fallbackEnabled = other.fallbackEnabled;
        this.focusGating = other.focusGating;
        this.vanillaLockFollow = other.vanillaLockFollow;
        if (other.switchCfg != null) {
            if (this.switchCfg == null) this.switchCfg = new SwitchConfig();
            SwitchConfig o = other.switchCfg, t = this.switchCfg;
            t.previousKey = o.previousKey; t.nextKey = o.nextKey; t.openConfigKey = o.openConfigKey;
            t.comboPrefix = o.comboPrefix; t.comboDigitDebounceMs = o.comboDigitDebounceMs;
            if (o.notification != null) {
                if (t.notification == null) t.notification = new NotificationConfig();
                NotificationConfig on = o.notification, tn = t.notification;
                tn.type = new ArrayList<>(o.notification.type);
                tn.durationSec = on.durationSec;
                tn.persistOnHome = on.persistOnHome;
                tn.alwaysShow = on.alwaysShow;
            }
        }
        this.keyboards = new ArrayList<>(other.keyboards);
        if (other.keycode != null) {
            this.keycode.base = other.keycode.base;
            this.keycode.stride = other.keycode.stride;
        }
        if (other.lock != null && other.lock.lockedKeys != null) {
            this.lock.lockedKeys = new ArrayList<>(other.lock.lockedKeys);
        }
        if (other.lock != null && other.lock.unlockedKeys != null) {
            this.lock.unlockedKeys = new ArrayList<>(other.lock.unlockedKeys);
        }
    }

    /** Validate STRIDE against the physical-key table size after load. */
    public void normalize() {
        if (keycode == null) keycode = new KeycodeConfig();
        if (keycode.stride < PhysicalKeyRegistry.size()) {
            keycode.stride = Math.max(keycode.stride, PhysicalKeyRegistry.size());
        }
        if (switchCfg == null) switchCfg = new SwitchConfig();
        if (switchCfg.notification == null) switchCfg.notification = new NotificationConfig();
        if (lock == null) lock = new LockConfig();
        if (lock.lockedKeys == null) lock.lockedKeys = new ArrayList<>();
        if (lock.unlockedKeys == null) lock.unlockedKeys = new ArrayList<>();
        // Strip any external-config id=0 entry (D20: vanilla baseline is implicit and must never
        // be a VOXKBD_0_* keybinding — silently removing keeps load idempotent).
        if (keyboards != null) keyboards.removeIf(e -> e == null || e.id == Constants.VANILLA_KB_INDEX);
        // No auto-seeded keyboards: virtual keyboards exist only when the player explicitly adds
        // one in the Config UI (their synthetic keybindings surface in vanilla Controls at that
        // moment). A fresh install therefore ships with a completely untouched controls screen.
        // The active layer starts on the vanilla baseline regardless (D20).
    }
}
