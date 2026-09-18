package com.voxkbd.mod.glfw;

import com.mojang.blaze3d.platform.InputConstants;
import com.voxkbd.core.Constants;
import com.voxkbd.core.config.Config;
import com.voxkbd.core.key.KeycodeTable;
import com.voxkbd.core.key.PhysicalKeyRegistry;
import net.minecraft.client.KeyMapping;
import org.lwjgl.glfw.GLFW;

/**
 * Registers Vox Kbd entries in vanilla Controls (1.20.x–1.21.x port).
 *
 * <p>Exactly three entries, forever: the switch bindings (§3.3). Synthetic keys
 * ({@code VOXKBD_<kb>_<phys>}) are NEVER registered as KeyMappings. On these versions a
 * KeyMapping category is a plain i18n string (KeyMapping.Category came with the 26.x era).</p>
 */
public final class KeybindingRegistry {

    /** Vox Kbd keybinding category (label via {@code key.category.voxkbd.category}). */
    public static final KeyMapping.Category CATEGORY = KeyMapping.Category.register(net.minecraft.resources.Identifier.fromNamespaceAndPath("voxkbd", "category"));

    public static KeyMapping previousKeybinding;
    public static KeyMapping nextKeyBinding;
    public static KeyMapping openConfigKeybinding;

    /** Switch keybindings are registered exactly once per game session. */
    private static boolean switchKeysRegistered = false;

    private KeybindingRegistry() {}

    /** Register ONLY the three switch bindings (§3.3). */
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
                    com.voxkbd.mod.ModRuntime.LOGGER.warn(
                            "Invalid switch key code {} for {} (out of range); using fallback {}",
                            parsed, translationKey, fallbackCode);
                }
            } catch (NumberFormatException nfe) {
                com.voxkbd.mod.ModRuntime.LOGGER.warn(
                        "Unrecognised switch key value '{}' for {}; using fallback {}",
                        configValue, translationKey, fallbackCode);
            }
        }
        KeyMapping kb = new KeyMapping(translationKey, InputConstants.Type.KEYSYM, code, CATEGORY);
        com.voxkbd.mod.ModRuntime.keyMappingRegistrar.accept(kb);
        return kb;
    }
}
