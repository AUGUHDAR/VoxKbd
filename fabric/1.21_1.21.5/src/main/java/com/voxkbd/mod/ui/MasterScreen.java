package com.voxkbd.mod.ui;

import com.voxkbd.core.input.InputState;
import com.voxkbd.core.key.PhysicalKey;
import com.voxkbd.core.key.PhysicalKeyRegistry;
import com.voxkbd.core.naming.VoxKbdNames;
import com.voxkbd.mod.lock.LockManager;
import com.voxkbd.mod.screencompat.GfxCompat;
import com.voxkbd.mod.screencompat.VoxScreen;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.network.chat.Component;

import java.util.ArrayList;
import java.util.List;

/**
 * Master UI, drawn to match the approved wireframe (1.20.x–1.21.x port of the 26.2 screen;
 * rendering via GuiGraphics, clicks via the classic double/int handlers).
 */
public final class MasterScreen extends VoxScreen {

    private final com.voxkbd.core.config.Config config;
    private final LockManager lockManager;
    private final InputState inputState;
    private final Runnable onOpenConfig;
    private final Runnable statePusher;

    private final List<Cell> cells = new ArrayList<>();
    private Button undoButton;
    private Button saveButton;
    private Button finishButton;
    private Button configButton;

    // Palette (shared with the other Vox Kbd screens)
    static final int BG = 0xFF141517;
    static final int PANEL = 0xFF1F2125;
    static final int PANEL_LIGHT = 0xFF2A2D31;
    static final int BORDER = 0xFF3A3D42;
    static final int CELL_BORDER = 0xFF54585F;
    static final int TEXT = 0xFFEFEFEF;
    static final int TEXT_DIM = 0xFF9A9A9A;
    static final int ACCENT = 0xFFFFD24A;
    static final int RED = 0xFFB23A48;
    static final int RED_DARK = 0xFF7A1F2A;
    static final int HIGHLIGHT = 0xFF3D4450;

    // Layout constants
    private static final int TOP_BAR_H = 34;
    private static final int BOTTOM_BAR_H = 44;
    private static final int SIDE_PAD = 16;
    private static final int GAP = 12;
    private static final int SECTION_GAP = 12;
    private static final int CAPTION_H = 16;
    private static final int CELL_GAP = 4;

    private int mainBoxX, mainBoxY, mainBoxW, mainBoxH;
    private int numpadBoxX, numpadBoxW;
    private int mouseBoxY, mouseBoxH;
    private int padBoxY, padBoxH;

    private int lastLayoutW = 0, lastLayoutH = 0;

    /** Transient unlock warning, rendered INSIDE this screen. */
    private Component warnMessage;
    private long warnUntilMs;

    private record Cell(PhysicalKey key, int x, int y, int w, int h) {}

    public MasterScreen(com.voxkbd.core.config.Config config, LockManager lockManager, InputState inputState,
                        Runnable onOpenConfig, Runnable statePusher) {
        super(Component.translatable("voxkbd.ui.master.title"));
        this.config = config;
        this.lockManager = lockManager;
        this.inputState = inputState;
        this.onOpenConfig = onOpenConfig;
        this.statePusher = statePusher;
    }

