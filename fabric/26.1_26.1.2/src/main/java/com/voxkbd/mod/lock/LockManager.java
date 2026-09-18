package com.voxkbd.mod.lock;

import com.mojang.blaze3d.platform.InputConstants;
import com.voxkbd.core.config.ComboPrefixMask;
import com.voxkbd.core.config.Config;
import com.voxkbd.core.input.InputState;
import com.voxkbd.core.key.PhysicalKey;
import com.voxkbd.core.key.PhysicalKeyRegistry;
import com.voxkbd.mod.config.ConfigManager;
import com.voxkbd.mod.ModRuntime;
import com.voxkbd.mod.glfw.KeybindingRegistry;
import com.voxkbd.mixin.KeyMappingAccessor;
import net.minecraft.client.Minecraft;
import net.minecraft.client.KeyMapping;

import java.util.HashSet;
import java.util.Map;
import java.util.Set;

/**
 * Builds the dynamic lock snapshot (decision D4 / §3.4) — 1.20.x–1.21.x port. Same semantics as
 * the 26.2 implementation; differences:
 * <ul>
 *   <li>the live binding is read via the {@link KeyMappingAccessor} mixin (on intermediary / SRG
 *       runtimes a reflection-by-name lookup would miss the obfuscated {@code key} field);</li>
 *   <li>the mod's own category is the plain string {@link KeybindingRegistry#CATEGORY}.</li>
 * </ul>
 */
public final class LockManager {

    /** i18n key returned when an unlock is blocked by vanilla-lock-following (§3.4.2). */
    public static final String LOCK_FOLLOW_WARNING_KEY = "voxkbd.ui.lock_follow_warning";

    /** i18n key returned when an unlock is blocked because the key is a mod-owned switch binding. */
    public static final String SWITCH_KEY_PROTECTED_KEY = "voxkbd.ui.switch_key_protected";

    private final Config config;
    private final InputState inputState;
    private final Runnable statePusher;
    private final ConfigManager configManager;

    /** Explicit user locks (left-click); persisted. */
    private final Set<String> uiLocked = new HashSet<>();
    /** Explicit user unlocks of default-locked keys (only honoured when vanilla-lock-following is off). */
    private final Set<String> uiUnlocked = new HashSet<>();

    private boolean dirty = false;

    private static final Map<Integer, String> KB_GLFW_TO_TOKEN = new java.util.HashMap<>();
    private static final Map<Integer, String> MOUSE_GLFW_TO_TOKEN = new java.util.HashMap<>();

    static {
        for (PhysicalKey pk : PhysicalKeyRegistry.all()) {
            if (pk.category() == PhysicalKey.Category.KEYBOARD) KB_GLFW_TO_TOKEN.put(pk.glfwCode(), pk.token());
            else if (pk.category() == PhysicalKey.Category.MOUSE) MOUSE_GLFW_TO_TOKEN.put(pk.glfwCode(), pk.token());
        }
    }

    public LockManager(Config config, InputState inputState, Runnable statePusher, ConfigManager configManager) {
        this.config = config;
        this.inputState = inputState;
        this.statePusher = statePusher;
        this.configManager = configManager;
        if (config.lock != null && config.lock.lockedKeys != null) {
            uiLocked.addAll(config.lock.lockedKeys);
        }
        if (config.lock != null && config.lock.unlockedKeys != null) {
            uiUnlocked.addAll(config.lock.unlockedKeys);
        }
        recompute();
    }

    /** Current effective locked-token set (for rendering the Master UI). */
    public Set<String> currentLocked() {
        return new HashSet<>(computeLockedKeys());
    }

    public boolean isDirty() {
        return dirty;
    }

    /** Lock a physical key (left-click in Master UI). */
    public void lock(String token) {
        uiLocked.add(token);
        uiUnlocked.remove(token);
        markDirty();
        persist();
        recompute();
    }

