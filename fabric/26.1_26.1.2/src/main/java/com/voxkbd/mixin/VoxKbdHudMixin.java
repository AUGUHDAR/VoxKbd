package com.voxkbd.mixin;

import com.voxkbd.mod.ModRuntime;
import net.minecraft.client.DeltaTracker;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * HUD hook for the "current keyboard always shown" tab (MC 26.x). Injects at the tail of the
 * in-game HUD render so the tab paints above the vanilla HUD. Loader-agnostic: targets a vanilla
 * class, so the per-loader remapper handles the name mapping.
 */
@Mixin(net.minecraft.client.gui.Gui.class)
public abstract class VoxKbdHudMixin {

    @Inject(method = "extractRenderState", at = @At("TAIL"))
    private void voxkbd$drawAlwaysShowTab(GuiGraphicsExtractor guiGraphics, DeltaTracker deltaTracker,
                                          CallbackInfo ci) {
        try {
            ModRuntime.onHudRender(guiGraphics, 0.0f);
        } catch (Throwable ignored) {
            // The tab is cosmetic — never break the HUD.
        }
    }
}
