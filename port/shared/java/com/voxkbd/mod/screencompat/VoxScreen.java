package com.voxkbd.mod.screencompat;

import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;

/**
 * Shared base for all Vox Kbd screens — per-version compatibility point.
 *
 * <p>1.20.2+ variant: the base Screen.render() draws its blur/gradient background automatically
 * before the subclass scene; Vox Kbd screens paint their own full-screen background, so this
 * override suppresses the automatic one to avoid a dark veil over the custom scene.</p>
 */
public abstract class VoxScreen extends Screen {

    protected VoxScreen(Component title) {
        super(title);
    }

    @Override
    public void renderBackground(GuiGraphics guiGraphics, int mouseX, int mouseY, float partialTick) {
        // Vox Kbd screens draw their own full-screen background.
    }
}