    @Override
    protected void init() {
        super.init();
        int bh = 24;
        int by = this.height - BOTTOM_BAR_H + (BOTTOM_BAR_H - bh) / 2;
        int rightEdge = this.width - SIDE_PAD;

        Font font = Minecraft.getInstance().font;
        int hintW = font != null
                ? font.width(Component.translatable("voxkbd.ui.help.click").getString()) : 100;
        int gap = 10;
        int availForButtons = this.width - 2 * SIDE_PAD - hintW - 14;
        int bw = Math.max(38, Math.min(96, (availForButtons - 3 * gap) / 4));
        int finishX = rightEdge - bw;
        int saveX = finishX - gap - bw;
        int undoX = saveX - gap - bw;
        int configX = undoX - gap - bw;
        finishButton = Button.builder(Component.translatable("voxkbd.ui.finish"), b -> onFinish())
                .bounds(finishX, by, bw, bh).build();
        saveButton = Button.builder(Component.translatable("voxkbd.ui.save"), b -> onSave())
                .bounds(saveX, by, bw, bh).build();
        undoButton = Button.builder(Component.translatable("voxkbd.ui.master.undo"), b -> onUndo())
                .bounds(undoX, by, bw, bh).build();
        configButton = Button.builder(Component.translatable("voxkbd.ui.config"), b -> {
                    if (onOpenConfig != null) onOpenConfig.run();
                })
                .bounds(configX, by, bw, bh).build();
        addRenderableWidget(finishButton);
        addRenderableWidget(saveButton);
        addRenderableWidget(undoButton);
        addRenderableWidget(configButton);

        refreshButtons();
    }

    private void refreshButtons() {
        boolean dirty = lockManager.isDirty();
        undoButton.active = dirty;
        saveButton.active = dirty;
        finishButton.active = true;
    }

    /** Lay out the four zone boxes and the key cells inside them. */
    private void ensureLayout() {
        if (this.width <= 0 || this.height <= 0) return;
        if (this.width == lastLayoutW && this.height == lastLayoutH && !cells.isEmpty()) return;
        lastLayoutW = this.width;
        lastLayoutH = this.height;
        cells.clear();

        int contentX = SIDE_PAD;
        int contentY = TOP_BAR_H + SIDE_PAD;
        int contentW = this.width - 2 * SIDE_PAD;
        int contentH = this.height - TOP_BAR_H - BOTTOM_BAR_H - 2 * SIDE_PAD;

        int minRow1 = CAPTION_H + 12 + 18;
        int minRow2 = CAPTION_H + 12 + 2 * 16 + 6;
        int row1H = Math.max(minRow1, (int) Math.round(contentH * 0.17));
        int row2H = Math.max(minRow2, (int) Math.round(contentH * 0.23));
        if (row1H + row2H > contentH / 2) {
            row1H = minRow1;
            row2H = minRow2;
        }
        int row0H = Math.max(120, contentH - row1H - row2H - 2 * SECTION_GAP);

        // --- Row 0: main keyboard box (left) + numpad box (right) ---
        mainBoxX = contentX;
        mainBoxY = contentY;
        mainBoxW = (contentW - GAP) * 72 / 100;
        mainBoxH = row0H;
        numpadBoxX = mainBoxX + mainBoxW + GAP;
        numpadBoxW = contentW - GAP - mainBoxW;

        int mainCols = 17, mainRows = 6;
        int uByW = (mainBoxW - 16 - (mainCols + 1) * CELL_GAP) / mainCols;
        int uByH = (mainBoxH - CAPTION_H - 12 - (mainRows + 1) * CELL_GAP) / mainRows;
        int u = Math.max(10, Math.min(uByW, uByH));
        int kbTotalW = mainCols * u + (mainCols + 1) * CELL_GAP;
        int kbX = mainBoxX + (mainBoxW - kbTotalW) / 2 + CELL_GAP;
        int kbY = mainBoxY + CAPTION_H + 6 + CELL_GAP;
        renderMainKeyboard(kbX, kbY, u);

        int npCols = 5, npRows = 4;
        int npUByW = (numpadBoxW - 16 - (npCols + 1) * CELL_GAP) / npCols;
        int npUByH = (mainBoxH - CAPTION_H - 12 - (npRows + 1) * CELL_GAP) / npRows;
        int npU = Math.max(12, Math.min(npUByW, npUByH));
        int npTotalW = npCols * npU + (npCols + 1) * CELL_GAP;
        int npX = numpadBoxX + (numpadBoxW - npTotalW) / 2 + CELL_GAP;
        int npY = mainBoxY + CAPTION_H + 6 + CELL_GAP;
        renderNumpad(npX, npY, npU);

        // --- Row 1: mouse box ---
        mouseBoxY = mainBoxY + mainBoxH + SECTION_GAP;
        mouseBoxH = row1H;
        List<PhysicalKey> mice = new ArrayList<>();
        for (PhysicalKey pk : PhysicalKeyRegistry.all()) if (pk.category() == PhysicalKey.Category.MOUSE) mice.add(pk);
        int mCellW = (contentW - 24 - (mice.size() - 1) * 8) / mice.size();
        int mCellH = Math.max(14, Math.min(mouseBoxH - CAPTION_H - 12, 26));
        int mx = contentX + 12;
        int my = mouseBoxY + CAPTION_H + (mouseBoxH - CAPTION_H - mCellH) / 2;
        for (PhysicalKey pk : mice) {
            cells.add(new Cell(pk, mx, my, mCellW, mCellH));
            mx += mCellW + 8;
        }

        // --- Row 2: gamepad box ---
        padBoxY = mouseBoxY + mouseBoxH + SECTION_GAP;
        padBoxH = row2H;
        List<PhysicalKey> pads = new ArrayList<>();
        for (PhysicalKey pk : PhysicalKeyRegistry.all()) if (pk.category() == PhysicalKey.Category.GAMEPAD) pads.add(pk);
        int perRow = 9;
        int gCellW = (contentW - 24 - (perRow - 1) * 8) / perRow;
        int gCellH = Math.max(12, Math.min((padBoxH - CAPTION_H - 12 - 6) / 2, 24));
        int gX = contentX + 12;
        int gY = padBoxY + CAPTION_H + (padBoxH - CAPTION_H - 2 * gCellH - 8) / 2;
        for (int i = 0; i < pads.size(); i++) {
            cells.add(new Cell(pads.get(i), gX, gY, gCellW, gCellH));
            gX += gCellW + 8;
            if ((i + 1) % perRow == 0 && i + 1 < pads.size()) {
                gX = contentX + 12;
                gY += gCellH + 8;
            }
        }
    }

