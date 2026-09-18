package com.voxkbd.mod;

import com.voxkbd.core.Constants;
import com.voxkbd.core.config.Config;
import com.voxkbd.core.input.InputState;
import com.voxkbd.core.key.KeycodeTable;
import com.voxkbd.mod.capture.InProcessCapture;
import com.voxkbd.mod.config.ConfigManager;
import com.voxkbd.mod.glfw.FocusListener;
import com.voxkbd.mod.glfw.GlfwDeliverer;
import com.voxkbd.mod.glfw.KeybindingRegistry;
import com.voxkbd.mod.lock.LockManager;
import com.voxkbd.mod.notification.SwitchNotifier;
import com.voxkbd.mod.platform.JavaDetector;
import com.voxkbd.mod.screencompat.ScreenCompat;
import com.voxkbd.mod.switch_.SwitchManager;
import com.voxkbd.mod.ui.ConfigScreen;
import com.voxkbd.mod.ui.MasterScreen;
import net.minecraft.client.Minecraft;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.file.Path;
import java.util.function.Consumer;

/**
 * Loader-agnostic client runtime (ported from the 26.2 Fabric-only VoxKbdMod). Wires together
 * config, keybindings, focus gating, the in-process desktop capture pipeline, the lock + switch
 * managers, and the master/config UI. Platform entrypoints (Fabric / Forge / NeoForge) call
 * {@link #init(Path, Path)} and forward their loader events to {@link #onClientTick(Minecraft)}.
 */
public final class ModRuntime {

    public static final Logger LOGGER = LoggerFactory.getLogger(Constants.MOD_NAME);

    /** Shared runtime input state (also referenced by the Android mixin bridge). */
    public static InputState INPUT_STATE;

    /** Shared live config instance (UI screens read the current keyboard names from it). */
    public static Config CONFIG;

    private static ConfigManager configManager;
    private static InputState inputState;
    private static SwitchManager switchManager;
    private static LockManager lockManager;
    private static SwitchNotifier notifier;
    private static MasterScreen masterScreen;
    private static boolean android;

    private static final Runnable statePusher = () -> { };

    /** Platform key-mapping registration hook. Set BEFORE {@link #init} runs. */
    public static java.util.function.Consumer<net.minecraft.client.KeyMapping> keyMappingRegistrar = km -> { };

    private static int tickCount;
    private static boolean autoOpenDone = false;

    private static final boolean AUTO_OPEN_MASTER = Boolean.parseBoolean(
            System.getProperty("vox.open", "false"));

    private ModRuntime() {}

    public static void init(Path gameDir, Path configDir) {
        android = JavaDetector.isAndroid();

        configManager = new ConfigManager(configDir);
        Config config;
        try {
            config = configManager.load();
        } catch (Exception e) {
            LOGGER.error("Failed to load config, using defaults", e);
            config = new Config();
            config.normalize();
        }

        inputState = new InputState();
        // D20: the game starts on the vanilla baseline — every key behaves exactly like vanilla
        // until the player actively switches into a virtual keyboard.
        inputState.setActiveKeyboard(Constants.VANILLA_KB_INDEX);
        inputState.setFocus(true);
        inputState.setInProcessTranslation(android);
        INPUT_STATE = inputState;
        CONFIG = config;
        // 实时持久化: normalize + write the config to disk immediately at startup.
        try {
            configManager.save();
        } catch (Exception e) {
            LOGGER.warn("VoxKbd: could not write config at startup", e);
        }
        if (android) {
            bindAndroidBackend(inputState);
        }

        KeycodeTable table = new KeycodeTable(config.keycode.base, config.keycode.stride);
        GlfwDeliverer.setTable(table);

        // Three switch bindings always; synthetic keys only for keyboards the player already added.
        KeybindingRegistry.registerAll(config, table);
        FocusListener.register(inputState, statePusher);

        lockManager = new LockManager(config, inputState, statePusher, configManager);
        notifier = new SwitchNotifier(config, inputState);
        switchManager = new SwitchManager(config, inputState, statePusher, notifier::notify);

        // master UI (reused instance; init() re-runs each open)
        final Config finalConfig = config;
        final MasterScreen[] holder = new MasterScreen[1];
        masterScreen = new MasterScreen(finalConfig, lockManager, inputState,
                () -> {
                    Minecraft client = Minecraft.getInstance();
                    if (client != null) {
                        client.setScreen(new ConfigScreen(holder[0], finalConfig, configManager,
                                inputState, statePusher, ModRuntime::onConfigChanged));
                    }
                }, statePusher);
        holder[0] = masterScreen;

        // hot-reload: refresh prefix + re-push state when the file changes externally
        configManager.addReloadListener(reloaded -> {
            switchManager.refreshPrefix();
            lockManager.refreshVanillaLocks();
            statePusher.run();
        });
        try {
            configManager.startWatcher();
        } catch (Exception e) {
            LOGGER.warn("Could not start config watcher", e);
        }

        if (!android) {
            setupInProcessCapture(switchManager);
        } else {
            LOGGER.info("Android runtime detected: in-process GLFW translation (mixin backend).");
        }

        LOGGER.info("Vox Kbd initialized (android={})", android);
    }

