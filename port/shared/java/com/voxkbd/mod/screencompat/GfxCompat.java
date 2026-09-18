package com.voxkbd.mod.screencompat;

import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;

/**
 * Text-scaling helper for keycap labels (port of the 26.2 GuiGraphicsExtractor pose path).
 * 1.20.x–1.21.5 variant: GuiGraphics.pose() returns a PoseStack (pushPose/popPose/translate/scale).
 */
public final class GfxCompat {
    private GfxCompat() {}

    public static void scaledCenteredText(GuiGraphics g, Font font, String text,
                                          int cx, int cy, float scale, int color) {
        var pose = g.pose();
        pose.pushPose();
        pose.translate((float) cx, (float) cy);
        pose.scale(scale, scale);
        pose.translate((float) -cx, (float) -cy);
        g.drawCenteredString(font, text, cx, cy - 4, color);
        pose.popPose();
    }
}
