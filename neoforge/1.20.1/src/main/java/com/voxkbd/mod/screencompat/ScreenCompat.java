package com.voxkbd.mod.screencompat;

import net.minecraft.client.gui.screens.controls.KeyBindsScreen;

/** Per-target vanilla key-binds screen resolution (package moved across releases). */
public final class ScreenCompat {
    private ScreenCompat() {}
    public static Class<?> keyBindsScreenClass() { return KeyBindsScreen.class; }
}
