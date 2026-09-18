package com.voxkbd.mod.ui;

import com.voxkbd.core.config.Config;
import com.voxkbd.core.input.InputState;
import com.voxkbd.core.naming.VoxKbdNames;
import com.voxkbd.mod.config.ConfigManager;
import com.voxkbd.mod.screencompat.VoxScreen;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;

import java.util.ArrayList;
import java.util.List;

/**
 * Configuration UI (1.20.x–1.21.x port of the 26.2 screen). Every option button carries its full
 * "label：value" text; 删除/修改 operate on the row selected in the list; 返回 persists and
 * returns to the master UI.
 */
public final class ConfigScreen extends VoxScreen {

    private final Screen parent;
    private final Config config;
    private final ConfigManager configManager;
    private final InputState inputState;
    private final Runnable statePusher;
    private final Runnable onChanged;

    private Button vanillaFollowBtn;
    private Button notifyModeBtn;
    private Button alwaysShowBtn;
    private Button addBtn;
    private Button deleteBtn;
    private Button editBtn;
    private Button backBtn;

    /** Currently selected keyboard id in the list (-1 = none). */
    private int selectedId = -1;
    private int scrollOffset = 0;

    private static final List<String> NOTIFY_CYCLE = List.of("NONE", "ACTION_BAR", "TITLE");
    private static final String NOTIFY_BOSS_BAR = "BOSS_BAR"; // legacy value, shown if present

    private static final List<String> ALWAYS_CYCLE = List.of("OFF", "ACTION_BAR", "TAB");

    // Layout
    private static final int SIDE_PAD = 16;
    private static final int TOP_PAD = 10;
    private static final int BTN_H = 26;
    private static final int ROW_GAP = 10;
    private static final int ROW_H = 26;
    private static final int LIST_INNER_PAD = 6;

    private int listX, listY, listW, listH;

    public ConfigScreen(Screen parent, Config config, ConfigManager configManager,
                        InputState inputState, Runnable statePusher, Runnable onChanged) {
        super(Component.translatable("voxkbd.ui.config.title"));
        this.parent = parent;
        this.config = config;
        this.configManager = configManager;
        this.inputState = inputState;
        this.statePusher = statePusher;
        this.onChanged = onChanged;
    }

    /** Visible keyboards: the built-in 默认键盘 (id 0) followed by the user-created ones. */
    private List<Config.KeyboardEntry> visibleEntries() {
        List<Config.KeyboardEntry> out = new ArrayList<>();
        Config.KeyboardEntry def = new Config.KeyboardEntry();
        def.id = com.voxkbd.core.Constants.VANILLA_KB_INDEX;
        out.add(def);
        for (Config.KeyboardEntry e : config.keyboards) {
            if (e.id != com.voxkbd.core.Constants.VANILLA_KB_INDEX) out.add(e);
        }
        return out;
    }

    private static boolean isDefaultEntry(Config.KeyboardEntry e) {
        return e.id == com.voxkbd.core.Constants.VANILLA_KB_INDEX;
    }

    private Config.KeyboardEntry selectedEntry() {
        if (selectedId < 0) return null;
        for (Config.KeyboardEntry e : visibleEntries()) if (e.id == selectedId) return e;
        return null;
    }

    /** Called by the editor when a keyboard was saved, so the list re-resolves its selection. */
    public void clearSelection() {
        selectedId = -1;
    }