    /** US-QWERTY main keyboard on a 17u grid. */
    private void renderMainKeyboard(int x, int y, int u) {
        int xx = x; int yy = y;
        xx = place(byTokenOrNull("ESC"), xx, yy, 1, u);
        xx += u + CELL_GAP;
        for (int i = 1; i <= 12; i++) {
            xx = place(byTokenOrNull("F" + i), xx, yy, 1, u);
        }
        yy += u + CELL_GAP;
        xx = x;
        for (String tok : new String[]{"GRAVE", "D1", "D2", "D3", "D4", "D5", "D6", "D7",
                                       "D8", "D9", "D0", "MINUS", "EQUAL"}) {
            xx = place(byTokenOrNull(tok), xx, yy, 1, u);
        }
        xx = place(byTokenOrNull("BACKSPACE"), xx, yy, 2, u);
        xx += u + CELL_GAP;
        xx = place(byTokenOrNull("HOME"), xx, yy, 1, u);
        yy += u + CELL_GAP;
        xx = x;
        xx = place(byTokenOrNull("TAB"), xx, yy, 1, u);
        for (char c : new char[]{'Q','W','E','R','T','Y','U','I','O','P'}) {
            xx = place(byTokenOrNull(String.valueOf(c)), xx, yy, 1, u);
        }
        xx = place(byTokenOrNull("LBRACKET"), xx, yy, 1, u);
        xx = place(byTokenOrNull("RBRACKET"), xx, yy, 1, u);
        xx = place(byTokenOrNull("BACKSLASH"), xx, yy, 1, u);
        xx += 2 * (u + CELL_GAP);
        xx = place(byTokenOrNull("PAGEUP"), xx, yy, 1, u);
        yy += u + CELL_GAP;
        xx = x;
        xx = place(byTokenOrNull("CAPSLOCK"), xx, yy, 2, u);
        for (char c : new char[]{'A','S','D','F','G','H','J','K','L'}) {
            xx = place(byTokenOrNull(String.valueOf(c)), xx, yy, 1, u);
        }
        xx = place(byTokenOrNull("SEMICOLON"), xx, yy, 1, u);
        xx = place(byTokenOrNull("APOSTROPHE"), xx, yy, 1, u);
        xx = place(byTokenOrNull("ENTER"), xx, yy, 2, u);
        xx += u + CELL_GAP;
        xx = place(byTokenOrNull("PAGEDOWN"), xx, yy, 1, u);
        yy += u + CELL_GAP;
        xx = x;
        xx = place(byTokenOrNull("LSHIFT"), xx, yy, 2, u);
        for (char c : new char[]{'Z','X','C','V','B','N','M'}) {
            xx = place(byTokenOrNull(String.valueOf(c)), xx, yy, 1, u);
        }
        xx = place(byTokenOrNull("COMMA"), xx, yy, 1, u);
        xx = place(byTokenOrNull("PERIOD"), xx, yy, 1, u);
        xx = place(byTokenOrNull("SLASH"), xx, yy, 1, u);
        xx = place(byTokenOrNull("RSHIFT"), xx, yy, 3, u);
        xx = place(byTokenOrNull("UP"), xx, yy, 1, u);
        xx = place(byTokenOrNull("END"), xx, yy, 1, u);
        yy += u + CELL_GAP;
        xx = x;
        xx = place(byTokenOrNull("LCTRL"), xx, yy, 1, u);
        xx = place(byTokenOrNull("LWIN"), xx, yy, 1, u);
        xx = place(byTokenOrNull("LALT"), xx, yy, 1, u);
        xx = place(byTokenOrNull("SPACE"), xx, yy, 6, u);
        xx = place(byTokenOrNull("RALT"), xx, yy, 1, u);
        xx = place(byTokenOrNull("RWIN"), xx, yy, 1, u);
        xx = place(byTokenOrNull("RCTRL"), xx, yy, 1, u);
        xx += 2 * (u + CELL_GAP);
        xx = place(byTokenOrNull("LEFT"), xx, yy, 1, u);
        xx = place(byTokenOrNull("DOWN"), xx, yy, 1, u);
        xx = place(byTokenOrNull("RIGHT"), xx, yy, 1, u);
    }

