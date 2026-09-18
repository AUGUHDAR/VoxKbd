package com.voxkbd.mod.screencompat;

import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;

/** Shared base for all Vox Kbd screens (per-version compatibility point). */
public abstract class VoxScreen extends Screen {

    protected VoxScreen(Component title) {
        super(title);
    }

    @Override
    public void renderBackground(GuiGraphics guiGraphics, int mouseX, int mouseY, float partialTick) {
        // Vox Kbd screens draw their own full-screen background.
    }

}