    @Override
    protected void init() {
        super.init();
        Font font = Minecraft.getInstance().font;
        if (font == null) return;

        int w = this.width - 2 * SIDE_PAD;
        int colW = (w - 2 * ROW_GAP) / 3;

        // --- Row 0: the three cycling option buttons
        int y0 = TOP_PAD;
        vanillaFollowBtn = Button.builder(vanillaFollowLabel(), b -> {
                    config.vanillaLockFollow = !config.vanillaLockFollow;
                    vanillaFollowBtn.setMessage(vanillaFollowLabel());
                    pushRuntimeChange();
                }).bounds(SIDE_PAD, y0, colW, BTN_H).build();
        addRenderableWidget(vanillaFollowBtn);

        notifyModeBtn = Button.builder(notifyModeLabel(), b -> {
                    String cur = notifyMode();
                    int idx = Math.max(0, NOTIFY_CYCLE.indexOf(cur));
                    String next = NOTIFY_CYCLE.get((idx + 1) % NOTIFY_CYCLE.size());
                    config.switchCfg.notification.type = "NONE".equals(next)
                            ? new ArrayList<>() : new ArrayList<>(List.of(next));
                    notifyModeBtn.setMessage(notifyModeLabel());
                    pushRuntimeChange();
                }).bounds(SIDE_PAD + colW + ROW_GAP, y0, colW, BTN_H).build();
        addRenderableWidget(notifyModeBtn);

        alwaysShowBtn = Button.builder(alwaysShowLabel(), b -> {
                    String cur = config.switchCfg.notification.alwaysShow;
                    int idx = Math.max(0, ALWAYS_CYCLE.indexOf(cur));
                    config.switchCfg.notification.alwaysShow =
                            ALWAYS_CYCLE.get((idx + 1) % ALWAYS_CYCLE.size());
                    alwaysShowBtn.setMessage(alwaysShowLabel());
                    pushRuntimeChange();
                }).bounds(SIDE_PAD + 2 * (colW + ROW_GAP), y0, colW, BTN_H).build();
        addRenderableWidget(alwaysShowBtn);

        // --- Row 1: add / delete / edit
        int y1 = y0 + BTN_H + ROW_GAP;
        addBtn = Button.builder(Component.translatable("voxkbd.ui.config.kb.add"), b -> onAdd())
                .bounds(SIDE_PAD, y1, colW, BTN_H).build();
        deleteBtn = Button.builder(Component.translatable("voxkbd.ui.config.kb.delete"), b -> onDelete())
                .bounds(SIDE_PAD + colW + ROW_GAP, y1, colW, BTN_H).build();
        editBtn = Button.builder(Component.translatable("voxkbd.ui.config.kb.edit"), b -> onEdit())
                .bounds(SIDE_PAD + 2 * (colW + ROW_GAP), y1, colW, BTN_H).build();
        addRenderableWidget(addBtn);
        addRenderableWidget(deleteBtn);
        addRenderableWidget(editBtn);

        // --- List box
        listX = SIDE_PAD;
        listY = y1 + BTN_H + ROW_GAP;
        listW = w;
        listH = this.height - listY - (BTN_H + 14) - 8;

        // --- Back button, bottom right
        int bw = 92;
        backBtn = Button.builder(Component.translatable("voxkbd.ui.back"), b -> onBack())
                .bounds(this.width - SIDE_PAD - bw, this.height - 14 - BTN_H, bw, BTN_H).build();
        addRenderableWidget(backBtn);

        refreshRowButtons();
    }

    private void refreshRowButtons() {
        boolean editable = selectedEntry() != null && !isDefaultEntry(selectedEntry());
        deleteBtn.active = editable;
        editBtn.active = editable;
    }

    // ---- option-button labels -------------------------------------------------

    private Component vanillaFollowLabel() {
        return Component.translatable("voxkbd.ui.config.opt.vanilla_follow",
                Component.translatable(config.vanillaLockFollow ? "voxkbd.ui.on" : "voxkbd.ui.off"));
    }

    private String notifyMode() {
        List<String> types = config.switchCfg.notification.type;
        if (types == null || types.isEmpty()) return "NONE";
        if (types.contains(NOTIFY_BOSS_BAR)) return NOTIFY_BOSS_BAR;
        if (types.contains("TITLE")) return "TITLE";
        return "ACTION_BAR";
    }

    private Component notifyModeLabel() {
        String mode = notifyMode();
        String valueKey = switch (mode) {
            case "ACTION_BAR" -> "voxkbd.ui.notify.actionbar";
            case "TITLE" -> "voxkbd.ui.notify.title";
            case "BOSS_BAR" -> "voxkbd.ui.config.notification.bossbar";
            default -> "voxkbd.ui.notify.none";
        };
        return Component.translatable("voxkbd.ui.config.opt.notify",
                Component.translatable(valueKey));
    }

    private Component alwaysShowLabel() {
        String mode = config.switchCfg.notification.alwaysShow;
        String valueKey = switch (mode) {
            case "ACTION_BAR" -> "voxkbd.ui.notify.actionbar";
            case "TAB" -> "voxkbd.ui.alwaysshow.tab";
            default -> "voxkbd.ui.notify.none";
        };
        return Component.translatable("voxkbd.ui.config.opt.always_show",
                Component.translatable(valueKey));
    }

    // ---- list actions ----------------------------------------------------------

    private void onAdd() {
        Minecraft.getInstance().setScreen(
                new KeyboardEditorScreen(this, config, configManager, null));
    }

    private void onEdit() {
        Config.KeyboardEntry e = selectedEntry();
        if (e == null || isDefaultEntry(e)) return;
        Minecraft.getInstance().setScreen(
                new KeyboardEditorScreen(this, config, configManager, e));
    }

    private void onDelete() {
        Config.KeyboardEntry e = selectedEntry();
        if (e == null || isDefaultEntry(e)) return;
        config.keyboards.remove(e);
        selectedId = -1;
        refreshRowButtons();
        pushRuntimeChange();
    }

    private void onBack() {
        try {
            configManager.save();
        } catch (java.io.IOException e) {
            com.voxkbd.mod.ModRuntime.LOGGER.warn("VoxKbd: failed to save config; in-memory state preserved", e);
        }
        pushRuntimeChange();
        Minecraft.getInstance().setScreen(parent);
    }

    private void pushRuntimeChange() {
        try {
            configManager.save();
        } catch (java.io.IOException e) {
            com.voxkbd.mod.ModRuntime.LOGGER.warn("VoxKbd: real-time config save failed; in-memory state preserved", e);
        }
        if (onChanged != null) onChanged.run();
        if (statePusher != null) statePusher.run();
    }