    /** 4 rows; the bottom row has KP_0 spanning 2u. */
    private void renderNumpad(int x, int y, int u) {
        int xx = x; int yy = y;
        xx = place(byTokenOrNull("KP_7"), xx, yy, 1, u);
        xx = place(byTokenOrNull("KP_8"), xx, yy, 1, u);
        xx = place(byTokenOrNull("KP_9"), xx, yy, 1, u);
        xx = place(byTokenOrNull("KP_DIVIDE"), xx, yy, 1, u);
        yy += u + CELL_GAP;
        xx = x;
        xx = place(byTokenOrNull("KP_4"), xx, yy, 1, u);
        xx = place(byTokenOrNull("KP_5"), xx, yy, 1, u);
        xx = place(byTokenOrNull("KP_6"), xx, yy, 1, u);
        xx = place(byTokenOrNull("KP_MULTIPLY"), xx, yy, 1, u);
        yy += u + CELL_GAP;
        xx = x;
        xx = place(byTokenOrNull("KP_1"), xx, yy, 1, u);
        xx = place(byTokenOrNull("KP_2"), xx, yy, 1, u);
        xx = place(byTokenOrNull("KP_3"), xx, yy, 1, u);
        xx = place(byTokenOrNull("KP_SUBTRACT"), xx, yy, 1, u);
        yy += u + CELL_GAP;
        xx = x;
        xx = place(byTokenOrNull("KP_0"), xx, yy, 2, u);
        xx = place(byTokenOrNull("KP_DECIMAL"), xx, yy, 1, u);
        xx = place(byTokenOrNull("KP_ENTER"), xx, yy, 1, u);
        xx = place(byTokenOrNull("KP_ADD"), xx, yy, 1, u);
    }

    private static PhysicalKey byTokenOrNull(String token) {
        return PhysicalKeyRegistry.byToken(token);
    }