    /**
     * Unlock a physical key (right-click). Returns an i18n key when blocked, or {@code null} on
     * success (vanilla-lock-following, mod-owned switch bindings, combo prefix modifiers).
     */
    public String unlock(String token) {
        if (config.vanillaLockFollow && collectVanillaBoundTokens().contains(token)) {
            return LOCK_FOLLOW_WARNING_KEY;
        }
        if (collectModOwnedSwitchTokens().contains(token)) {
            return SWITCH_KEY_PROTECTED_KEY;
        }
        if (getComboPrefixModifiers().contains(token)) {
            return LOCK_FOLLOW_WARNING_KEY;
        }
        uiLocked.remove(token);
        uiUnlocked.add(token);
        markDirty();
        persist();
        recompute();
        return null;
    }

    /**
     * Real-time persistence (v0.5): mirror BOTH explicit sets into the config and write the JSON
     * to disk immediately — the file must always match what the UI shows.
     */
    private void persist() {
        config.lock.lockedKeys = new java.util.ArrayList<>(uiLocked);
        config.lock.unlockedKeys = new java.util.ArrayList<>(uiUnlocked);
        try {
            configManager.save();
        } catch (java.io.IOException e) {
            ModRuntime.LOGGER.warn("VoxKbd: real-time config save failed; in-memory state preserved", e);
        }
    }

    /** Persist explicit locks (Save button — the state is already on disk via {@link #persist()}). */
    public void markSaved() {
        persist();
        dirty = false;
    }

    /** Revert to the last saved lock state (Undo button). */
    public void undo() {
        java.util.List<String> locked = config.lock.lockedKeys != null ? config.lock.lockedKeys : java.util.List.of();
        java.util.List<String> unlocked = config.lock.unlockedKeys != null ? config.lock.unlockedKeys : java.util.List.of();
        uiLocked.clear();
        uiLocked.addAll(locked);
        uiUnlocked.clear();
        uiUnlocked.addAll(unlocked);
        dirty = false;
        recompute();
    }

    /** Re-derive the dynamic lock set from the CURRENT vanilla bindings and re-apply it. */
    public void refreshVanillaLocks() {
        recompute();
    }

    /**
     * Force-unlock a physical key because a function was just bound to its synthetic key
     * (bind-capture flow, v0.5). Returns true when the state actually changed.
     */
    public boolean unlockForBinding(String token) {
        if (token == null || uiUnlocked.contains(token)) return false;
        uiLocked.remove(token);
        uiUnlocked.add(token);
        markDirty();
        persist();
        recompute();
        return true;
    }

    private void markDirty() {
        dirty = true;
    }

    private void recompute() {
        Set<String> locked = computeLockedKeys();
        inputState.setLocked(locked);
        inputState.setModOwnedKeycodes(collectModOwnedKeycodes());
        if (statePusher != null) statePusher.run();
    }

    /** Effective locked set: default (vanilla-bound) + combo prefix modifiers + mod-owned switch
     *  bindings + explicit locks, minus allowed unlocks. */
    private Set<String> computeLockedKeys() {
        Set<String> vanilla = collectVanillaBoundTokens();
        Set<String> locked = new HashSet<>();
        for (String v : vanilla) {
            if (config.vanillaLockFollow) {
                locked.add(v);
            } else if (!uiUnlocked.contains(v)) {
                locked.add(v);
            }
        }
        locked.addAll(getComboPrefixModifiers());
        locked.addAll(collectModOwnedSwitchTokens());
        locked.addAll(uiLocked);
        return locked;
    }

