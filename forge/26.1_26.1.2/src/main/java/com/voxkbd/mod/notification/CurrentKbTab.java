package com.voxkbd.mod.notification;

import com.voxkbd.core.Constants;
import com.voxkbd.core.config.Config;
import com.voxkbd.core.input.InputState;
import com.voxkbd.mod.ui.MasterScreen;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.network.chat.Component;

/**
 * "Current keyboard always shown" — tap-bar mode (Config UI tri-state: 关 / 动作栏 / tap栏).
 *
 * <p>When {@code notification.alwaysShow == "TAB"} and a non-vanilla keyboard is active, a small
 * always-on tab is pinned to the top-left of the HUD showing 当前键盘：name. Drawn from the
 * loader's HUD hook (Fabric HudRenderCallback / HUD mixin on Forge &amp; NeoForge). The action-bar
 * mode of the same tri-state is driven from {@link SwitchNotifier#tick()}.</p>
 */
public final class CurrentKbTab {

    private static final int BG = 0xE6141517;
    private static final int BORDER = 0xFF3A3D42;
    private static final int TEXT = 0xFFFFD24A;

    private CurrentKbTab() {}

    public static void draw(GuiGraphicsExtractor gui, Config config, InputState inputState) {
        Config.NotificationConfig n = config == null || config.switchCfg == null
                ? null : config.switchCfg.notification;
        if (n == null || !"TAB".equals(n.alwaysShow)) return;
        if (inputState == null) return;
        int kb = inputState.activeKeyboard();
        if (kb == Constants.VANILLA_KB_INDEX) return;

        Minecraft client = Minecraft.getInstance();
        if (client == null || client.font == null) return;
        Font font = client.font;

        Component text = Component.translatable("voxkbd.ui.active", MasterScreen.displayName(kb));
        int w = font.width(text.getString()) + 12;
        gui.fill(6, 6, 6 + w, 24, BG);
        gui.fill(6, 6, 6 + w, 7, BORDER);
        gui.fill(6, 23, 6 + w, 24, BORDER);
        gui.fill(6, 6, 7, 24, BORDER);
        gui.fill(5 + w, 6, 6 + w, 24, BORDER);
        gui.text(font, text, 12, 11, TEXT, false);
    }
}