    // ---- list interaction --------------------------------------------------------

    private int visibleRows() {
        return Math.max(1, (listH - 2 * LIST_INNER_PAD) / ROW_H);
    }

    private void clampScroll() {
        int max = Math.max(0, visibleEntries().size() - visibleRows());
        if (scrollOffset > max) scrollOffset = max;
        if (scrollOffset < 0) scrollOffset = 0;
    }

    @Override
    public boolean mouseClicked(double mx, double my, int button) {
        if (button == 0 && mx >= listX && mx <= listX + listW
                && my >= listY && my <= listY + listH) {
            int idx = scrollOffset + (int) ((my - listY - LIST_INNER_PAD) / ROW_H);
            List<Config.KeyboardEntry> entries = visibleEntries();
            if (idx >= 0 && idx < entries.size()) {
                int id = entries.get(idx).id;
                selectedId = (selectedId == id) ? -1 : id;
                refreshRowButtons();
                return true;
            }
        }
        return super.mouseClicked(mx, my, button);
    }

    // mouseScrolled: generated per-version (3-arg on 1.20/1.20.1, 4-arg from 1.20.2).
    @Override
    public boolean mouseScrolled(double mouseX, double mouseY, double scrollY) {
        if (mouseX >= listX && mouseX <= listX + listW && mouseY >= listY && mouseY <= listY + listH) {
            scrollOffset += scrollY > 0 ? -1 : 1;
            clampScroll();
            return true;
        }
        return super.mouseScrolled(mouseX, mouseY, scrollY);
    }

    // ---- rendering ----------------------------------------------------------------

    @Override
    public void render(GuiGraphics gui, int mouseX, int mouseY, float delta) {
        Font font = Minecraft.getInstance().font;
        if (font == null) return;

        gui.fill(0, 0, this.width, this.height, MasterScreen.BG);

        // List box
        gui.fill(listX, listY, listX + listW, listY + listH, MasterScreen.PANEL);
        gui.fill(listX, listY, listX + listW, listY + 1, MasterScreen.BORDER);
        gui.fill(listX, listY + listH - 1, listX + listW, listY + listH, MasterScreen.BORDER);
        gui.fill(listX, listY, listX + 1, listY + listH, MasterScreen.BORDER);
        gui.fill(listX + listW - 1, listY, listX + listW, listY + listH, MasterScreen.BORDER);

        List<Config.KeyboardEntry> entries = visibleEntries();
        clampScroll();
        if (entries.isEmpty()) {
            Component empty = Component.translatable("voxkbd.ui.config.kb.empty");
            gui.drawString(font, empty,
                    listX + (listW - font.width(empty.getString())) / 2,
                    listY + listH / 2 - 4, MasterScreen.TEXT_DIM, false);
        } else {
            int nameX = listX + 14;
            int descX = listX + (int) (listW * 0.34);
            int idW = font.width(Component.translatable("voxkbd.ui.config.kb.id").getString() + " 888");
            int idX = listX + listW - idW - 14;

            int rows = Math.min(visibleRows(), entries.size() - scrollOffset);
            for (int i = 0; i < rows; i++) {
                Config.KeyboardEntry e = entries.get(scrollOffset + i);
                int rowY = listY + LIST_INNER_PAD + i * ROW_H;
                boolean selected = e.id == selectedId;
                if (selected) {
                    gui.fill(listX + 2, rowY, listX + listW - 2, rowY + ROW_H, MasterScreen.HIGHLIGHT);
                }
                int textY = rowY + (ROW_H - 9) / 2;
                gui.drawString(font, displayNameOf(e), nameX, textY, MasterScreen.TEXT, false);
                gui.drawString(font, clip(font, descOf(e), idX - descX - 16),
                        descX, textY, MasterScreen.TEXT_DIM, false);
                gui.drawString(font, Component.translatable("voxkbd.ui.config.kb.id", e.id),
                        idX, textY, MasterScreen.TEXT_DIM, false);
            }
        }

        // Buttons render via the base pass, after the custom scene.
        super.render(gui, mouseX, mouseY, delta);
    }

    private Component displayNameOf(Config.KeyboardEntry e) {
        return MasterScreen.displayName(e.id);
    }

    private String descOf(Config.KeyboardEntry e) {
        if (isDefaultEntry(e)) {
            return Component.translatable("voxkbd.kb.0.desc").getString();
        }
        return e.description == null ? "" : e.description;
    }

    /** Clip a plain string to {@code maxWidth} with an ellipsis. */
    private static String clip(Font font, String s, int maxWidth) {
        if (s == null || s.isEmpty()) return s;
        if (font.width(s) <= maxWidth) return s;
        int w = font.width("…");
        int end = s.length();
        while (end > 0 && font.width(s.substring(0, end)) + w > maxWidth) end--;
        return s.substring(0, end) + "…";
    }
}
