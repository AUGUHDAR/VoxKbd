package com.voxkbd.mod.screencompat;

import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphicsExtractor;

/** Text-scaling helper for keycap labels. 1.21.6+ variant: GuiGraphicsExtractor.pose() returns a Matrix3x2fStack. */
public final class GfxCompat {
    private GfxCompat() {}

    public static void scaledCenteredText(GuiGraphicsExtractor g, Font font, String text,
                                          int cx, int cy, float scale, int color) {
        var pose = g.pose();
        pose.pushMatrix();
        pose.translate((float) cx, (float) cy);
        pose.scale(scale, scale);
        pose.translate((float) -cx, (float) -cy);
        g.centeredText(font, text, cx, cy - 4, color);
        pose.popMatrix();
    }
}