    private static void setupInProcessCapture(SwitchManager mgr) {
        boolean ok = InProcessCapture.start(inputState, mgr);
        if (ok) {
            Runtime.getRuntime().addShutdownHook(new Thread(InProcessCapture::stop, "voxkbd-capture-shutdown"));
        } else {
            LOGGER.warn("In-process capture did not start; virtual keyboards are inactive on this desktop.");
        }
    }

    /** Reflection keeps the mod compiled without a build-time dependency on the mixin module. */
    private static void bindAndroidBackend(InputState state) {
        try {
            Class.forName("com.voxkbd.bridge.VoxKbdMixinApi")
                    .getMethod("bindInputState", InputState.class)
                    .invoke(null, state);
        } catch (Throwable t) {
            LOGGER.warn("Android GLFW mixin backend unavailable; in-process translation stays off", t);
            state.setInProcessTranslation(false);
        }
    }

    private static void onConfigChanged() {
        switchManager.refreshPrefix();
        statePusher.run();
    }

    /** Loader client-tick hook (Fabric ClientTickEvents / Forge TickEvent / NeoForge ClientTickEvent). */
    public static void onClientTick(Minecraft client) {
        // Screen gating: while any MC Screen (chat / inventory / menus) is open, capture backends
        // pass every key through natively so GUI typing and menu keys are never hijacked (§3.2).
        inputState.setPaused(client.screen != null);
        // Bind capture (v0.5): while the vanilla key-binds screen is open, lift ONLY the
        // capture backends' GUI pass-through — the lock rules themselves are untouched.
        inputState.setBindCapture(client.screen != null
                && ScreenCompat.keyBindsScreenClass().isInstance(client.screen));
        // Vanilla-bound keys may become known (or change) after init — refresh the dynamic
        // default-lock set periodically (§3.4.1, never hardcoded).
        if (++tickCount % 20 == 0) {
            lockManager.refreshVanillaLocks();
            // VOXKBD 永久跟随: any function bound to a synthetic key keeps its physical key
            // unlocked — regardless of what else changed in the config. Idempotent + persisted.
            var table = GlfwDeliverer.table();
            for (net.minecraft.client.KeyMapping km : client.options.keyMappings) {
                var decoded = table.decode(LockManager.readLiveKey(km).getValue());
                if (decoded != null) {
                    if (lockManager.unlockForBinding(decoded.key().token())) {
                        LOGGER.info("Function bound to {} — physical key {} unlocked (permanent follow)",
                                com.voxkbd.core.naming.VoxKbdNames.internalName(
                                        decoded.keyboard(), decoded.key().token()),
                                decoded.key().token());
                    }
                }
            }
        }
        if (KeybindingRegistry.previousKeybinding != null
                && KeybindingRegistry.previousKeybinding.consumeClick()) {
            switchManager.previous();
        }
        if (KeybindingRegistry.nextKeyBinding != null
                && KeybindingRegistry.nextKeyBinding.consumeClick()) {
            switchManager.next();
        }
        if (KeybindingRegistry.openConfigKeybinding != null
                && KeybindingRegistry.openConfigKeybinding.consumeClick()) {
            if (client.screen == null) {
                client.setScreen(masterScreen);
            }
        }
        // --voxOpen: fire once when the world is loaded and no screen is open.
        if (AUTO_OPEN_MASTER && client.level != null && client.screen == null
                && !autoOpenDone) {
            autoOpenDone = true;
            client.setScreen(masterScreen);
        }
        switchManager.tick();
        notifier.tick();
    }

    /** HUD "current keyboard always shown" tab (loader-agnostic draw hook, GuiGraphicsExtractor based). */
    public static void onHudRender(net.minecraft.client.gui.GuiGraphicsExtractor gui, float delta) {
        com.voxkbd.mod.notification.CurrentKbTab.draw(gui, CONFIG, INPUT_STATE);
    }
}