    /** All physical keys currently bound to a vanilla function (dynamic, never hardcoded).
     *  Excludes the mod's own KeyBindings. Reads the live {@code key} field via the accessor mixin. */
    private Set<String> collectVanillaBoundTokens() {
        Set<String> bound = new HashSet<>();
        Minecraft mc = Minecraft.getInstance();
        if (mc == null || mc.options == null) return bound;
        for (KeyMapping kb : mc.options.keyMappings) {
            if (KeybindingRegistry.CATEGORY.equals(kb.getCategory())) continue;
            InputConstants.Key key = readLiveKey(kb);
            if (key == null) continue;
            int code = key.getValue();
            if (code < 0) continue; // UNKNOWN_KEY
            if (key.getType() == InputConstants.Type.MOUSE) {
                String tok = MOUSE_GLFW_TO_TOKEN.get(code);
                if (tok != null) bound.add(tok);
            } else {
                String tok = KB_GLFW_TO_TOKEN.get(code);
                if (tok != null) bound.add(tok);
            }
        }
        return bound;
    }

    /** Read the live {@code key} field via the accessor mixin; fall back to {@code getDefaultKey()}. */
    public static InputConstants.Key readLiveKey(KeyMapping kb) {
        if (kb instanceof KeyMappingAccessor acc) {
            try {
                return acc.voxkbd$key();
            } catch (Throwable ignored) {
                // fall through
            }
        }
        return kb.getDefaultKey();
    }

    /** Physical-key tokens the mod's own switch / config bindings are currently bound to (always locked). */
    private Set<String> collectModOwnedSwitchTokens() {
        Set<String> out = new HashSet<>();
        for (KeyMapping kb : new KeyMapping[] {
                KeybindingRegistry.previousKeybinding,
                KeybindingRegistry.nextKeyBinding,
                KeybindingRegistry.openConfigKeybinding }) {
            if (kb == null) continue;
            InputConstants.Key key = readLiveKey(kb);
            if (key == null) continue;
            int code = key.getValue();
            if (code < 0) continue;
            String tok = KB_GLFW_TO_TOKEN.get(code);
            if (tok != null) out.add(tok);
        }
        return out;
    }

    /** Raw GLFW keycodes of the mod's own switch / config bindings (capture pass-through list). */
    private Set<Integer> collectModOwnedKeycodes() {
        Set<Integer> out = new HashSet<>();
        for (KeyMapping kb : new KeyMapping[] {
                KeybindingRegistry.previousKeybinding,
                KeybindingRegistry.nextKeyBinding,
                KeybindingRegistry.openConfigKeybinding }) {
            if (kb == null) continue;
            InputConstants.Key key = readLiveKey(kb);
            if (key == null) continue;
            int code = key.getValue();
            if (code < 0) continue;
            out.add(code);
        }
        return out;
    }

    /** Tokens for all modifier keys (both sides) that appear in the configured combo prefix (D8). */
    private Set<String> getComboPrefixModifiers() {
        Set<String> mods = new HashSet<>();
        if (config == null || config.switchCfg == null || config.switchCfg.comboPrefix == null) return mods;

        int mask = ComboPrefixMask.parse(config.switchCfg.comboPrefix);
        if (ComboPrefixMask.hasCtrl(mask) || ComboPrefixMask.hasAlt(mask)
                || ComboPrefixMask.hasShift(mask) || ComboPrefixMask.hasWin(mask)) {
            for (PhysicalKey pk : PhysicalKeyRegistry.all()) {
                if (!pk.isModifier()) continue;
                String t = pk.token();
                if ((ComboPrefixMask.hasCtrl(mask)  && (t.equals(PhysicalKey.LCTRL.token()) || t.equals(PhysicalKey.RCTRL.token())))
                 || (ComboPrefixMask.hasAlt(mask)   && (t.equals(PhysicalKey.LALT.token())  || t.equals(PhysicalKey.RALT.token())))
                 || (ComboPrefixMask.hasShift(mask) && (t.equals(PhysicalKey.LSHIFT.token())|| t.equals(PhysicalKey.RSHIFT.token())))
                 || (ComboPrefixMask.hasWin(mask)   && (t.equals(PhysicalKey.LWIN.token())  || t.equals(PhysicalKey.RWIN.token())))) {
                    mods.add(t);
                }
            }
        }
        return mods;
    }
}
