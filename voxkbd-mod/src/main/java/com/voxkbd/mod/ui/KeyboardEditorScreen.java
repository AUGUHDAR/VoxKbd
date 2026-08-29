package com.voxkbd.mod.ui;

import com.voxkbd.core.config.Config;
import com.voxkbd.core.naming.VoxKbdNames;
import com.voxkbd.mod.config.ConfigManager;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.network.chat.Component;

/**
 * Add / edit screen for one virtual keyboard, matching the approved wireframe:
 *
 * <pre>
 * 添加虚拟键盘 / 修改虚拟键盘                当前修改：xxxx   ← header + divider
 *
 *        虚拟键盘名称        [ 输入框 ]
 *        虚拟键盘描述        [ 输入框 ]
 *
 *                                  [撤回]  [保存]  [完成]   ← bottom right
 * </pre>
 *
 * <p>One class for both modes: {@code entry == null} means "add". Button gating mirrors the
 * master UI: 保存/撤回 light up only while the fields differ from the last saved values, 完成 is
 * always lit. A keyboard is created ONLY by an explicit 保存 — clicking 完成 or 撤回 directly in
 * add mode simply leaves the screen without creating anything. 完成 (edit mode, or after a save)
 * commits pending edits, persists the config to disk and returns to the {@link ConfigScreen}.</p>
 */
public final class KeyboardEditorScreen extends Screen {

    private final ConfigScreen parent;
    private final Config config;
    private final ConfigManager configManager;
    /** Null in add mode until the first 保存 creates it. */
    private Config.KeyboardEntry entry;

    private EditBox nameBox;
    private EditBox descBox;
    private Button revertButton;
    private Button saveButton;
    private Button finishButton;

    private static final int HEADER_H = 30;
    private static final int SIDE_PAD = 16;

    public KeyboardEditorScreen(ConfigScreen parent, Config config, ConfigManager configManager,
                                Config.KeyboardEntry entry) {
        super(Component.translatable(entry == null
                ? "voxkbd.ui.editor.add.title" : "voxkbd.ui.editor.edit.title"));
        this.parent = parent;
        this.config = config;
        this.configManager = configManager;
        this.entry = entry;
    }

    @Override
    protected void init() {
        super.init();
        Font font = Minecraft.getInstance().font;
        if (font == null) return;

        // Bottom-right: 撤回 / 保存 / 完成. Width derives from the window (no fixed budget).
        int gap = 10, bh = 24;
        int by = this.height - 20 - bh;
        int bw = Math.max(48, Math.min(92, (this.width - 2 * SIDE_PAD - 2 * gap) / 3));
        int finishX = this.width - SIDE_PAD - bw;
        int saveX = finishX - gap - bw;
        int revertX = saveX - gap - bw;
        finishButton = Button.builder(Component.translatable("voxkbd.ui.finish"), b -> onFinish())
                .bounds(finishX, by, bw, bh).build();
        saveButton = Button.builder(Component.translatable("voxkbd.ui.save"), b -> onSave())
                .bounds(saveX, by, bw, bh).build();
        revertButton = Button.builder(Component.translatable("voxkbd.ui.revert"), b -> onRevert())
                .bounds(revertX, by, bw, bh).build();
        addRenderableWidget(finishButton);
        addRenderableWidget(saveButton);
        addRenderableWidget(revertButton);

        // Input boxes: both rows share one column so the two boxes line up exactly.
        int boxX = (int) (this.width * 0.36);
        int boxW = Math.max(100, Math.min(280, (int) (this.width * 0.26)));
        int boxH = 22;
        int nameY = (int) (this.height * 0.28);
        int descY = (int) (this.height * 0.44);

        nameBox = new EditBox(font, boxX, nameY, boxW, boxH, Component.translatable("voxkbd.ui.editor.name"));
        nameBox.setMaxLength(64);
        nameBox.setValue(currentName());
        nameBox.setResponder(s -> refreshButtons());
        addRenderableWidget(nameBox);

        descBox = new EditBox(font, boxX, descY, boxW, boxH, Component.translatable("voxkbd.ui.editor.desc"));
        descBox.setMaxLength(128);
        descBox.setValue(currentDesc());
        descBox.setResponder(s -> refreshButtons());
        addRenderableWidget(descBox);

        refreshButtons();
    }

