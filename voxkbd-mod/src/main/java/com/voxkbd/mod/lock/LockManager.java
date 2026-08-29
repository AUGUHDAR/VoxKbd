package com.voxkbd.mod.lock;

import com.mojang.blaze3d.platform.InputConstants;
import com.voxkbd.core.config.ComboPrefixMask;
import com.voxkbd.core.config.Config;
import com.voxkbd.core.input.InputState;
import com.voxkbd.core.key.PhysicalKey;
import com.voxkbd.core.key.PhysicalKeyRegistry;
import com.voxkbd.mod.VoxKbdMod;
import com.voxkbd.mod.config.ConfigManager;
import net.minecraft.client.Minecraft;
import net.minecraft.client.KeyMapping;

import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.function.Consumer;

/**
 * Builds the dynamic lock snapshot (decision D4 / §3.4). Lock layers, all derived at runtime — never
 * hardcode a key list (§3.4.3 / §3.4.4 "动态检测，非硬编码"):
 *
 * <ol>
 *   <li><b>Default lock</b> &#8212; every currently vanilla-bound physical key (read from
 *       {@code Minecraft.options.keyMappings}, skipping the mod's own KeyBindings).</li>
 *   <li><b>Vanilla lock following</b> (config) &#8212; keeps vanilla-bound keys locked and
 *       <em>prevents</em> unlocking them; unlock attempts return {@code voxkbd.ui.lock_follow_warning}.</li>
 *   <li><b>Combo prefix lock</b> &#8212; modifier keys used in the "combo + digit" prefix
 *       (Ctrl/Alt/Shift/Win) are always locked so the prefix itself can never be hijacked
 *       by a virtual keyboard (§3.3 / D8).</li>
 *   <li><b>Mod-owned switch / config binding lock</b> &#8212; the previous / next / openConfig
 *       KeyBindings are always locked (tracked via the GLFW keycode set, not the token set, so
 *       even keys outside the {@code PhysicalKeyRegistry} table such as {@code [} / {@code ]} /
 *       `` ` `` are covered). The §3.3 "防止卡死" guarantee (§3.4.1 second bullet point, new).</li>
 *   <li><b>Per-key UI lock</b> &#8212; left-click lock / right-click unlock in the Master UI.</li>
 *   <li><b>Safety lock on unsaved Finish</b> (§3.4.4) &#8212; every currently-unlocked physical key
 *       is forced locked (no save). Undo is suppressed afterwards so the safety net cannot be
 *       lifted by a single misclick.</li>
 * </ol>
 *
 * <p>After any change it updates {@link InputState} and pushes {@code state} (via {@code statePusher}),
 * and mirrors explicit locks into {@code config.lock.lockedKeys} for persistence.</p>
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
    /**
     * Reflective handle to {@code KeyMapping.key} (the live, player-chosen binding). The field is
     * {@code protected} in MC 26.2 with no public getter — {@code getDefaultKey()} only returns the
     * immutable constructor-time default, which would put every KeyMapping's "default" key in the
     * lock set regardless of the player's actual rebinding (the "all keys locked" symptom).
     */
    private static final java.lang.reflect.Field KEY_FIELD;
    static {
        for (PhysicalKey pk : PhysicalKeyRegistry.all()) {
            if (pk.category() == PhysicalKey.Category.KEYBOARD) KB_GLFW_TO_TOKEN.put(pk.glfwCode(), pk.token());
            else if (pk.category() == PhysicalKey.Category.MOUSE) MOUSE_GLFW_TO_TOKEN.put(pk.glfwCode(), pk.token());
        }
        java.lang.reflect.Field f;
        try {
            f = KeyMapping.class.getDeclaredField("key");
            f.setAccessible(true);
        } catch (NoSuchFieldException e) {
            // Field name "key" is the MC 26.2 Yarn intermediary name. If the mapping ever changes
            // we fall back to getDefaultKey() — which is the buggy behaviour, but better than a
            // hard crash on load.
            f = null;
        }
        KEY_FIELD = f;
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
     * success. Blocked by:
     * <ul>
     *   <li>vanilla-lock-following protecting a vanilla-bound key (§3.4.2);</li>
     *   <li>the key being one of the mod's own switch / config bindings — these are ALWAYS locked
     *       (§3.3, "防止卡死" requirement);</li>
     *   <li>the key being a modifier used in the configured combo prefix (D8).</li>
     * </ul>
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

    /** §3.4.4 behaviour REMOVED (v0.5 眼见为实): finishing without saving no longer force-locks
     *  every key — the UI's red = locked is the single source of truth and is persisted in real time. */

    /**
     * Real-time persistence (v0.5): mirror BOTH explicit sets into the config and write the JSON
     * to disk immediately. Called after EVERY lock/unlock/binding change — the file must always
     * match what the UI shows, never only on exit ("抽奖式保存" is forbidden).
     */
    private void persist() {
        config.lock.lockedKeys = new java.util.ArrayList<>(uiLocked);
        config.lock.unlockedKeys = new java.util.ArrayList<>(uiUnlocked);
        try {
            configManager.save();
        } catch (java.io.IOException e) {
            VoxKbdMod.LOGGER.warn("VoxKbd: real-time config save failed; in-memory state preserved", e);
        }
    }

    /** Persist explicit locks (Save button — the state is already on disk via {@link #persist()}). */
    public void markSaved() {
        persist();
        dirty = false;
    }

    /** Revert to the last saved lock state (Undo button). Disabled after a safety finish. */
    public void undo() {
        // Undo target = the persisted sets (which real-time persistence keeps identical to disk).
        java.util.List<String> locked = config.lock.lockedKeys != null ? config.lock.lockedKeys : java.util.List.of();
        java.util.List<String> unlocked = config.lock.unlockedKeys != null ? config.lock.unlockedKeys : java.util.List.of();
        uiLocked.clear();
        uiLocked.addAll(locked);
        uiUnlocked.clear();
        uiUnlocked.addAll(unlocked);
        dirty = false;
        recompute();
    }

    /** Re-derive the dynamic lock set from the CURRENT vanilla bindings and re-apply it.
     *  Cheap enough to call periodically from the client tick loop: options load after mod init,
     *  and players can rebind keys at any time (§3.4.1 dynamic, never hardcoded). */
    public void refreshVanillaLocks() {
        recompute();
    }

    /**
     * Force-unlock a physical key because a function was just bound to its synthetic key
     * (bind-capture flow, v0.5): the binding can only ever fire if the physical key belongs to
     * the virtual keyboard. Returns true when the state actually changed.
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
        // Mirror mod-owned keycodes (previous / next / openConfig) into the shared state so
        // capture backends can recognise them even when the player rebinds to a key the
        // physical-key table does not know about (e.g. `[`, `]`, `` ` ``). §3.3 "防止卡死".
        inputState.setModOwnedKeycodes(collectModOwnedKeycodes());
        if (statePusher != null) statePusher.run();
    }

    /** Effective locked set: default (vanilla-bound) + combo prefix modifiers + mod-owned switch
     *  bindings (§3.3 "防止卡死") + explicit locks, minus allowed unlocks. */
    private Set<String> computeLockedKeys() {
        Set<String> vanilla = collectVanillaBoundTokens();
        Set<String> locked = new HashSet<>();
        for (String v : vanilla) {
            if (config.vanillaLockFollow) {
                locked.add(v); // always locked while following
            } else if (!uiUnlocked.contains(v)) {
                locked.add(v); // default lock unless explicitly unlocked
            }
        }
        // Combo prefix modifiers: always locked (cannot be hijacked by virtual keyboards, D8).
        locked.addAll(getComboPrefixModifiers());
        // Mod-owned switch / config bindings: always locked, regardless of player rebinding them
        // to a key that is not in the vanilla table (§3.3 "防止卡死").
        locked.addAll(collectModOwnedSwitchTokens());
        locked.addAll(uiLocked);
        return locked;
    }

    /** All physical keys currently bound to a vanilla function (dynamic, never hardcoded).
     *  Excludes the mod's own KeyBindings (previous / next / openConfig) — those are tracked
     *  separately by {@link #collectModOwnedSwitchTokens()} so they stay locked even when the
     *  player rebinds them to a key the vanilla table does not know about (e.g. `[`, `]`, `` ` ``).
     *
     *  <p>Reads {@code KeyMapping.key} (the live, player-chosen binding) reflectively, because
     *  MC 26.2 has no public getter — {@code getDefaultKey()} only returns the immutable
     *  constructor-time default, which would put every KeyMapping's "default" key in the lock
     *  set regardless of the player's actual rebinding (the "all keys locked" symptom).</p>
     */
    private Set<String> collectVanillaBoundTokens() {
        Set<String> bound = new HashSet<>();
        Minecraft mc = Minecraft.getInstance();
        if (mc == null || mc.options == null) return bound;
        for (KeyMapping kb : mc.options.keyMappings) {
            // Skip the mod's own KeyBindings — they are tracked independently and would otherwise
            // either pollute this set (when rebound to a non-vanilla key) or be missed (when the
            // rebind target is e.g. `[` which is not in PhysicalKeyRegistry). Both classes of bug
            // break §3.3's "防止卡死" guarantee.
            if (kb.getCategory() == com.voxkbd.mod.glfw.KeybindingRegistry.CATEGORY) continue;
            InputConstants.Key key = readLiveKey(kb);
            if (key == null) continue;
            int code = key.getValue();
            if (code < 0) continue; // UNKNOWN_KEY
            if (key.getType() == InputConstants.Type.MOUSE) {
                String tok = MOUSE_GLFW_TO_TOKEN.get(code);
                if (tok != null) bound.add(tok);
            } else { // KEYSYM (keyboard / synthetic)
                String tok = KB_GLFW_TO_TOKEN.get(code);
                if (tok != null) bound.add(tok);
            }
        }
        return bound;
    }

    /** Read the live {@code key} field via reflection; fall back to {@code getDefaultKey()}. */
    public static InputConstants.Key readLiveKey(KeyMapping kb) {
        if (KEY_FIELD != null) {
            try {
                return (InputConstants.Key) KEY_FIELD.get(kb);
            } catch (IllegalAccessException ignored) {
                // fall through
            }
        }
        return kb.getDefaultKey();
    }

    /**
     * Returns the physical-key tokens the mod's own switch / config bindings (previous / next /
     * openConfig) are currently bound to. These are ALWAYS locked so the player can never lose
     * access to the mod's own UI / switching keys — even when they rebind them to a key the
     * vanilla {@link com.voxkbd.core.key.PhysicalKeyRegistry} does not know about (§3.3, "防止卡死").
     * Uses reflective {@code readLiveKey} so a player rebind is honoured immediately.
     */
    private Set<String> collectModOwnedSwitchTokens() {
        Set<String> out = new HashSet<>();
        for (KeyMapping kb : new KeyMapping[] {
                com.voxkbd.mod.glfw.KeybindingRegistry.previousKeybinding,
                com.voxkbd.mod.glfw.KeybindingRegistry.nextKeyBinding,
                com.voxkbd.mod.glfw.KeybindingRegistry.openConfigKeybinding }) {
            if (kb == null) continue; // not yet registered (called from the wrong thread / too early)
            InputConstants.Key key = readLiveKey(kb);
            if (key == null) continue;
            int code = key.getValue();
            if (code < 0) continue;
            String tok = KB_GLFW_TO_TOKEN.get(code);
            if (tok != null) out.add(tok);
            // Codes outside the physical-key table (e.g. `[`=91, `]`=93, `` ` ``=96) are still
            // covered by the mod-owned-keycode path in {@link #collectModOwnedKeycodes} — see
            // the capture backends, which check both the token set and the keycode set.
        }
        return out;
    }

    /**
     * The raw GLFW keycodes the mod's own switch / config bindings are currently bound to.
     * Capture backends consult {@code InputState.isModOwnedKeycode(...)} in addition to the
     * token set, so even keys outside the {@link com.voxkbd.core.key.PhysicalKeyRegistry}
     * table (the previous/next/openConfig defaults `[` / `]` / `` ` `` are exactly that)
     * still trigger pass-through (§3.3 "防止卡死"). Uses reflective {@code readLiveKey} so a
     * player rebind is honoured immediately.
     */
    private Set<Integer> collectModOwnedKeycodes() {
        Set<Integer> out = new HashSet<>();
        for (KeyMapping kb : new KeyMapping[] {
                com.voxkbd.mod.glfw.KeybindingRegistry.previousKeybinding,
                com.voxkbd.mod.glfw.KeybindingRegistry.nextKeyBinding,
                com.voxkbd.mod.glfw.KeybindingRegistry.openConfigKeybinding }) {
            if (kb == null) continue;
            InputConstants.Key key = readLiveKey(kb);
            if (key == null) continue;
            int code = key.getValue();
            if (code < 0) continue;
            out.add(code);
        }
        return out;
    }

    /**
     * Returns the set of physical-key tokens for all modifier keys (both left & right)
     * that appear in the configured "combo + digit" prefix (e.g. "CTRL_ALT" → LCTRL, RCTRL, LALT, RALT).
     * These are always locked so the prefix itself can never be remapped by a virtual keyboard.
     * Built by enumerating {@link PhysicalKey#isModifier()} so adding new modifiers in
     * {@link PhysicalKey} automatically extends the protection (no hardcoded token strings).
     */
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
