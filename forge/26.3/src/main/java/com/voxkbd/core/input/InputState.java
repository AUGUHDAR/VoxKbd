package com.voxkbd.core.input;

import com.voxkbd.core.Constants;

import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArraySet;

/**
 * Thread-safe runtime input state shared between the control side (mod) and the translation side
 * (desktop daemon, or Android mixin). Holds the currently active virtual keyboard, the MC window
 * focus, and the set of locked physical-key tokens.
 *
 * <p>The lock set is built dynamically at runtime — never hardcode locked keys (§3.4.3/§3.4.4).</p>
 */
public final class InputState {
    /**
     * Active layer: 0 = vanilla baseline (D20 — the game starts here and every physical key behaves
     * exactly like vanilla until the player switches into a virtual keyboard), >= 1 = virtual keyboard.
     */
    private volatile int activeKeyboard = Constants.VANILLA_KB_INDEX;
    private volatile boolean focus = true;   // gated by GLFW window focus (D7)
    /**
     * When true, the in-process (Android) GLFW injection backend performs translation. On desktop
     * the mod keeps this false and the daemon does the capture/translation instead, so the mixin
     * stays a pure pass-through and the two backends never double-handle a key (§4.0 / D6).
     */
    private volatile boolean inProcessTranslation = false;
    /**
     * When true, an MC Screen (chat / inventory / menus) is open: capture backends pass every
     * physical key through natively so GUI typing and menu keys keep working. The mod updates
     * this from its client-tick loop.
     */
    private volatile boolean paused = false;
    /**
     * True while the vanilla key-binds screen (Controls) is capturing a binding. Capture backends
     * then translate pressed keys as SYNTHETIC keys of the active keyboard even when locked —
     * otherwise a function could never be bound to a {@code VOXKBD_*} key at all (every locked key
     * would register as its vanilla key). The mod updates this from its client-tick loop.
     */
    private volatile boolean bindCapture = false;
    /**
     * Live copy of the configured "combo + digit" prefix mask ({@link com.voxkbd.core.config.ComboPrefixMask}
     * bit ordering), kept here so capture backends can let a prefix+digit through even when that digit is
     * otherwise locked to vanilla (e.g. hotbar 1–9). 0 = combo switching disabled.
     */
    private volatile int comboPrefixMask;
    private final Set<String> locked = new CopyOnWriteArraySet<>();
    /**
     * Mod-owned switch / config KeyMapping current GLFW keycodes (previous / next / openConfig).
     * These are ALWAYS considered "locked" at the capture layer even if the player rebinds them
     * to a key the vanilla {@code PhysicalKeyRegistry} does not know about (e.g. `[`, `]`, `` ` ``).
     * The token-based {@link #locked} set would otherwise miss them — the §3.3 "防止卡死" guarantee
     * depends on these never being hijacked by a virtual keyboard. Backends consult BOTH the
     * token set and this code set: a hit in either means pass-through.
     */
    private final Set<Integer> modOwnedKeycodes = new CopyOnWriteArraySet<>();

    public int activeKeyboard() { return activeKeyboard; }
    public void setActiveKeyboard(int kb) { this.activeKeyboard = kb; }

    public boolean focus() { return focus; }
    public void setFocus(boolean focus) { this.focus = focus; }

    public boolean inProcessTranslation() { return inProcessTranslation; }
    public void setInProcessTranslation(boolean v) { this.inProcessTranslation = v; }

    public boolean paused() { return paused; }
    public void setPaused(boolean paused) { this.paused = paused; }

    public boolean bindCapture() { return bindCapture; }
    public void setBindCapture(boolean bindCapture) { this.bindCapture = bindCapture; }

    public int comboPrefixMask() { return comboPrefixMask; }
    public void setComboPrefixMask(int mask) { this.comboPrefixMask = mask; }

    public boolean isLocked(String physToken) { return locked.contains(physToken); }
    /** True when {@code glfwKeycode} is a mod-owned switch / config binding (always pass-through). */
    public boolean isModOwnedKeycode(int glfwKeycode) { return modOwnedKeycodes.contains(glfwKeycode); }

    public Set<String> lockedSnapshot() { return Set.copyOf(locked); }
    public Set<Integer> modOwnedKeycodesSnapshot() { return Set.copyOf(modOwnedKeycodes); }

    public void setLocked(Set<String> tokens) {
        locked.clear();
        locked.addAll(tokens);
    }

    public void setLocked(List<String> tokens) {
        locked.clear();
        locked.addAll(tokens);
    }

    public void addLocked(String token) { locked.add(token); }
    public void removeLocked(String token) { locked.remove(token); }

    /** Replace the mod-owned keycode set (previous / next / openConfig). */
    public void setModOwnedKeycodes(java.util.Collection<Integer> codes) {
        modOwnedKeycodes.clear();
        if (codes != null) modOwnedKeycodes.addAll(codes);
    }
}
