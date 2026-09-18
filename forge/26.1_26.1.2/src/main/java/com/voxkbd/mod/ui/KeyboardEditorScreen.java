package com.voxkbd.mod.ui;

import com.voxkbd.core.config.Config;
import com.voxkbd.core.naming.VoxKbdNames;
import com.voxkbd.mod.config.ConfigManager;
import com.voxkbd.mod.screencompat.VoxScreen;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.network.chat.Component;

/**
 * Add / edit screen for one virtual keyboard (1.20.x–1.21.x port of the 26.2 screen).
 * One class for both modes: {@code entry == null} means "add". A keyboard is created ONLY by an
 * explicit 保存.
 */
public final class KeyboardEditorScreen extends VoxScreen {

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

    private String currentName() {
        return entry != null && entry.displayName != null ? entry.displayName : "";
    }

    private String currentDesc() {
        return entry != null && entry.description != null ? entry.description : "";
    }

    private boolean isDirty() {
        return !nameBox.getValue().trim().equals(currentName())
                || !descBox.getValue().trim().equals(currentDesc());
    }

    private void refreshButtons() {
        if (saveButton == null) return;
        boolean dirty = isDirty();
        saveButton.active = dirty;
        revertButton.active = dirty;
        finishButton.active = true;
    }

    /** 撤回: restore the boxes to the last saved values. Never creates a keyboard. */
    private void onRevert() {
        nameBox.setValue(currentName());
        descBox.setValue(currentDesc());
    }

    /** 保存: apply the fields in memory and persist. In add mode the ONLY way a keyboard is created. */
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
        try {
            configManager.save();
        } catch (java.io.IOException e) {
            com.voxkbd.mod.ModRuntime.LOGGER.warn("VoxKbd: real-time config save failed; in-memory state preserved", e);
        }
        refreshButtons();
    }

    /** 完成: always lit, never creates a keyboard on its own. */
    private void onFinish() {
        if (entry != null) {
            if (isDirty()) onSave();
            try {
                configManager.save();
            } catch (java.io.IOException e) {
                com.voxkbd.mod.ModRuntime.LOGGER.warn(
                        "VoxKbd: failed to save config; in-memory state preserved", e);
            }
        }
        if (parent != null) {
            Minecraft.getInstance().setScreen(parent);
        } else {
            this.onClose();
        }
    }

    @Override
    public void extractRenderState(GuiGraphicsExtractor gui, int mouseX, int mouseY, float delta) {
        Font font = Minecraft.getInstance().font;
        if (font == null) return;

        gui.fill(0, 0, this.width, this.height, MasterScreen.BG);

        // --- Header: title left, 当前修改 right (edit mode only), full-width divider below
        gui.text(font, this.title, SIDE_PAD, (HEADER_H - 9) / 2, MasterScreen.TEXT, false);
        if (entry != null) {
            Component cur = Component.translatable("voxkbd.ui.editor.current",
                    MasterScreen.displayName(entry.id));
            int w = font.width(cur.getString());
            gui.text(font, cur, this.width - w - SIDE_PAD, (HEADER_H - 9) / 2, MasterScreen.TEXT_DIM, false);
        }
        gui.fill(0, HEADER_H, this.width, HEADER_H + 1, MasterScreen.BORDER);

        // --- Field labels, vertically centred against their input boxes
        int labelX = (int) (this.width * 0.18);
        gui.text(font, Component.translatable("voxkbd.ui.editor.name"),
                labelX, nameBox.getY() + (nameBox.getHeight() - 9) / 2, MasterScreen.TEXT, false);
        gui.text(font, Component.translatable("voxkbd.ui.editor.desc"),
                labelX, descBox.getY() + (descBox.getHeight() - 9) / 2, MasterScreen.TEXT, false);

        // Edit boxes + buttons render via the base pass, after the custom scene.
        super.extractRenderState(gui, mouseX, mouseY, delta);
    }
}
