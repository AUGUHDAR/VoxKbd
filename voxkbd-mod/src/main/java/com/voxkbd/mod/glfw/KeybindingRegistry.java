package com.voxkbd.mod.glfw;

import com.mojang.blaze3d.platform.InputConstants;
import com.voxkbd.core.Constants;
import com.voxkbd.core.config.Config;
import com.voxkbd.core.key.KeycodeTable;
import com.voxkbd.core.key.PhysicalKeyRegistry;
import net.fabricmc.fabric.api.client.keymapping.v1.KeyMappingHelper;
import net.minecraft.client.KeyMapping;
import net.minecraft.resources.Identifier;
import org.lwjgl.glfw.GLFW;

/**
 * Registers Vox Kbd entries in vanilla Controls.
 *
 * <p>Exactly three entries, forever: the switch bindings (§3.3) — previous keyboard / next
 * keyboard / open master UI. These are FUNCTIONS that need a key bound to them, which is what
 * the vanilla Controls list is for.</p>
 *
 * <p>Synthetic keys ({@code VOXKBD_<kb>_<phys>}, D16) are NEVER registered as KeyMappings and
 * never appear in vanilla Controls: they are the BIND TARGET (a key), not a function — putting
 * a key into a "bind a function" list is meaningless. A function can be REBOUND to a synthetic
 * key via its fixed GLFW keycode (D16 allocation table; e.g. modpack options.txt), which is the
 * entire point of "copying the keyboard" for extra bindable keys.</p>
 */
public final class KeybindingRegistry {

    /** Vox Kbd keybinding category (label via {@code key.category.voxkbd.category}). */
    public static final KeyMapping.Category CATEGORY =
            KeyMapping.Category.register(Identifier.fromNamespaceAndPath(Constants.MOD_ID, "category"));

    public static KeyMapping previousKeybinding;
    public static KeyMapping nextKeyBinding;
    public static KeyMapping openConfigKeybinding;

    /** Switch keybindings are registered exactly once per game session. */
    private static boolean switchKeysRegistered = false;

    private KeybindingRegistry() {}

    /** Register ONLY the three switch bindings (§3.3) — see the class comment for why nothing else. */
    public static void registerAll(Config config, KeycodeTable table) {
        if (!switchKeysRegistered) {
            switchKeysRegistered = true;
            previousKeybinding = registerSwitch("voxkbd.key.previous", config.switchCfg.previousKey,
                    GLFW.GLFW_KEY_LEFT_BRACKET);
            nextKeyBinding = registerSwitch("voxkbd.key.next", config.switchCfg.nextKey,
                    GLFW.GLFW_KEY_RIGHT_BRACKET);
            openConfigKeybinding = registerSwitch("voxkbd.key.openConfig", config.switchCfg.openConfigKey,
                    GLFW.GLFW_KEY_GRAVE_ACCENT);
        }
    }

    /** Resolve a config entry to a keycode: physical-token → raw integer → fallback default. */
    private static KeyMapping registerSwitch(String translationKey, String configValue, int fallbackCode) {
        int code = fallbackCode;
        var pk = PhysicalKeyRegistry.byToken(configValue);
        if (pk != null) {
            code = pk.glfwCode();
        } else if (configValue != null && !configValue.isBlank()) {
            try {
                int parsed = Integer.parseInt(configValue.trim());
                if (parsed >= 0 && parsed <= 512) {
                    code = parsed;
                } else {
                    com.voxkbd.mod.VoxKbdMod.LOGGER.warn(
                            "Invalid switch key code {} for {} (out of range); using fallback {}",
                            parsed, translationKey, fallbackCode);
                }
            } catch (NumberFormatException nfe) {
                com.voxkbd.mod.VoxKbdMod.LOGGER.warn(
                        "Unrecognised switch key value '{}' for {}; using fallback {}",
                        configValue, translationKey, fallbackCode);
            }
        }
        KeyMapping kb = new KeyMapping(translationKey, InputConstants.Type.KEYSYM, code, CATEGORY);
        KeyMappingHelper.registerKeyMapping(kb);
        return kb;
    }
}