    /** Place a key cell and return the x cursor for the NEXT key (full width + gap). */
    private int place(PhysicalKey pk, int x, int y, int widthUnits, int u) {
        if (pk != null) {
            int w = widthUnits * u + (widthUnits - 1) * CELL_GAP;
            cells.add(new Cell(pk, x, y, w, u));
        }
        return x + widthUnits * (u + CELL_GAP);
    }

    @Override
    public void render(GuiGraphics gui, int mouseX, int mouseY, float delta) {
        ensureLayout();
        Font font = Minecraft.getInstance().font;
        if (font == null) return;

        // --- Background
        gui.fill(0, 0, this.width, this.height, BG);

        // --- Top bar: title left, active keyboard right, full-width divider below
        gui.fill(0, 0, this.width, TOP_BAR_H, PANEL);
        gui.drawString(font, this.title, SIDE_PAD, (TOP_BAR_H - 9) / 2, ACCENT, false);
        int activeKb = inputState.activeKeyboard();
        Component activeLabel = Component.translatable("voxkbd.ui.active",
                activeKb == com.voxkbd.core.Constants.VANILLA_KB_INDEX
                        ? Component.translatable("voxkbd.kb.0")
                        : displayName(activeKb));
        int tw = font.width(activeLabel.getString());
        gui.drawString(font, activeLabel, this.width - tw - SIDE_PAD, (TOP_BAR_H - 9) / 2, TEXT, false);
        gui.fill(0, TOP_BAR_H, this.width, TOP_BAR_H + 1, BORDER);

        // --- Zone boxes + captions
        drawZoneBox(gui, font, Component.translatable("voxkbd.ui.section.keyboard"),
                mainBoxX, mainBoxY, mainBoxW, mainBoxH, true);
        drawZoneBox(gui, font, Component.translatable("voxkbd.ui.section.numpad"),
                numpadBoxX, mainBoxY, numpadBoxW, mainBoxH, true);
        drawZoneBox(gui, font, Component.translatable("voxkbd.ui.section.mouse"),
                SIDE_PAD, mouseBoxY, this.width - 2 * SIDE_PAD, mouseBoxH, false);
        drawZoneBox(gui, font, Component.translatable("voxkbd.ui.section.gamepad"),
                SIDE_PAD, padBoxY, this.width - 2 * SIDE_PAD, padBoxH, false);

        // --- Bottom bar: hint left, buttons are real widgets on top
        int by = this.height - BOTTOM_BAR_H;
        gui.fill(0, by, this.width, this.height, PANEL);
        gui.fill(0, by, this.width, by + 1, BORDER);
        gui.drawString(font, Component.translatable("voxkbd.ui.help.click"),
                SIDE_PAD, by + (BOTTOM_BAR_H - 9) / 2, TEXT_DIM, false);

        // --- Transient warning, drawn inside the UI
        if (warnMessage != null) {
            if (System.currentTimeMillis() < warnUntilMs) {
                int ww = font.width(warnMessage.getString());
                gui.fill(this.width / 2 - ww / 2 - 6, by - 20, this.width / 2 + ww / 2 + 6, by - 4,
                        PANEL & 0x00FFFFFF | 0xC8000000);
                gui.drawString(font, warnMessage, this.width / 2 - ww / 2, by - 17, 0xFFFF7B7B, false);
            } else {
                warnMessage = null;
            }
        }

        // --- Cells
        java.util.Set<String> locked = lockManager.currentLocked();
        for (Cell cell : cells) {
            boolean isLocked = locked.contains(cell.key.token());
            gui.fill(cell.x, cell.y, cell.x + cell.w, cell.y + cell.h, isLocked ? RED : PANEL_LIGHT);
            gui.fill(cell.x, cell.y, cell.x + cell.w, cell.y + 1, CELL_BORDER);
            gui.fill(cell.x, cell.y + cell.h - 1, cell.x + cell.w, cell.y + cell.h, CELL_BORDER);
            gui.fill(cell.x, cell.y, cell.x + 1, cell.y + cell.h, CELL_BORDER);
            gui.fill(cell.x + cell.w - 1, cell.y, cell.x + cell.w, cell.y + cell.h, CELL_BORDER);
            if (isLocked) {
                gui.fill(cell.x, cell.y, cell.x + cell.w, cell.y + cell.h, RED_DARK & 0x00FFFFFF | 0x44000000);
            }

            // Keycap labels are identical on every keyboard layer: natural keycap text.
            String label = cell.key.label();
            int maxW = cell.w - 4;
            int fontW = font.width(label);
            float scale = fontW > maxW && maxW > 0 ? Math.max(0.35f, (float) maxW / fontW) : 1.0f;
            int cx = cell.x + cell.w / 2;
            int cy = cell.y + cell.h / 2;
            if (scale >= 0.999f) {
                gui.drawCenteredString(font, label, cx, cy - 4, isLocked ? 0xFFFFFFFF : TEXT);
            } else {
                GfxCompat.scaledCenteredText(gui, font, label, cx, cy, scale,
                        isLocked ? 0xFFFFFFFF : TEXT);
            }
        }

        // Widgets (bottom-bar buttons) are rendered by the base Screen pass — AFTER the custom
        // scene or the fills would paint over them.
        super.render(gui, mouseX, mouseY, delta);
    }

