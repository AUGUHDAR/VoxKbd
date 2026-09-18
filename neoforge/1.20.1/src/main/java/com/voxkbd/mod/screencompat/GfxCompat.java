package com.voxkbd.mod.screencompat;

import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;

/** Text-scaling helper for keycap labels. 1.20.x-1.21.5 variant: GuiGraphics.pose() returns a PoseStack. */
public final class GfxCompat {
    private GfxCompat() {}

    public static void scaledCenteredText(GuiGraphics g, Font font, String text,
                                          int cx, int cy, float scale, int color) {
        var pose = g.pose();
        pose.pushPose();
        pose.translate((float) cx, (float) cy, 0.0F);
        pose.scale(scale, scale, 1.0F);
        pose.translate((float) -cx, (float) -cy, 0.0F);
        g.drawCenteredString(font, text, cx, cy - 4, color);
        pose.popPose();
    }
}
