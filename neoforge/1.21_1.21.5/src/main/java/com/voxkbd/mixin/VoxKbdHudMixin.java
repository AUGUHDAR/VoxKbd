package com.voxkbd.mixin;

import com.voxkbd.mod.ModRuntime;
import net.minecraft.client.DeltaTracker;
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
 * <p>The handler must declare the FULL parameter list of the target: 1.21.x renders the HUD as
 * {@code render(GuiGraphics, DeltaTracker)} — a GuiGraphics-only handler fails to apply with
 * "Invalid descriptor" (observed on 1.21.1).</p>
 */
@Mixin(net.minecraft.client.gui.Gui.class)
public abstract class VoxKbdHudMixin {

    @Inject(method = "render", at = @At("TAIL"))
    private void voxkbd$drawAlwaysShowTab(GuiGraphics guiGraphics, DeltaTracker deltaTracker,
                                          CallbackInfo ci) {
        try {
            ModRuntime.onHudRender(guiGraphics, 0.0f);
        } catch (Throwable ignored) {
            // The tab is cosmetic — never break the HUD.
        }
    }
}