    /** Bordered zone box with its caption inside. */
    private void drawZoneBox(GuiGraphics gui, Font font, Component caption,
                             int x, int y, int w, int h, boolean centered) {
        if (w <= 0 || h <= 0) return;
        gui.fill(x, y, x + w, y + h, PANEL);
        gui.fill(x, y, x + w, y + 1, BORDER);
        gui.fill(x, y + h - 1, x + w, y + h, BORDER);
        gui.fill(x, y, x + 1, y + h, BORDER);
        gui.fill(x + w - 1, y, x + w, y + h, BORDER);
        int tx = centered ? x + (w - font.width(caption.getString())) / 2 : x + 10;
        gui.drawString(font, caption, tx, y + 4, TEXT_DIM, false);
    }

    /** Resolve a keyboard's display name: user-set name, i18n key, then a generic "键盘 N". */
    public static Component displayName(int kbId) {
        var cfg = com.voxkbd.mod.ModRuntime.CONFIG;
        if (cfg != null && kbId != com.voxkbd.core.Constants.VANILLA_KB_INDEX) {
            var entry = cfg.keyboardById(kbId);
            if (entry != null && entry.displayName != null && !entry.displayName.isEmpty()) {
                return Component.literal(entry.displayName);
            }
        }
        String key = VoxKbdNames.keyboardNameKey(kbId);
        String resolved = net.minecraft.client.resources.language.I18n.get(key);
        if (!resolved.equals(key)) {
            return Component.translatable(key);
        }
        return Component.translatable("voxkbd.kb.default", kbId);
    }

    @Override
    public boolean mouseClicked(double mouseX, double mouseY, int button) {
        for (Cell cell : cells) {
            if (mouseX >= cell.x && mouseX <= cell.x + cell.w && mouseY >= cell.y && mouseY <= cell.y + cell.h) {
                if (button == 0) {
                    lockManager.lock(cell.key.token());
                } else if (button == 1) {
                    String warn = lockManager.unlock(cell.key.token());
                    if (warn != null) {
                        warnMessage = Component.translatable(warn);
                        warnUntilMs = System.currentTimeMillis() + 3000;
                    }
                }
                refreshButtons();
                return true;
            }
        }
        return super.mouseClicked(mouseX, mouseY, button);
    }

    private void onUndo() {
        lockManager.undo();
        refreshButtons();
    }

    private void onSave() {
        lockManager.markSaved();
        if (statePusher != null) statePusher.run();
        refreshButtons();
    }

    private void onFinish() {
        // Lock state is already persisted in real time — finishing just exits.
        Minecraft.getInstance().setScreen(null);
    }
}
