package com.voxkbd.mixin;

import com.voxkbd.mod.ModRuntime;
import net.minecraft.client.gui.GuiGraphics;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * HUD hook for the "current keyboard always shown" tab (1.20.x–1.21.x port). Injects at the tail
 * of the in-game HUD render so the tab paints above the vanilla HUD. Loader-agnostic: works on
 * Fabric, Forge and NeoForge because it targets a vanilla class; the AP/remapper handles the
 * per-loader name mapping (intermediary / SRG / mojmap).
 *
 * <p>The callback only captures the GuiGraphics prefix of the render signature — the trailing
 * parameter differs between 1.20.1 ({@code float partialTick}) and 1.20.2+ ({@code DeltaTracker})
 * and is not needed for drawing.</p>
 */
@Mixin(net.minecraft.client.gui.Gui.class)
public abstract class VoxKbdHudMixin {

    @Inject(method = "render", at = @At("TAIL"))
    private void voxkbd$drawAlwaysShowTab(GuiGraphics guiGraphics, CallbackInfo ci) {
        try {
            ModRuntime.onHudRender(guiGraphics, 0.0f);
        } catch (Throwable ignored) {
            // The tab is cosmetic — never break the HUD.
        }
    }
}
