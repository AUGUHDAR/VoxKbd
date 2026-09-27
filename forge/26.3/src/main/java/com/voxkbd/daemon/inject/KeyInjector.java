package com.voxkbd.daemon.inject;

import com.voxkbd.core.key.PhysicalKey;

/**
 * Delivers a LOCKED physical key back to the OS so Minecraft receives it exactly as vanilla
 * (desktop pass-through path, §4.0 / Protocol javadoc). {@link RobotKeyInjector} is the production
 * implementation; {@link RecordingInjector} is a test double.
 */
public interface KeyInjector {
    /** Inject a physical key event. {@code action}: 0 = release, 1 = press, 2 = repeat. */
    void injectPhysical(PhysicalKey phys, int action, int mods);
}
