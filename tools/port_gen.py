#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""Vox Kbd multi-version port target generator (voxlink-style layout).

Directory logic mirrors D:/桌面/voxlink:

    <loader>/<mc-group>/          standalone Gradle project, ONE jar per group
    成品/<Loader>/                final jars: voxkbd-<ver>-<group>-<loader>.jar

Sources assembled into every target:
  port/shared/java       loader-agnostic mod runtime (1.20 - 26.x)
  port/shared/mixin      KeyMappingAccessor + VoxKbdHudMixin
  port/shared/resources  assets + voxkbd.mixins.json (loader adjusted here)
  voxkbd-core            copied as-is (group-agnostic contract layer)
  voxkbd-daemon          copied; the Windows hook variant depends on the era
                         (legacy = JNA, modern = FFM/Java 25)
  voxkbd-mixin / voxkbd-fclbridge   Android backend, Fabric targets only

Usage: python tools/port_gen.py [<loader>/<group> ...]   (no args = all targets)
"""
import json
import os
import re
import shutil
import sys

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
SHARED = os.path.join(ROOT, "port", "shared")

# ------------------------------------------------------------------ constants

MOD_VERSION = "1.0.1"

JDK17 = "C:/Program Files/Java/jdk-17"
JDK21 = "C:/Program Files/Java/jdk-21"
JDK25 = "C:/Program Files/Java/jdk-25.0.2"

GRADLE_FABRIC = "9.2.1"      # fabric + neoforge groups
GRADLE_FG6 = "8.11.1"        # forge 1.20.x (ForgeGradle 6)
GRADLE_FG7 = "9.5.0"         # forge 1.20.6+ (ForgeGradle 7)

LOOM_LEGACY = "1.14.9"       # net.fabricmc.fabric-loom-remap  (official mappings + remap)
LOOM_MODERN = "1.15.5"       # net.fabricmc.fabric-loom        (MC 26.x ships official names)

MDG_VERSION = "2.0.143"      # net.neoforged.moddev
FG6_RANGE = "[6.0,6.2)"
FG7_RANGE = "[7.0.3,8)"
FG7_EXACT = "7.0.31"

JNA_VERSION = "5.14.0"

ERAS = ("legacy", "modern")


# ------------------------------------------------------------------ target matrix
# Mirrors the proven D:/桌面/voxlink matrix (group labels, MC lines, loader/JDK levels),
# keeping VoxKbd's own source shim flags.

def fab(group, mc, rng, api, release, loader, *, era="legacy", jvm=JDK21, gradle=GRADLE_FABRIC, **shim):
    d = dict(loader="fabric", group=group, mc=mc, mc_range=rng, fabric_loader=loader,
             fabric_api=api, release=release, era=era, jvm=jvm, gradle=gradle)
    d.update(shim)
    return d


def frg(group, mc, rng, forge, release, plugin, *, era="legacy", jvm=JDK21, gradle=GRADLE_FG6, **shim):
    d = dict(loader="forge", group=group, mc=mc, mc_range=rng, forge=forge, release=release,
             fg=plugin, era=era, jvm=jvm, gradle=gradle)
    d.update(shim)
    return d


def neo(group, mc, rng, version, release, *, era="legacy", jvm=JDK21, gradle=GRADLE_FABRIC,
        legacyforge=False, **shim):
    d = dict(loader="neoforge", group=group, mc=mc, mc_range=rng, neo=version, release=release,
             era=era, jvm=jvm, gradle=gradle, legacyforge=legacyforge)
    d.update(shim)
    return d


F = [
    # ---------------------------------------------------------------- Fabric 1.20.x
    fab("1.20_1.20.1", "1.20.1", ">=1.20 <=1.20.1", "0.92.12+1.20.1", 17, "0.16.14",
        scroll3=True, render_bg=False, screen_compat="old"),
    fab("1.20.2_1.20.6", "1.20.6", ">=1.20.2 <=1.20.6", "0.100.8+1.20.6", 17, "0.16.14",
        screen_compat="old"),
    # ---------------------------------------------------------------- Fabric 1.21.x
    fab("1.21_1.21.5", "1.21.5", ">=1.21 <=1.21.5", "0.128.2+1.21.5", 21, "0.16.10"),
    fab("1.21.6_1.21.8", "1.21.8", ">=1.21.6 <=1.21.8", "0.136.1+1.21.8", 21, "0.16.10",
        gfx3x2=True),
    fab("1.21.9_1.21.10", "1.21.9", ">=1.21.9 <=1.21.10", "0.134.1+1.21.9", 21, "0.16.10",
        gfx3x2=True, dist_era="new"),
    fab("1.21.11", "1.21.11", "1.21.11", "0.141.4+1.21.11", 21, "0.19.2",
        gfx3x2=True, dist_era="new", identifier=True),
    # ---------------------------------------------------------------- Fabric 26.x
    fab("26.1_26.1.2", "26.1.2", ">=26.1 <=26.1.2", "0.154.2+26.1.2", 25, "0.19.3",
        era="modern", gui_era="26.1", jvm=JDK25, gfx3x2=True, dist_era="new", identifier=True),
    fab("26.2", "26.2", ">=26.2 <26.3", "0.154.2+26.2", 25, "0.19.3",
        era="modern", jvm=JDK25, gfx3x2=True, dist_era="new", identifier=True),
    fab("26.3", "26.3", ">=26.3 <=26.99", "0.160.6+26.3", 25, "0.19.5",
        era="modern", jvm=JDK25, gfx3x2=True, dist_era="new", identifier=True, sdl=True),
]

G = [
    # ---------------------------------------------------------------- Forge 1.20.x
    frg("1.20_1.20.1", "1.20.1", "[1.20.1,1.20.2)", "1.20.1-47.4.10", 17, FG6_RANGE,
        jvm=JDK17, scroll3=True, render_bg=False, screen_compat="old", reobf=True),
    frg("1.20.2_1.20.4", "1.20.4", "[1.20.2,1.20.5)", "1.20.4-49.2.8", 17, FG6_RANGE,
        jvm=JDK17, screen_compat="old"),
    # ---------------------------------------------------------------- Forge 1.21.x
    frg("1.20.6", "1.20.6", "[1.20.6,1.20.7)", "1.20.6-50.2.10", 21, FG7_RANGE,
        jvm=JDK21, gradle=GRADLE_FG7, screen_compat="old"),
    frg("1.21_1.21.5", "1.21.5", "[1.21,1.21.6)", "1.21.5-55.1.11", 21, FG7_RANGE,
        jvm=JDK21, gradle=GRADLE_FG7),
    frg("1.21.6_1.21.8", "1.21.8", "[1.21.6,1.21.9)", "1.21.8-58.1.20", 21, FG7_RANGE,
        jvm=JDK21, gradle=GRADLE_FG7, gfx3x2=True),
    frg("1.21.9_1.21.10", "1.21.10", "[1.21.9,1.21.11)", "1.21.10-60.1.13", 21, FG7_RANGE,
        jvm=JDK21, gradle=GRADLE_FG7, gfx3x2=True, dist_era="new"),
    frg("1.21.11", "1.21.11", "[1.21.11,1.22)", "1.21.11-61.1.14", 21, FG7_EXACT,
        jvm=JDK21, gradle=GRADLE_FG7, gfx3x2=True, dist_era="new", identifier=True),
    # ---------------------------------------------------------------- Forge 26.x (no Forge 26.3 yet)
    frg("26.1_26.1.2", "26.1.2", "[26.1,26.2)", "26.1.2-64.1.0", 25, FG7_RANGE,
        jvm=JDK25, gradle=GRADLE_FG7, era="modern", gui_era="26.1", gfx3x2=True,
        dist_era="new", identifier=True),
    frg("26.2", "26.2", "[26.2,26.3)", "26.2-65.1.0", 25, FG7_RANGE,
        jvm=JDK25, gradle=GRADLE_FG7, era="modern", gfx3x2=True, dist_era="new", identifier=True),
]

N = [
    # ---------------------------------------------------------------- NeoForge 1.20.x
    neo("1.20.1", "1.20.1", "[1.20.1,1.20.2)", "1.20.1-47.3.0", 17, jvm=JDK17,
        legacyforge=True, scroll3=True, render_bg=False, screen_compat="old"),
    neo("1.20.4_1.20.6", "1.20.6", "[1.20.4,1.20.7)", "20.6.125", 21, jvm=JDK21,
        screen_compat="old"),
    # ---------------------------------------------------------------- NeoForge 1.21.x
    neo("1.21_1.21.5", "1.21.5", "[1.21,1.21.6)", "21.5.98", 21, jvm=JDK21),
    neo("1.21.6_1.21.8", "1.21.8", "[1.21.6,1.21.9)", "21.8.54", 21, jvm=JDK21, gfx3x2=True),
    neo("1.21.9_1.21.10", "1.21.10", "[1.21.9,1.21.11)", "21.10.64", 21, jvm=JDK21,
        gfx3x2=True, dist_era="new"),
    neo("1.21.11", "1.21.11", "[1.21.11,1.22)", "21.11.45", 21, jvm=JDK21,
        gfx3x2=True, dist_era="new", identifier=True),
    # ---------------------------------------------------------------- NeoForge 26.x
    neo("26.1_26.1.2", "26.1.2", "[26.1,26.2)", "26.1.2.53-beta", 25, jvm=JDK25,
        era="modern", gui_era="26.1", gfx3x2=True, dist_era="new", identifier=True),
    neo("26.2", "26.2", "[26.2,26.3)", "26.2.0.40-beta", 25, jvm=JDK25,
        era="modern", gfx3x2=True, dist_era="new", identifier=True),
    neo("26.3", "26.3", "[26.3,26.4)", "26.3.0.1-beta", 25, jvm=JDK25,
        era="modern", gfx3x2=True, dist_era="new", identifier=True, sdl=True),
]

TARGETS = {}
for _t in F + G + N:
    key = f"{_t['loader']}/{_t['group']}"
    if key in TARGETS:
        raise SystemExit(f"duplicate target {key}")
    TARGETS[key] = _t

DISPLAY = {"fabric": "Fabric", "forge": "Forge", "neoforge": "NeoForge"}


def tdir_of(t):
    return os.path.join(ROOT, t["loader"], t["group"])


def jar_name(t):
    return f"voxkbd-{MOD_VERSION}-{t['group']}-{t['loader']}.jar"


# ------------------------------------------------------------------ helpers

def write(path, content):
    os.makedirs(os.path.dirname(path), exist_ok=True)
    with open(path, "w", encoding="utf-8", newline="\n") as f:
        f.write(content)


def copy(src, dst):
    os.makedirs(os.path.dirname(dst), exist_ok=True)
    shutil.copy2(src, dst)


def copy_tree(src, dst, exclude=()):
    for dirpath, dirnames, filenames in os.walk(src):
        dirnames[:] = [d for d in dirnames if d not in (".workbuddy",)]
        for fn in filenames:
            full = os.path.join(dirpath, fn)
            rel = os.path.relpath(full, src)
            if any(re.match(p, rel.replace(os.sep, "/")) for p in exclude):
                continue
            dstf = os.path.join(dst, rel)
            os.makedirs(os.path.dirname(dstf), exist_ok=True)
            shutil.copy2(full, dstf)


def patch_file(path, old, new, *, required=True):
    """In-place textual patch on a generated source file. Returns True when applied."""
    s = open(path, encoding="utf-8").read()
    if old not in s:
        if required:
            raise SystemExit(f"patch anchor missing in {os.path.relpath(path, ROOT)}: {old[:70]!r}")
        return False
    open(path, "w", encoding="utf-8", newline="\n").write(s.replace(old, new))
    return True


# ------------------------------------------------------------------ shim emitters

SCREENCOMPAT_IMPORTS = {
    "old": "import net.minecraft.client.gui.screens.controls.KeyBindsScreen;",
    "options": "import net.minecraft.client.gui.screens.options.controls.KeyBindsScreen;",
}


def emit_screen_compat(tdir, t):
    imp = SCREENCOMPAT_IMPORTS[t.get("screen_compat", "options")]
    write(os.path.join(tdir, "src/main/java/com/voxkbd/mod/screencompat/ScreenCompat.java"),
f'''package com.voxkbd.mod.screencompat;

{imp}

/** Per-target vanilla key-binds screen resolution (package moved across releases). */
public final class ScreenCompat {{
    private ScreenCompat() {{}}
    public static Class<?> keyBindsScreenClass() {{ return KeyBindsScreen.class; }}
}}
''')


def emit_vox_screen(tdir, t):
    bg = ""
    if t.get("render_bg", True):
        bg = '''
    @Override
    public void renderBackground(GuiGraphics guiGraphics, int mouseX, int mouseY, float partialTick) {
        // Vox Kbd screens draw their own full-screen background.
    }
'''
    write(os.path.join(tdir, "src/main/java/com/voxkbd/mod/screencompat/VoxScreen.java"),
f'''package com.voxkbd.mod.screencompat;

import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;

/** Shared base for all Vox Kbd screens (per-version compatibility point). */
public abstract class VoxScreen extends Screen {{

    protected VoxScreen(Component title) {{
        super(title);
    }}
{bg}
}}
''')


def emit_gfx_compat(tdir, t):
    if t.get("gfx3x2"):
        body = '''        var pose = g.pose();
        pose.pushMatrix();
        pose.translate((float) cx, (float) cy);
        pose.scale(scale, scale);
        pose.translate((float) -cx, (float) -cy);
        g.drawCenteredString(font, text, cx, cy - 4, color);
        pose.popMatrix();'''
        doc = "1.21.6+ variant: GuiGraphics.pose() returns a Matrix3x2fStack."
    else:
        body = '''        var pose = g.pose();
        pose.pushPose();
        pose.translate((float) cx, (float) cy, 0.0F);
        pose.scale(scale, scale, 1.0F);
        pose.translate((float) -cx, (float) -cy, 0.0F);
        g.drawCenteredString(font, text, cx, cy - 4, color);
        pose.popPose();'''
        doc = "1.20.x-1.21.5 variant: GuiGraphics.pose() returns a PoseStack."
    write(os.path.join(tdir, "src/main/java/com/voxkbd/mod/screencompat/GfxCompat.java"),
f'''package com.voxkbd.mod.screencompat;

import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;

/** Text-scaling helper for keycap labels. {doc} */
public final class GfxCompat {{
    private GfxCompat() {{}}

    public static void scaledCenteredText(GuiGraphics g, Font font, String text,
                                          int cx, int cy, float scale, int color) {{
{body}
    }}
}}
''')


def emit_config_screen_scroll(tdir, t):
    if not t.get("scroll3"):
        return
    patch_file(os.path.join(tdir, "src/main/java/com/voxkbd/mod/ui/ConfigScreen.java"),
               "public boolean mouseScrolled(double mouseX, double mouseY, double scrollX, double scrollY) {",
               "public boolean mouseScrolled(double mouseX, double mouseY, double scrollY) {")
    patch_file(os.path.join(tdir, "src/main/java/com/voxkbd/mod/ui/ConfigScreen.java"),
               "return super.mouseScrolled(mouseX, mouseY, scrollX, scrollY);",
               "return super.mouseScrolled(mouseX, mouseY, scrollY);")


def emit_switch_notifier_bossbar(tdir, t):
    if t.get("boss_overlay", True):
        return
    patch_file(os.path.join(tdir, "src/main/java/com/voxkbd/mod/notification/SwitchNotifier.java"),
"""        return new LerpingBossEvent(BOSS_BAR_UUID, text, 1.0f,
                BossEvent.BossBarColor.BLUE, BossEvent.BossBarOverlay.PROGRESS,
                false, false, false);""",
"""        return new LerpingBossEvent(BOSS_BAR_UUID, text, 1.0f,
                BossEvent.BossBarColor.BLUE,
                false, false, false);""")


def emit_new_era_patches(tdir, t):
    """1.21.9+ API era: Window#handle, KeyMapping.Category, Identifier factory,
    MouseButtonEvent mouseClicked. Applied to the generated tree (post-copy).

    On 26.3 the window handle is still a plain long, but FocusListener/GlfwDeliverer are rewritten
    wholesale for SDL afterwards, so their handle patches are skipped here."""
    src = os.path.join(tdir, "src/main/java")

    if not t.get("sdl"):
        patch_file(os.path.join(src, "com/voxkbd/mod/glfw/FocusListener.java"),
                   "long window = mc.getWindow().getWindow();",
                   "long window = mc.getWindow().handle();")
        patch_file(os.path.join(src, "com/voxkbd/mod/glfw/GlfwDeliverer.java"),
                   "long window = mc.getWindow().getWindow();",
                   "long window = mc.getWindow().handle();")
    # Written with the 1.21.9-era name: the `identifier` pass renames ResourceLocation -> Identifier
    # afterwards for 1.21.11+ (the class only moved in 1.21.11, not in 1.21.9).
    patch_file(os.path.join(src, "com/voxkbd/mod/glfw/KeybindingRegistry.java"),
               'public static final String CATEGORY = "key.category.voxkbd.category";',
               'public static final KeyMapping.Category CATEGORY = KeyMapping.Category.register('
               'net.minecraft.resources.ResourceLocation.fromNamespaceAndPath("voxkbd", "category"));')

    mbe_sig = "public boolean mouseClicked(net.minecraft.client.input.MouseButtonEvent ev, boolean doubled) {"
    for rel, coords in (("com/voxkbd/mod/ui/MasterScreen.java", ("mouseX", "mouseY")),
                        ("com/voxkbd/mod/ui/ConfigScreen.java", ("mx", "my"))):
        x, y = coords
        if patch_file(os.path.join(src, rel),
                      f"public boolean mouseClicked(double {x}, double {y}, int button) {{",
                      mbe_sig + f"\n        double {x} = ev.x(), {y} = ev.y();\n        int button = ev.button();",
                      required=False):
            patch_file(os.path.join(src, rel),
                       f"return super.mouseClicked({x}, {y}, button);",
                       "return super.mouseClicked(ev, doubled);")


# MC 26.x renamed the whole GUI rendering model. Common to every 26.x line:
#   GuiGraphics -> GuiGraphicsExtractor, render() -> extractRenderState(), draw*() -> text*()
MODERN_COMMON_REGEX = [
    (r"\bGuiGraphics\b", "GuiGraphicsExtractor"),
    (r"\bdrawString\(", "text("),
    (r"\bdrawCenteredString\(", "centeredText("),
]

MODERN_COMMON_LITERAL = [
    ("public void render(GuiGraphicsExtractor", "public void extractRenderState(GuiGraphicsExtractor"),
    ("public void renderBackground(GuiGraphicsExtractor",
     "public void extractBackground(GuiGraphicsExtractor"),
    ("super.render(", "super.extractRenderState("),
]

# 26.2 moved the screen accessor and the HUD surface behind Minecraft.gui / Gui.hud.
# 26.1 still has Minecraft#screen, Minecraft#setScreen and the HUD surface directly on Gui.
MODERN_262_REGEX = [
    (r"\bclient\.screen", "client.gui.screen()"),
]

MODERN_262_LITERAL = [
    ("Minecraft.getInstance().setScreen(", "Minecraft.getInstance().gui.setScreen("),
    ("client.setScreen(", "client.gui.setScreen("),
    ("client.gui.setOverlayMessage(", "client.gui.hud.setOverlayMessage("),
    ("client.gui.setTitle(", "client.gui.hud.setTitle("),
    ("client.gui.setSubtitle(", "client.gui.hud.setSubtitle("),
    ("client.gui.setTimes(", "client.gui.hud.setTimes("),
    ("client.gui.getBossOverlay()", "client.gui.hud.getBossOverlay()"),
]


def emit_modern_patches(tdir, t):
    """MC 26.x API era, applied on top of the 1.21.9+ patches."""
    src = os.path.join(tdir, "src/main/java")
    gui_era = t.get("gui_era", "26.2")
    regexes = list(MODERN_COMMON_REGEX)
    literal = list(MODERN_COMMON_LITERAL)
    if gui_era != "26.1":
        regexes += MODERN_262_REGEX
        literal += MODERN_262_LITERAL

    for dirpath, _dirnames, filenames in os.walk(src):
        for fn in filenames:
            if not fn.endswith(".java"):
                continue
            p = os.path.join(dirpath, fn)
            s = open(p, encoding="utf-8").read()
            orig = s
            for pat, rep in regexes:
                s = re.sub(pat, rep, s)
            for pat, rep in literal:
                s = s.replace(pat, rep)
            if s != orig:
                open(p, "w", encoding="utf-8", newline="\n").write(s)

    emit_hud_mixin(tdir, t)

    if t.get("sdl"):
        emit_sdl_shims(tdir, t)


def emit_hud_mixin(tdir, t):
    """The HUD tab hooks the vanilla HUD renderer. 26.1 draws the HUD from Gui; 26.2+ moved it to
    net.minecraft.client.gui.Hud. Both expose extractRenderState(GuiGraphicsExtractor, DeltaTracker)."""
    target = "Gui" if t.get("gui_era") == "26.1" else "Hud"
    write(os.path.join(tdir, "src/main/java/com/voxkbd/mixin/VoxKbdHudMixin.java"),
f'''package com.voxkbd.mixin;

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
@Mixin(net.minecraft.client.gui.{target}.class)
public abstract class VoxKbdHudMixin {{

    @Inject(method = "extractRenderState", at = @At("TAIL"))
    private void voxkbd$drawAlwaysShowTab(GuiGraphicsExtractor guiGraphics, DeltaTracker deltaTracker,
                                          CallbackInfo ci) {{
        try {{
            ModRuntime.onHudRender(guiGraphics, 0.0f);
        }} catch (Throwable ignored) {{
            // The tab is cosmetic — never break the HUD.
        }}
    }}
}}
''')


# ---------------------------------------------------------------- MC 26.3 (SDL input era)

def replace_tree(src, pairs):
    """Plain textual replacements across every generated .java file."""
    for dirpath, _dirnames, filenames in os.walk(src):
        for fn in filenames:
            if not fn.endswith(".java"):
                continue
            p = os.path.join(dirpath, fn)
            s = open(p, encoding="utf-8").read()
            orig = s
            for old, new in pairs:
                s = s.replace(old, new)
            if s != orig:
                open(p, "w", encoding="utf-8", newline="\n").write(s)


def emit_sdl_shims(tdir, t):
    """MC 26.3 replaced GLFW with SDL3: there is no org.lwjgl.glfw at all.

    Three touch points change shape:
      * the window handle is a plain long (Window#handle) and focus is polled, not callback-driven;
      * synthetic keys are injected through KeyboardHandler#keyPress(handle, action, KeyEvent);
      * the key spaces are SDL scancodes, and InputConstants.Type.KEYSYM became Type.KEYBOARD.
    """
    src = os.path.join(tdir, "src/main/java")
    glfw = os.path.join(src, "com/voxkbd/mod/glfw")

    # InputConstants.Type.KEYSYM -> Type.KEYBOARD (SDL key space) everywhere it is used.
    replace_tree(src, [("InputConstants.Type.KEYSYM", "InputConstants.Type.KEYBOARD")])

    # --- focus: no GLFW callback exists; poll Window#isFocused from the client tick instead.
    write(os.path.join(glfw, "FocusListener.java"),
'''package com.voxkbd.mod.glfw;

import com.voxkbd.core.input.InputState;
import net.minecraft.client.Minecraft;

/**
 * Window-focus tracking (MC 26.3 / SDL era). SDL exposes no focus callback through MC's window
 * wrapper, so focus is polled from the client tick; the state is pushed only when it changes.
 */
public final class FocusListener {

    private static InputState state;
    private static Runnable pusher;
    private static boolean lastFocus = true;

    private FocusListener() {}

    public static void register(InputState inputState, Runnable statePusher) {
        state = inputState;
        pusher = statePusher;
        Minecraft mc = Minecraft.getInstance();
        lastFocus = mc == null || mc.getWindow() == null || mc.getWindow().isFocused();
        inputState.setFocus(lastFocus);
        if (statePusher != null) statePusher.run();
    }

    /** Called from the client tick; cheap (a single boolean read on the window). */
    public static void poll() {
        if (state == null) return;
        Minecraft mc = Minecraft.getInstance();
        if (mc == null || mc.getWindow() == null) return;
        boolean focused = mc.getWindow().isFocused();
        if (focused != lastFocus) {
            lastFocus = focused;
            state.setFocus(focused);
            if (pusher != null) pusher.run();
        }
    }
}
''')

    # --- delivery: KeyboardHandler#keyPress is the SDL-era equivalent of the GLFW key callback.
    write(os.path.join(glfw, "GlfwDeliverer.java"),
'''package com.voxkbd.mod.glfw;

import com.voxkbd.core.key.KeycodeTable;
import com.voxkbd.core.naming.VoxKbdNames;
import net.minecraft.client.Minecraft;
import net.minecraft.client.input.KeyEvent;

/**
 * Delivers a synthetic extended keycode into MC's in-process input (decision D16 / §5.6).
 *
 * <p>MC 26.3 runs on SDL3, so there is no GLFW key callback to invoke any more. The equivalent
 * entry point is {@code KeyboardHandler#keyPress(window, action, KeyEvent)}, which is exactly what
 * the SDL event pump calls; injecting there keeps the synthetic codes on the same path as real
 * ones. The synthetic code has no distinct logical/scancode pair, so both KeyEvent slots carry it.</p>
 *
 * <p>On desktop the daemon captures + translates an unlocked physical key and forwards a
 * {@code key_event}; the MOD (the only component inside MC's process) calls
 * {@link #deliver(int, String, int, int)} which synthesizes {@code VOXKBD_<kb>_<phys>} and injects it.</p>
 */
public final class GlfwDeliverer {

    private static volatile KeycodeTable table = KeycodeTable.defaults();

    private GlfwDeliverer() {}

    /** Set the keycode table matching the loaded config (call once during init). */
    public static void setTable(KeycodeTable table) {
        if (table != null) GlfwDeliverer.table = table;
    }

    public static KeycodeTable table() {
        return table;
    }

    /**
     * Inject a synthetic key event for virtual keyboard {@code kb}, physical token {@code phys}.
     *
     * @param action 0 = release, 1 = press, 2 = repeat.
     * @param mods   modifier bitmask captured at source.
     */
    public static void deliver(int kb, String phys, int action, int mods) {
        int code = table.keycodeFor(kb, phys);
        Minecraft mc = Minecraft.getInstance();
        if (mc == null || mc.getWindow() == null || mc.keyboardHandler == null) return;
        long window = mc.getWindow().handle();
        // The capture hook runs on its own thread, but MC's key handling (KeyMapping state, screens,
        // options) is main-thread-only. Marshal the delivery onto the render thread; the single
        // executor queue preserves press/release ordering.
        try {
            mc.execute(() -> mc.keyboardHandler.keyPress(window, action, new KeyEvent(code, code, mods)));
        } catch (Throwable ignored) {
            // Game shutting down / queue unavailable — drop the event rather than crash the hook.
        }
    }

    /** Resolve the synthetic internal name (used for diagnostics / logging only). */
    public static String internalName(int kb, String phys) {
        return VoxKbdNames.internalName(kb, phys);
    }
}
''')

    # --- keybinding fallbacks: SDL scancode space via InputConstants (GLFW constants are gone).
    patch_file(os.path.join(glfw, "KeybindingRegistry.java"),
               "import org.lwjgl.glfw.GLFW;\n", "", required=False)
    for glfw_name, mc_name in (("GLFW.GLFW_KEY_LEFT_BRACKET", "InputConstants.KEY_LBRACKET"),
                               ("GLFW.GLFW_KEY_RIGHT_BRACKET", "InputConstants.KEY_RBRACKET"),
                               ("GLFW.GLFW_KEY_GRAVE_ACCENT", "InputConstants.KEY_GRAVE")):
        patch_file(os.path.join(glfw, "KeybindingRegistry.java"), glfw_name, mc_name)

    # --- the Android GLFW callback backend has no SDL counterpart on 26.3; it is skipped at copy
    #     time (see gen_target) and never enters the mixin config (see below).

    # --- focus is polled, so the tick loop has to drive it.
    patch_file(os.path.join(src, "com/voxkbd/mod/ModRuntime.java"),
               "    public static void onClientTick(Minecraft client) {",
               "    public static void onClientTick(Minecraft client) {\n"
               "        FocusListener.poll();", required=False)

    # 26.3 moved the client input space from GLFW keycodes to SDL scancodes.
    if t.get("sdl"):
        write(os.path.join(src, "com/voxkbd/mod/screencompat/InputEra.java"),
'''package com.voxkbd.mod.screencompat;

/** MC 26.3 switched the client input space from GLFW keycodes to SDL scancodes. */
public final class InputEra {
    private InputEra() {}
    public static final boolean SDL_SCANCODES = true;
}
''')


def emit_inprocess_capture_modern(tdir, t):
    """Java 25 runtime: the FFM hook is always available, so the JNA fallback is dropped and
    the class no longer references it (no JNA on the modern-era classpath)."""
    write(os.path.join(tdir, "src/main/java/com/voxkbd/mod/capture/InProcessCapture.java"),
'''package com.voxkbd.mod.capture;

import com.voxkbd.core.config.ComboPrefixMask;
import com.voxkbd.core.input.InputState;
import com.voxkbd.daemon.input.CapturedKey;
import com.voxkbd.daemon.input.PhysicalInputCapture;
import com.voxkbd.daemon.input.WindowsKeyboardHook;
import com.voxkbd.daemon.translate.KeyTranslator;
import com.voxkbd.daemon.translate.TranslateResult;
import com.voxkbd.mod.ModRuntime;
import com.voxkbd.mod.glfw.GlfwDeliverer;
import com.voxkbd.mod.switch_.SwitchManager;

import java.util.Locale;
import java.util.function.Consumer;

/**
 * In-process desktop capture pipeline (single-JAR delivery) — 26.x era.
 *
 * <p>The Windows low-level keyboard hook uses the JDK's built-in Foreign Function &amp; Memory API
 * (final since Java 22), and MC 26.x mandates a Java 25 runtime, so no native binding library is
 * needed and none is bundled.</p>
 */
public final class InProcessCapture {

    private static volatile PhysicalInputCapture capture;

    private InProcessCapture() {}

    /** Start the in-process capture backend. Returns true if a capture backend is active. */
    public static boolean start(InputState state, SwitchManager switchManager) {
        KeyTranslator translator = new KeyTranslator();
        PhysicalInputCapture backend = chooseCapture(state);
        if (backend == null) {
            ModRuntime.LOGGER.warn("In-process capture unavailable on this OS; virtual keyboards are inactive.");
            return false;
        }
        Consumer<CapturedKey> sink = (CapturedKey k) -> {
            try {
                TranslateResult r = translator.translate(k, state);
                if (r.outcome() != TranslateResult.Outcome.SYNTHETIC) {
                    return;
                }
                int mods = k.mods();
                if (switchManager != null && switchManager.matchesPrefix(mods)
                        && ComboPrefixMask.isDigitToken(k.phys().token())) {
                    switchManager.onComboDigit(k.phys().token(), mods);
                    return;
                }
                GlfwDeliverer.deliver(r.keyboard(), r.phys().token(), k.action(), mods);
            } catch (Throwable t) {
                ModRuntime.LOGGER.error("voxkbd in-process capture sink error", t);
            }
        };
        try {
            backend.start(sink);
        } catch (Throwable t) {
            ModRuntime.LOGGER.error("Failed to start in-process capture", t);
            return false;
        }
        capture = backend;
        ModRuntime.LOGGER.info("Vox Kbd in-process capture started (backend={})",
                backend.getClass().getSimpleName());
        return true;
    }

    /** Stop the capture backend (best-effort). */
    public static void stop() {
        PhysicalInputCapture backend = capture;
        if (backend != null) {
            try {
                backend.stop();
            } catch (Throwable ignored) {
                // best-effort
            }
            capture = null;
        }
    }

    private static PhysicalInputCapture chooseCapture(InputState state) {
        String os = System.getProperty("os.name", "").toLowerCase(Locale.ROOT);
        if (os.contains("win")) {
            try {
                return new WindowsKeyboardHook(state);
            } catch (Throwable t) {
                ModRuntime.LOGGER.warn("FFM low-level keyboard hook unavailable", t);
            }
        }
        return null;
    }
}
''')


def emit_sdl_note(tdir, t):
    pass


# ------------------------------------------------------------------ platform entrypoints

def emit_fabric_platform(tdir, t):
    if t["era"] == "modern":
        # fabric-key-binding-api-v1 was superseded by fabric-key-mapping-api-v1 in the 26.x era.
        helper_import = "import net.fabricmc.fabric.api.client.keymapping.v1.KeyMappingHelper;"
        registrar = "KeyMappingHelper::registerKeyMapping"
    else:
        helper_import = "import net.fabricmc.fabric.api.client.keybinding.v1.KeyBindingHelper;"
        registrar = "KeyBindingHelper::registerKeyBinding"
    write(os.path.join(tdir, "src/main/java/com/voxkbd/mod/platform/fabric/VoxKbdFabric.java"),
f'''package com.voxkbd.mod.platform.fabric;

import com.voxkbd.mod.ModRuntime;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
{helper_import}
import net.fabricmc.loader.api.FabricLoader;

/** Fabric client entrypoint. */
public final class VoxKbdFabric implements ClientModInitializer {{

    @Override
    public void onInitializeClient() {{
        ModRuntime.keyMappingRegistrar = {registrar};
        ModRuntime.init(FabricLoader.getInstance().getGameDir(),
                FabricLoader.getInstance().getConfigDir());
        ClientTickEvents.END_CLIENT_TICK.register(ModRuntime::onClientTick);
    }}
}}
''')


def emit_fabric_mod_json(tdir, t):
    data = {
        "schemaVersion": 1,
        "id": "voxkbd",
        "version": "${version}",
        "name": "Vox Kbd",
        "description": "Virtual keyboards: an infinitely extensible key layer for modpacks without touching vanilla bindings.",
        "authors": ["VoxKbd"],
        "license": "MIT",
        "environment": "client",
        "entrypoints": {"client": ["com.voxkbd.mod.platform.fabric.VoxKbdFabric"]},
        "mixins": ["voxkbd.mixins.json"],
        "depends": {
            "fabricloader": ">=0.15.0",
            "minecraft": t["mc_range"],
            "fabric-api": "*",
        },
    }
    write(os.path.join(tdir, "src/main/resources/fabric.mod.json"),
          json.dumps(data, indent=2, ensure_ascii=False) + "\n")


def emit_forge_init(tdir, t, ns="forge", cls="VoxKbdForge"):
    """Emit the Forge bus-based client entrypoint into package ...platform.<ns>.

    NeoForge 1.20.1 (legacyforge) is Forge 1.20.1 under a different loader, so it reuses this
    wholesale with ns="neoforge"/cls="VoxKbdNeoForge" — its API lives under net.minecraftforge.
    """
    # ForgeGradle 7 landed with Forge 50 (1.20.6), but the event bus only became eventbus 7 with
    # Forge 58 (1.21.6+): @SubscribeEvent moves to eventbus.api.listener and ClientTickEvent gains
    # the nested .Post type. Verified against the reference project's per-line sources. NeoForge
    # 1.20.1 rides the old (Forge 47) shape.
    eventbus7 = (t["loader"] == "forge"
                 and t["group"] in ("1.21.6_1.21.8", "1.21.9_1.21.10", "1.21.11",
                                    "26.1_26.1.2", "26.2"))
    if eventbus7:
        sub_import = "import net.minecraftforge.eventbus.api.listener.SubscribeEvent;"
        tick_sig = "public static void onClientTick(TickEvent.ClientTickEvent.Post event) {"
    else:
        sub_import = "import net.minecraftforge.eventbus.api.SubscribeEvent;"
        tick_sig = ("public static void onClientTick(TickEvent.ClientTickEvent event) {\n"
                    "            if (event.phase != TickEvent.Phase.END) return;")
    tick_import = "import net.minecraftforge.event.TickEvent;"

    write(os.path.join(tdir, f"src/main/java/com/voxkbd/mod/platform/{ns}/{cls}.java"),
f'''package com.voxkbd.mod.platform.{ns};

import com.voxkbd.mod.ModRuntime;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.client.event.RegisterKeyMappingsEvent;
{sub_import}
{tick_import}
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.fml.loading.FMLEnvironment;

/** Forge-style client entrypoint. Registry events live on the mod bus, tick events on the game bus. */
@Mod("voxkbd")
public final class {cls} {{

    public {cls}() {{
        if (FMLEnvironment.dist != Dist.CLIENT) return;
        ForgeWiring.init();
    }}

    @Mod.EventBusSubscriber(modid = "voxkbd", value = Dist.CLIENT, bus = Mod.EventBusSubscriber.Bus.MOD)
    public static final class ModBus {{
        @SubscribeEvent
        public static void onRegisterKeyMappings(RegisterKeyMappingsEvent event) {{
            ModRuntime.keyMappingRegistrar = event::register;
            for (KeyMapping km : KeybindingCollector.all()) {{
                event.register(km);
            }}
        }}
    }}

    @Mod.EventBusSubscriber(modid = "voxkbd", value = Dist.CLIENT, bus = Mod.EventBusSubscriber.Bus.FORGE)
    public static final class GameBus {{
        @SubscribeEvent
        {tick_sig}
            if (ModRuntime.INPUT_STATE != null) {{
                ModRuntime.onClientTick(Minecraft.getInstance());
            }}
        }}
    }}
}}
''')
    write(os.path.join(tdir, f"src/main/java/com/voxkbd/mod/platform/{ns}/KeybindingCollector.java"),
f'''package com.voxkbd.mod.platform.{ns};

import net.minecraft.client.KeyMapping;

import java.util.ArrayList;
import java.util.List;

/** Buffers KeyMappings created by ModRuntime.init so the register event can push them. */
public final class KeybindingCollector {{
    private static final List<KeyMapping> COLLECTED = new ArrayList<>();
    private static boolean live = false;

    private KeybindingCollector() {{}}

    public static void arm() {{
        COLLECTED.clear();
        live = true;
    }}

    public static void collect(KeyMapping km) {{
        if (live) COLLECTED.add(km);
    }}

    public static List<KeyMapping> all() {{
        return COLLECTED;
    }}
}}
''')
    write(os.path.join(tdir, f"src/main/java/com/voxkbd/mod/platform/{ns}/ForgeWiring.java"),
f'''package com.voxkbd.mod.platform.{ns};

import com.voxkbd.mod.ModRuntime;

/** Static wiring so the @Mod class stays minimal. */
final class ForgeWiring {{
    private ForgeWiring() {{}}

    static void init() {{
        KeybindingCollector.arm();
        ModRuntime.keyMappingRegistrar = KeybindingCollector::collect;
        ModRuntime.init(net.minecraftforge.fml.loading.FMLPaths.GAMEDIR.get(),
                net.minecraftforge.fml.loading.FMLPaths.CONFIGDIR.get());
    }}
}}
''')


def emit_neoforge_init(tdir, t):
    legacy = t.get("legacyforge") or (t["era"] == "legacy" and t["release"] < 21)
    if legacy:
        write(os.path.join(tdir, "src/main/java/com/voxkbd/mod/platform/neoforge/VoxKbdNeoForge.java"),
'''package com.voxkbd.mod.platform.neoforge;

import com.voxkbd.mod.ModRuntime;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.fml.common.Mod;
import net.neoforged.fml.loading.FMLEnvironment;
import net.neoforged.fml.loading.FMLPaths;
import net.neoforged.neoforge.client.event.RegisterKeyMappingsEvent;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.event.TickEvent;

/** NeoForge client entrypoint (1.20.1 legacyforge era). */
@Mod("voxkbd")
public final class VoxKbdNeoForge {

    public VoxKbdNeoForge(IEventBus modBus) {
        if (FMLEnvironment.dist != Dist.CLIENT) return;
        modBus.addListener(this::onRegisterKeyMappings);
        NeoForge.EVENT_BUS.addListener(this::onClientTick);
        ModRuntime.keyMappingRegistrar = KeybindingBuffer::collect;
        ModRuntime.init(FMLPaths.GAMEDIR.get(), FMLPaths.CONFIGDIR.get());
    }

    private void onRegisterKeyMappings(RegisterKeyMappingsEvent event) {
        for (KeyMapping km : KeybindingBuffer.drain()) {
            event.register(km);
        }
    }

    private void onClientTick(TickEvent.ClientTickEvent event) {
        if (event.phase != TickEvent.Phase.END) return;
        if (ModRuntime.INPUT_STATE != null) {
            ModRuntime.onClientTick(Minecraft.getInstance());
        }
    }
}
''')
    else:
        write(os.path.join(tdir, "src/main/java/com/voxkbd/mod/platform/neoforge/VoxKbdNeoForge.java"),
'''package com.voxkbd.mod.platform.neoforge;

import com.voxkbd.mod.ModRuntime;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.fml.common.Mod;
import net.neoforged.fml.loading.FMLEnvironment;
import net.neoforged.fml.loading.FMLPaths;
import net.neoforged.neoforge.client.event.ClientTickEvent;
import net.neoforged.neoforge.client.event.RegisterKeyMappingsEvent;
import net.neoforged.neoforge.common.NeoForge;

/** NeoForge client entrypoint (modern era). */
@Mod("voxkbd")
public final class VoxKbdNeoForge {

    public VoxKbdNeoForge(IEventBus modBus) {
        if (FMLEnvironment.dist != Dist.CLIENT) return;
        modBus.addListener(this::onRegisterKeyMappings);
        NeoForge.EVENT_BUS.addListener(this::onClientTick);
        ModRuntime.keyMappingRegistrar = KeybindingBuffer::collect;
        ModRuntime.init(FMLPaths.GAMEDIR.get(), FMLPaths.CONFIGDIR.get());
    }

    private void onRegisterKeyMappings(RegisterKeyMappingsEvent event) {
        for (KeyMapping km : KeybindingBuffer.drain()) {
            event.register(km);
        }
    }

    private void onClientTick(ClientTickEvent.Post event) {
        if (ModRuntime.INPUT_STATE != null) {
            ModRuntime.onClientTick(Minecraft.getInstance());
        }
    }
}
''')
    write(os.path.join(tdir, "src/main/java/com/voxkbd/mod/platform/neoforge/KeybindingBuffer.java"),
'''package com.voxkbd.mod.platform.neoforge;

import net.minecraft.client.KeyMapping;

import java.util.ArrayList;
import java.util.List;

/** Buffers KeyMappings created by ModRuntime.init so the register event can push them. */
public final class KeybindingBuffer {
    private static final List<KeyMapping> COLLECTED = new ArrayList<>();

    private KeybindingBuffer() {}

    public static synchronized void collect(KeyMapping km) {
        COLLECTED.add(km);
    }

    public static synchronized List<KeyMapping> drain() {
        List<KeyMapping> out = new ArrayList<>(COLLECTED);
        COLLECTED.clear();
        return out;
    }
}
''')


# ------------------------------------------------------------------ loader metadata

FORGE_RANGE = {
    "1.20_1.20.1": "[47,48)", "1.20.2_1.20.4": "[48,50)", "1.20.6": "[50,51)",
    "1.21_1.21.5": "[51,56)", "1.21.6_1.21.8": "[58,59)", "1.21.9_1.21.10": "[60,61)",
    "1.21.11": "[61,)", "26.1_26.1.2": "[64,65)", "26.2": "[65,)",
}

NEO_RANGE = {
    "1.20.1": "[47.3.0,)", "1.20.4_1.20.6": "[20.4,)", "1.21_1.21.5": "[21.0,)",
    "1.21.6_1.21.8": "[21.6,)", "1.21.9_1.21.10": "[21.9,)", "1.21.11": "[21.11,)",
    "26.1_26.1.2": "[26.1,)", "26.2": "[26.2,)", "26.3": "[26.3,)",
}


def emit_forge_mods_toml(tdir, t):
    write(os.path.join(tdir, "src/main/resources/META-INF/mods.toml"),
f'''modLoader = "javafml"
loaderVersion = "[2,)"
license = "MIT"

[[mods]]
modId = "voxkbd"
version = "{MOD_VERSION}"
displayName = "Vox Kbd"
authors = "VoxKbd"
description = \'\'\'
Virtual keyboards: an infinitely extensible key layer for modpacks without touching vanilla bindings.
\'\'\'

[[dependencies.voxkbd]]
modId = "forge"
mandatory = true
versionRange = "{FORGE_RANGE[t['group']]}"
ordering = "NONE"
side = "CLIENT"

[[dependencies.voxkbd]]
modId = "minecraft"
mandatory = true
versionRange = "{t['mc_range']}"
ordering = "NONE"
side = "CLIENT"
''')


def neo_toml_name(t):
    """LegacyForge (NeoForge 1.20.1) is Forge-with-patches and still reads META-INF/mods.toml;
    every later NeoForge line reads META-INF/neoforge.mods.toml."""
    return "mods.toml" if t.get("legacyforge") else "neoforge.mods.toml"


def emit_neoforge_mods_toml(tdir, t):
    dep_attr = "mandatory = true" if t.get("legacyforge") else 'type = "required"'
    write(os.path.join(tdir, "src/main/resources/META-INF", neo_toml_name(t)),
f'''modLoader = "javafml"
loaderVersion = "[1,)"
license = "MIT"

[[mods]]
modId = "voxkbd"
version = "{MOD_VERSION}"
displayName = "Vox Kbd"
authors = "VoxKbd"
description = \'\'\'
Virtual keyboards: an infinitely extensible key layer for modpacks without touching vanilla bindings.
\'\'\'

[[dependencies.voxkbd]]
modId = "neoforge"
{dep_attr}
versionRange = "{NEO_RANGE[t['group']]}"
ordering = "NONE"
side = "CLIENT"

[[dependencies.voxkbd]]
modId = "minecraft"
{dep_attr}
versionRange = "{t['mc_range']}"
ordering = "NONE"
side = "CLIENT"
''')


# ------------------------------------------------------------------ gradle files

LICENSE_LINE = '\njar {\n    from("../../LICENSE") {}\n}\n'


def gradle_props(t):
    jh = t["jvm"].replace("\\", "/")
    lines = [
        # Kept modest: NeoFormRuntime forks its own tool JVM (and ForgeGradle its own daemon), and
        # the Windows pagefile is the binding constraint — a fat Gradle daemon starves them.
        "org.gradle.jvmargs=-Xmx2G",
        "org.gradle.daemon=false",
        f"org.gradle.java.home={jh}",
        f"org.gradle.java.installations.paths={jh}",
        "",
        "mod_id=voxkbd",
        f"mod_version={MOD_VERSION}-{t['group']}-{t['loader']}",
        "mod_group_id=com.voxkbd",
        "mod_license=MIT",
        "",
        f"minecraft_version={t['mc']}",
        f"minecraft_version_range={t['mc_range']}",
        f"java_version={t['release']}",
        f"release_bytecode={t['release']}",
    ]
    if t["loader"] == "fabric":
        lines += [f"fabric_loader_version={t['fabric_loader']}", f"fabric_version={t['fabric_api']}"]
    elif t["loader"] == "forge":
        lines += [f"forge_version={t['forge']}", f"forge_version_range={FORGE_RANGE[t['group']]}"]
    else:
        lines += [f"neo_version={t['neo']}", f"neo_version_range={NEO_RANGE[t['group']]}"]
    return "\n".join(lines) + "\n"


def wrapper_files(tdir, gradle_ver):
    os.makedirs(os.path.join(tdir, "gradle", "wrapper"), exist_ok=True)
    copy(os.path.join(ROOT, "gradle", "wrapper", "gradle-wrapper.jar"),
         os.path.join(tdir, "gradle", "wrapper", "gradle-wrapper.jar"))
    for script in ("gradlew", "gradlew.bat"):
        copy(os.path.join(ROOT, script), os.path.join(tdir, script))
    write(os.path.join(tdir, "gradle", "wrapper", "gradle-wrapper.properties"),
f"""distributionBase=GRADLE_USER_HOME
distributionPath=wrapper/dists
distributionUrl=https\\://services.gradle.org/distributions/gradle-{gradle_ver}-bin.zip
networkTimeout=120000
validateDistributionUrl=true
zipStoreBase=GRADLE_USER_HOME
zipStorePath=wrapper/dists
""")


FABRIC_SETTINGS = """pluginManagement {
    repositories {
        mavenLocal()
        maven {
            name = 'Fabric'
            url = 'https://maven.fabricmc.net/'
        }
        maven {
            name = 'Minecraft libraries'
            url = 'https://libraries.minecraft.net'
        }
        mavenCentral()
        gradlePluginPortal()
    }
}

rootProject.name = "voxkbd-fabric-%(group)s"
"""

NEOFORGE_SETTINGS = """pluginManagement {
    repositories {
        mavenLocal()
        maven {
            name = 'NeoForged'
            url = 'https://maven.neoforged.net/releases'
        }
        maven {
            name = 'Minecraft libraries'
            url = 'https://libraries.minecraft.net'
        }
        mavenCentral()
        gradlePluginPortal()
    }
}

rootProject.name = "voxkbd-neoforge-%(group)s"
"""


def fabric_build(tdir, t):
    write(os.path.join(tdir, "settings.gradle"), FABRIC_SETTINGS % {"group": t["group"]})
    if t["era"] == "modern":
        plugin = f"id 'net.fabricmc.fabric-loom' version '{LOOM_MODERN}'"
        dep_block = f"""dependencies {{
    minecraft "com.mojang:minecraft:{t['mc']}"
    implementation "net.fabricmc:fabric-loader:{t['fabric_loader']}"
    implementation "net.fabricmc.fabric-api:fabric-api:{t['fabric_api']}"
}}"""
        loom_block = "loom {\n}\n"
        java_block = f"""java {{
    withSourcesJar()
    toolchain {{
        languageVersion = JavaLanguageVersion.of({t['release']})
    }}
}}"""
    else:
        plugin = f"id 'net.fabricmc.fabric-loom-remap' version '{LOOM_LEGACY}'"
        dep_block = f"""dependencies {{
    minecraft "com.mojang:minecraft:{t['mc']}"
    mappings loom.officialMojangMappings()
    modImplementation "net.fabricmc:fabric-loader:{t['fabric_loader']}"
    modImplementation "net.fabricmc.fabric-api:fabric-api:{t['fabric_api']}"

    include("net.java.dev.jna:jna:{JNA_VERSION}")
}}"""
        loom_block = """loom {
    mixin {
        useLegacyMixinAp = true
        defaultRefmapName = "voxkbd.refmap.json"
    }
}
"""
        java_block = f"""java {{
    withSourcesJar()
    sourceCompatibility = JavaVersion.VERSION_{'17' if t['release'] <= 17 else '21'}
    targetCompatibility = JavaVersion.VERSION_{'17' if t['release'] <= 17 else '21'}
}}"""
    write(os.path.join(tdir, "build.gradle"),
f"""plugins {{
    {plugin}
}}

version = "{MOD_VERSION}-{t['group']}-{t['loader']}"
group = "com.voxkbd"
base {{ archivesName = "voxkbd" }}

repositories {{
    mavenCentral()
}}

{loom_block}
{dep_block}

tasks.withType(ProcessResources).configureEach {{
    var replaceProperties = [
        mod_id            : "voxkbd",
        mod_version       : "{MOD_VERSION}",
        minecraft_version : "{t['mc']}",
        minecraft_version_range: "{t['mc_range']}",
        version           : project.version,
    ]
    inputs.properties replaceProperties
    filesMatching(['fabric.mod.json']) {{
        expand replaceProperties + [project: project]
    }}
}}

tasks.withType(JavaCompile).configureEach {{
    it.options.encoding = "UTF-8"
    it.options.release = {t['release']}
}}

{java_block}
{LICENSE_LINE}""")


def forge_build(tdir, t):
    write(os.path.join(tdir, "settings.gradle"),
"""pluginManagement {
    repositories {
        mavenLocal()
        maven {
            name = 'MinecraftForge'
            url = 'https://maven.minecraftforge.net/'
        }
        maven {
            name = 'SpongePowered'
            url = 'https://repo.spongepowered.org/repository/maven-public/'
        }
        mavenCentral()
        gradlePluginPortal()
    }
}

rootProject.name = "voxkbd-forge-%(group)s"
""" % {"group": t["group"]})

    if t["fg"] == FG6_RANGE:
        buildscript = """buildscript {
    repositories {
        maven {
            name = 'SpongePowered'
            url = 'https://repo.spongepowered.org/repository/maven-public/'
        }
        maven {
            name = 'MinecraftForge'
            url = 'https://maven.minecraftforge.net/'
        }
        mavenCentral()
    }
    dependencies {
        classpath 'org.spongepowered:mixingradle:0.7.38'
    }
}

"""
        # `apply plugin` may not precede the plugins {} block, so it is emitted after it.
        apply_line = "\napply plugin: 'org.spongepowered.mixin'\n"
        mixin_block = """mixin {
    add sourceSets.main, 'voxkbd.refmap.json'
    config 'voxkbd.mixins.json'
}

"""
        dep_extra = "    annotationProcessor 'org.spongepowered:mixin:0.8.5:processor'\n"
        mavenizer = """repositories {
    maven {
        name = 'MinecraftForge'
        url = 'https://maven.minecraftforge.net/'
    }
    maven {
        name = 'SpongePowered'
        url = 'https://repo.spongepowered.org/repository/maven-public/'
    }
    mavenCentral()
}
"""
        dep_mc = '    minecraft "net.minecraftforge:forge:%s"\n' % t["forge"]
        reobf = ""
        if not t.get("reobf"):
            reobf = ("\n// Forge 1.20.2+ runs official mappings at runtime; keep the jar un-reobfed.\n"
                     "tasks.matching { it.name == 'reobfJar' }.configureEach { enabled = false }\n")
    else:
        buildscript = ""
        apply_line = ""
        mixin_block = ""
        dep_extra = ""
        mavenizer = """repositories {
    minecraft.mavenizer(it)
    maven fg.forgeMaven
    maven fg.minecraftLibsMaven
    mavenCentral()
}
"""
        dep_mc = ('    implementation minecraft.dependency("net.minecraftforge:forge:%s")\n'
                  % t["forge"])
        reobf = ""

    write(os.path.join(tdir, "build.gradle"),
f"""{buildscript}plugins {{
    id 'java'
    id 'idea'
    id 'net.minecraftforge.gradle' version '{t['fg']}'
}}
{apply_line}
version = "{MOD_VERSION}-{t['group']}-{t['loader']}"
group = "com.voxkbd"
base {{ archivesName = "voxkbd" }}

java.toolchain.languageVersion = JavaLanguageVersion.of({t['release']})

minecraft {{
    mappings channel: 'official', version: '{t['mc']}'
}}

{mixin_block}{mavenizer}
dependencies {{
{dep_mc}{dep_extra}}}

tasks.withType(ProcessResources).configureEach {{
    var replaceProperties = [
        minecraft_version      : '{t['mc']}',
        minecraft_version_range: '{t['mc_range']}',
        forge_version          : '{t['forge'].split('-', 1)[-1]}',
        forge_version_range    : '{FORGE_RANGE[t['group']]}',
        loader_version_range   : '[2,)',
        mod_id                 : 'voxkbd',
        mod_version            : '{MOD_VERSION}',
        mod_license            : 'MIT',
    ]
    inputs.properties replaceProperties
    filesMatching(['META-INF/mods.toml']) {{
        expand replaceProperties + [project: project]
    }}
}}

tasks.named('jar', Jar).configure {{
    manifest {{
        attributes([
                'Specification-Title'    : 'voxkbd',
                'Specification-Vendor'   : 'VoxKbd',
                'Specification-Version'  : '1',
                'Implementation-Title'   : 'Vox Kbd',
                'Implementation-Version' : project.version,
                'MixinConfigs'           : 'voxkbd.mixins.json'
        ])
    }}
    from("../../LICENSE") {{}}
}}

tasks.withType(JavaCompile).configureEach {{
    options.encoding = 'UTF-8'
    options.release = {t['release']}
}}
{reobf}""")


def neoforge_build(tdir, t):
    write(os.path.join(tdir, "settings.gradle"), NEOFORGE_SETTINGS % {"group": t["group"]})
    plugin = "net.neoforged.moddev.legacyforge" if t.get("legacyforge") else "net.neoforged.moddev"
    # The legacyforge plugin exposes its DSL through `legacyForge {}`, not `neoForge {}`.
    block = "legacyForge" if t.get("legacyforge") else "neoForge"
    jv = 17 if t["release"] <= 17 else (21 if t["release"] <= 21 else 25)
    if t.get("legacyforge"):
        enable_block = "    enable {\n        forgeVersion = project.neo_version\n        disableRecompilation = true\n    }\n"
        extra_deps = ("dependencies {\n"
                      "    annotationProcessor 'org.spongepowered:mixin:0.8.5:processor'\n"
                      "}\n\n"
                      "mixin {\n    add sourceSets.main, 'voxkbd.refmap.json'\n"
                      "    config 'voxkbd.mixins.json'\n}\n\n")
    else:
        enable_block = "    enable {\n        version = project.neo_version\n        disableRecompilation = true\n    }\n"
        extra_deps = ""
    write(os.path.join(tdir, "build.gradle"),
f"""plugins {{
    id '{plugin}' version '{MDG_VERSION}'
}}

version = "{MOD_VERSION}-{t['group']}-{t['loader']}"
group = "com.voxkbd"
base {{ archivesName = "voxkbd" }}

repositories {{
    maven {{
        name = 'Minecraft libraries'
        url = 'https://libraries.minecraft.net'
    }}
    mavenCentral()
}}

{block} {{
{enable_block}
    runs {{
        client {{ client() }}
        server {{ server() }}
    }}

    mods {{
        "voxkbd" {{
            sourceSet sourceSets.main
        }}
    }}
}}

{extra_deps}tasks.withType(ProcessResources).configureEach {{
    var replaceProperties = [
        mod_id                 : 'voxkbd',
        mod_version            : '{MOD_VERSION}',
        mod_license            : 'MIT',
        minecraft_version      : '{t['mc']}',
        minecraft_version_range: '{t['mc_range']}',
        neo_version_range      : '{NEO_RANGE[t['group']]}',
        loader_version_range   : '[1,)',
    ]
    inputs.properties replaceProperties
    filesMatching(['META-INF/{neo_toml_name(t)}']) {{
        expand replaceProperties + [project: project]
    }}
}}

tasks.withType(JavaCompile).configureEach {{
    it.options.encoding = "UTF-8"
    it.options.release = {t['release']}
}}

java {{
    withSourcesJar()
    sourceCompatibility = JavaVersion.VERSION_{jv}
    targetCompatibility = JavaVersion.VERSION_{jv}
}}

jar {{
    manifest.attributes(['MixinConfigs': 'voxkbd.mixins.json'])
    from("../../LICENSE") {{}}
}}
""")


# ------------------------------------------------------------------ driver

def gen_target(key):
    t = TARGETS[key]
    tdir = tdir_of(t)
    src = os.path.join(tdir, "src/main/java")
    res = os.path.join(tdir, "src/main/resources")

    # Generation is overwrite-only: every file the generator owns is rewritten, and what must not
    # exist is excluded at copy time rather than deleted afterwards. No directory is ever wiped, so
    # the tree stays a pure function of this file.

    # --- shared mod code
    shared_exclude = []
    if t["era"] == "modern":
        # Java 25 has the FFM API, so the JNA hook is dead weight (and would drag com.sun.jna in).
        shared_exclude.append(r"com/voxkbd/daemon/input/WindowsKeyboardHookJna\.java")
    copy_tree(os.path.join(SHARED, "java"), src, exclude=shared_exclude)
    copy_tree(os.path.join(SHARED, "mixin"), src)
    copy_tree(os.path.join(SHARED, "resources"), res)

    # --- core (group-agnostic)
    copy_tree(os.path.join(ROOT, "voxkbd-core", "src/main/java"), src)

    # --- daemon: legacy runtimes have no FFM, so swap in the JNA hook
    if t["era"] == "legacy":
        copy_tree(os.path.join(ROOT, "voxkbd-daemon", "src/main/java"), src,
                  exclude=[r"com/voxkbd/daemon/input/WindowsKeyboardHook\.java",
                           r"com/voxkbd/daemon/VoxKbdDaemon\.java"])
    else:
        copy_tree(os.path.join(ROOT, "voxkbd-daemon", "src/main/java"), src)
        emit_inprocess_capture_modern(tdir, t)

    # --- Android backend: Fabric only, and it has no SDL counterpart on 26.3
    if t["loader"] == "fabric" and not t.get("sdl"):
        copy_tree(os.path.join(ROOT, "voxkbd-mixin", "src/main/java"), src)
        copy_tree(os.path.join(ROOT, "voxkbd-fclbridge", "src/main/java"), src)

    # --- per-version shims
    emit_screen_compat(tdir, t)
    emit_vox_screen(tdir, t)
    emit_gfx_compat(tdir, t)
    emit_config_screen_scroll(tdir, t)
    emit_switch_notifier_bossbar(tdir, t)

    if tuple(int(x) for x in t["mc"].split(".")[:2]) >= (1, 21):
        patch_file(os.path.join(src, "com/voxkbd/mod/notification/SwitchNotifier.java"),
                   'new ResourceLocation("voxkbd", "switch");',
                   'ResourceLocation.fromNamespaceAndPath("voxkbd", "switch");', required=False)
    if t.get("dist_era") == "new":
        emit_new_era_patches(tdir, t)
    if t.get("identifier"):
        for rel in ("com/voxkbd/mod/notification/SwitchNotifier.java",
                    "com/voxkbd/mod/glfw/KeybindingRegistry.java"):
            p = os.path.join(src, rel)
            s = open(p, encoding="utf-8").read().replace("ResourceLocation", "Identifier")
            open(p, "w", encoding="utf-8", newline="\n").write(s)
    if t["era"] == "modern":
        emit_modern_patches(tdir, t)

    # --- mixin config: Fabric also applies the Android callback mixin
    mj = os.path.join(res, "voxkbd.mixins.json")
    cfg = json.load(open(mj, encoding="utf-8"))
    cfg["client"] = ["KeyMappingAccessor", "VoxKbdHudMixin"]
    if t["loader"] == "fabric" and not t.get("sdl"):
        # Android GLFW-callback backend: no SDL counterpart on 26.3.
        cfg["client"].append("VoxKbdKeyCallbackMixin")
    if t["loader"] == "forge" and t.get("reobf"):
        cfg["refmap"] = "voxkbd.refmap.json"
    write(mj, json.dumps(cfg, indent=2, ensure_ascii=False) + "\n")

    # --- loader platform + metadata + build files
    if t["loader"] == "fabric":
        emit_fabric_platform(tdir, t)
        emit_fabric_mod_json(tdir, t)
        fabric_build(tdir, t)
    elif t["loader"] == "forge":
        emit_forge_init(tdir, t)
        emit_forge_mods_toml(tdir, t)
        forge_build(tdir, t)
    else:
        if t.get("legacyforge"):
            # NeoForge 1.20.1 is Forge 1.20.1 under the NeoForge loader: net.minecraftforge API,
            # net.minecraftforge.fml bus classes, META-INF/mods.toml.
            emit_forge_init(tdir, t, ns="neoforge", cls="VoxKbdNeoForge")
        else:
            emit_neoforge_init(tdir, t)
            if t.get("dist_era") == "new":
                # FML 11 (1.21.9+): FMLEnvironment.dist field became getDist().
                patch_file(os.path.join(src, "com/voxkbd/mod/platform/neoforge/VoxKbdNeoForge.java"),
                           "FMLEnvironment.dist", "FMLEnvironment.getDist()", required=False)
        emit_neoforge_mods_toml(tdir, t)
        neoforge_build(tdir, t)

    wrapper_files(tdir, t["gradle"])
    write(os.path.join(tdir, "gradle.properties"), gradle_props(t))


def main():
    keys = sys.argv[1:] or list(TARGETS)
    for key in keys:
        if key not in TARGETS:
            print(f"unknown target {key}")
            sys.exit(1)
        gen_target(key)
        print(f"generated {key}")


if __name__ == "__main__":
    main()
