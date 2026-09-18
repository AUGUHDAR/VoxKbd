package com.voxkbd.daemon.input;

import com.voxkbd.core.key.PhysicalKey;

/**
 * A raw physical key event captured by the input backend.
 *
 * @param phys    the resolved physical key (may be null if the vk is outside the
 *                {@link com.voxkbd.core.key.PhysicalKeyRegistry} — e.g. `[`=91, `]`=93,
 *                `` ` ``=96, which are the mod's default switch bindings and must still be
 *                recognized by the lock layer via {@link #glfwKey})
 * @param action  GLFW action semantics: 0 = release, 1 = press, 2 = repeat
 * @param mods    modifier bitmask captured at event time
 * @param glfwKey the raw GLFW / VK keycode of the event (always valid)
 */
public record CapturedKey(PhysicalKey phys, int action, int mods, int glfwKey) {
    public CapturedKey(PhysicalKey phys, int action, int mods) {
        this(phys, action, mods, phys != null ? phys.glfwCode() : -1);
    }
    public boolean isPress() { return action == 1; }
    public boolean isRelease() { return action == 0; }
}