    /** Last saved values (an entry's fields are only written by an explicit 保存). */
    private String currentName() {
        return entry != null && entry.displayName != null ? entry.displayName : "";
    }

    private String currentDesc() {
        return entry != null && entry.description != null ? entry.description : "";
    }

    /** True while either box differs from the last saved values — gates 保存 and 撤回. */
    private boolean isDirty() {
        return !nameBox.getValue().trim().equals(currentName())
                || !descBox.getValue().trim().equals(currentDesc());
    }

    private void refreshButtons() {
        if (saveButton == null) return;
        boolean dirty = isDirty();
        saveButton.active = dirty;
        revertButton.active = dirty;
        finishButton.active = true; // 完成 is always lit; in add mode it never creates a keyboard
    }

    /** 撤回: restore the boxes to the last saved values. Never creates a keyboard. */
    private void onRevert() {
        nameBox.setValue(currentName());
        descBox.setValue(currentDesc());
    }

    /** 保存: apply the fields in memory. In add mode this is the ONLY way a keyboard is created.
     *  Synthetic keys are pure keycode-space extensions (D16) — nothing to register anywhere. */
    private void onSave() {
        if (entry == null) {
            int maxId = 0;
            for (Config.KeyboardEntry e : config.keyboards) maxId = Math.max(maxId, e.id);
            entry = new Config.KeyboardEntry();
            entry.id = maxId + 1;
            entry.nameKey = VoxKbdNames.keyboardNameKey(entry.id);
            entry.resourcePackOverride = true;
            config.keyboards.add(entry);
        }
        entry.displayName = nameBox.getValue().trim();
        entry.description = descBox.getValue().trim();
        if (parent != null) parent.clearSelection();
        // 实时持久化: the new/edited keyboard is on disk the moment 保存 is pressed.
        try {
            configManager.save();
        } catch (java.io.IOException e) {
            com.voxkbd.mod.VoxKbdMod.LOGGER.warn("VoxKbd: real-time config save failed; in-memory state preserved", e);
        }
        refreshButtons();
    }

    /**
     * 完成: always lit, never creates a keyboard on its own.
     * <ul>
     *   <li>add mode without a prior 保存 → just go back, typed text is discarded;</li>
     *   <li>otherwise → commit pending edits, persist the config to disk, back to the list.</li>
     * </ul>
     */
    private void onFinish() {
        if (entry != null) {
            if (isDirty()) onSave();
            try {
                configManager.save();
            } catch (java.io.IOException e) {
                com.voxkbd.mod.VoxKbdMod.LOGGER.warn(
                        "VoxKbd: failed to save config; in-memory state preserved", e);
            }
        }
        if (parent != null) {
            Minecraft.getInstance().gui.setScreen(parent);
        } else {
            this.onClose();
        }
    }

    @Override
    public void extractRenderState(GuiGraphicsExtractor ctx, int mouseX, int mouseY, float delta) {
        Font font = Minecraft.getInstance().font;
        if (font == null) return;

        ctx.fill(0, 0, this.width, this.height, MasterScreen.BG);

        // --- Header: title left, 当前修改 right (edit mode only), full-width divider below
        ctx.text(font, this.title, SIDE_PAD, (HEADER_H - 9) / 2, MasterScreen.TEXT, false);
        if (entry != null) {
            Component cur = Component.translatable("voxkbd.ui.editor.current",
                    MasterScreen.displayName(entry.id));
            int w = font.width(cur.getString());
            ctx.text(font, cur, this.width - w - SIDE_PAD, (HEADER_H - 9) / 2, MasterScreen.TEXT_DIM, false);
        }
        ctx.fill(0, HEADER_H, this.width, HEADER_H + 1, MasterScreen.BORDER);

        // --- Field labels, vertically centred against their input boxes
        int labelX = (int) (this.width * 0.18);
        ctx.text(font, Component.translatable("voxkbd.ui.editor.name"),
                labelX, nameBox.getY() + (nameBox.getHeight() - 9) / 2, MasterScreen.TEXT, false);
        ctx.text(font, Component.translatable("voxkbd.ui.editor.desc"),
                labelX, descBox.getY() + (descBox.getHeight() - 9) / 2, MasterScreen.TEXT, false);

        // Edit boxes + buttons render via the base pass, after the custom scene.
        super.extractRenderState(ctx, mouseX, mouseY, delta);
    }
}
