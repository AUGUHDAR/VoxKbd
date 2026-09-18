package com.voxkbd.mod.screencompat;

import net.minecraft.client.gui.screens.options.controls.KeyBindsScreen;

/**
 * Per-version compatibility shim: the vanilla key-binds screen moved packages between releases
 * (1.20.1: {@code gui.screens.controls}; 1.20.2+: {@code gui.screens.options.controls}). The
 * generated per-target build replaces this file with the right import — this variant targets
 * 1.20.2+.
 */
public final class ScreenCompat {
    private ScreenCompat() {}
    public static Class<?> keyBindsScreenClass() { return KeyBindsScreen.class; }
}
