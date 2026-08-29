package com.voxkbd.mod;

import com.voxkbd.core.Constants;
import com.voxkbd.core.config.Config;
import com.voxkbd.core.input.InputState;
import com.voxkbd.core.key.KeycodeTable;
import com.voxkbd.mod.config.ConfigManager;
import com.voxkbd.mod.capture.InProcessCapture;
import com.voxkbd.mod.glfw.GlfwDeliverer;
import com.voxkbd.mod.glfw.FocusListener;
import com.voxkbd.mod.glfw.KeybindingRegistry;
import com.voxkbd.mod.lock.LockManager;
import com.voxkbd.mod.notification.SwitchNotifier;
import com.voxkbd.mod.platform.JavaDetector;
import com.voxkbd.mod.switch_.SwitchManager;
import com.voxkbd.mod.ui.ConfigScreen;
import com.voxkbd.mod.ui.MasterScreen;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.client.Minecraft;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.file.Path;

/**
 * Vox Kbd Fabric client mod entrypoint (decision D1 / §5.1). Wires together config, keybindings,
 * focus gating, the desktop daemon supervisor, the lock + switch managers, and the master/config UI.
 *
 * <p>On Android (FCL / ZL2) there is no daemon and {@link InputState#setInProcessTranslation(boolean)}
 * is set true by {@code ClientEntrypoint} (the in-process mixin backend handles capture/translate).
 * On desktop the mod keeps it false and the daemon does capture/translate; the mod only delivers the
 * synthetic extended keycodes into GLFW in-process (§4.0 / D6).</p>
 */
public final class VoxKbdMod implements ClientModInitializer {

    public static final Logger LOGGER = LoggerFactory.getLogger(Constants.MOD_NAME);

    /** Debug log directory (logs/ next to gameDir). */
    public static final java.nio.file.Path LOG_DIR = java.nio.file.Paths.get("logs");

    /** Shared runtime input state (also referenced by the Android ClientEntrypoint / mixin bridge). */
    public static InputState INPUT_STATE;

    /** Shared live config instance (UI screens read the current keyboard names from it). */
    public static Config CONFIG;

    private ConfigManager configManager;
    private InputState inputState;
    private SwitchManager switchManager;
    private LockManager lockManager;
    private SwitchNotifier notifier;
    private MasterScreen masterScreen;

    /**
     * No-op state pusher. In the single-JAR in-process model the capture backend reads the shared
     * {@link InputState} directly, so there is nothing to push to an external process (the old desktop
     * delivery split is gone, §4.0 / decision D12). Kept as the lifecycle hook for focus/lock/switch
     * listeners so existing call sites are unchanged.
     */
    private final Runnable statePusher = () -> { };

    @Override
    public void onInitializeClient() {
        boolean android = JavaDetector.isAndroid();

        FabricLoader loader = FabricLoader.getInstance();
        Path gameDir = loader.getGameDir();
        Path configDir = loader.getConfigDir();

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
        inputState.setInProcessTranslation(android); // Android: mixin does in-process translation
        INPUT_STATE = inputState;
        CONFIG = config;
        // 实时持久化: normalize + write the config to disk immediately at startup, so the file on
        // disk always matches what the mod loaded (no "抽奖" — the file is never stale).
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
        // Synthetic keys are bind TARGETS, never Controls entries — no KeyMapping registration
        // anywhere (see KeybindingRegistry); the vanilla list holds only the three switch keys.
        switchManager = new SwitchManager(config, inputState, statePusher, notifier::notify);

        // "Current keyboard always shown" tap bar (Config UI tri-state; action-bar mode in SwitchNotifier)
        net.fabricmc.fabric.api.client.rendering.v1.hud.HudElementRegistry.addLast(
                net.minecraft.resources.Identifier.fromNamespaceAndPath(Constants.MOD_ID, "always_show"),
                new com.voxkbd.mod.notification.AlwaysShowHud(config, inputState));

        // master UI (reused instance; init() re-runs each open)
        final Config finalConfig = config;
        final MasterScreen[] holder = new MasterScreen[1];
        masterScreen = new MasterScreen(finalConfig, lockManager, inputState,
                () -> {
                    Minecraft client = Minecraft.getInstance();
                    if (client != null) {
                        client.gui.setScreen(new ConfigScreen(holder[0], finalConfig, configManager,
                                inputState, statePusher, this::onConfigChanged));
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

        registerTickLoop();
        LOGGER.info("Vox Kbd initialized (android={})", android);
    }

    private void setupInProcessCapture(SwitchManager switchManager) {
        boolean ok = InProcessCapture.start(inputState, switchManager);
        if (ok) {
            Runtime.getRuntime().addShutdownHook(new Thread(InProcessCapture::stop, "voxkbd-capture-shutdown"));
        } else {
            LOGGER.warn("In-process capture did not start; virtual keyboards are inactive on this desktop.");
        }
    }

    /**
     * Hand the shared {@link InputState} to the Android GLFW-injection mixin. Reflection keeps the
     * mod compiled without a build-time dependency on the mixin module; when the mixin is absent
     * (or failed to apply) this logs and continues — the game stays fully playable.
     */
    private void bindAndroidBackend(InputState state) {
        try {
            Class.forName("com.voxkbd.bridge.VoxKbdMixinApi")
                    .getMethod("bindInputState", InputState.class)
                    .invoke(null, state);
        } catch (Throwable t) {
            LOGGER.warn("Android GLFW mixin backend unavailable; in-process translation stays off", t);
            state.setInProcessTranslation(false);
        }
    }

    private void onConfigChanged() {
        switchManager.refreshPrefix();
        statePusher.run();
    }

    private int tickCount;

    /**
     * When the JVM is started with {@code -Dvox.open=true}, the mod auto-shows the Master UI
     * as soon as the player is in-game. This avoids automation having to press the
     * openConfigKey (default: `) which fails while the main menu / pause screen is open.
     * Read once at mod-init so a single flag drives headless screenshot / QA.
     */
    private static final boolean AUTO_OPEN_MASTER = Boolean.parseBoolean(
            System.getProperty("vox.open", "false"));

    private void registerTickLoop() {
        ClientTickEvents.END_CLIENT_TICK.register(client -> {
            // Screen gating: while any MC Screen (chat / inventory / menus) is open, capture backends
            // pass every key through natively so GUI typing and menu keys are never hijacked (§3.2).
            inputState.setPaused(client.gui != null && client.gui.screen() != null);
            // Bind capture (v0.5): while the vanilla key-binds screen is open, lift ONLY the
            // capture backends' GUI pass-through — the lock rules themselves are untouched
            // (眼见为实: red key = registers vanilla, unlocked key = registers synthetic).
            inputState.setBindCapture(client.gui != null
                    && client.gui.screen() instanceof net.minecraft.client.gui.screens.options.controls.KeyBindsScreen);
            // Vanilla-bound keys may become known (or change) after init — refresh the dynamic
            // default-lock set periodically (§3.4.1, never hardcoded).
            if (++tickCount % 20 == 0) {
                lockManager.refreshVanillaLocks();
                // VOXKBD 永久跟随: any function bound to a synthetic key keeps its physical key
                // unlocked — regardless of what else changed in the config. Idempotent + persisted.
                var table = com.voxkbd.mod.glfw.GlfwDeliverer.table();
                for (net.minecraft.client.KeyMapping km : client.options.keyMappings) {
                    var decoded = table.decode(LockManager.readLiveKey(km).getValue());
                    if (decoded != null) {
                        if (lockManager.unlockForBinding(decoded.key().token())) {
                            VoxKbdMod.LOGGER.info("Function bound to {} — physical key {} unlocked (permanent follow)",
                                    com.voxkbd.core.naming.VoxKbdNames.internalName(
                                            decoded.keyboard(), decoded.key().token()),
                                    decoded.key().token());
                        }
                    }
                }
            }
            if (KeybindingRegistry.previousKeybinding.consumeClick()) {
                switchManager.previous();
            }
            if (KeybindingRegistry.nextKeyBinding.consumeClick()) {
                switchManager.next();
            }
            if (KeybindingRegistry.openConfigKeybinding.consumeClick()) {
                if (client.gui.screen() == null) {
                    client.gui.setScreen(masterScreen);
                }
            }
            // --voxOpen: fire once when the world is loaded and no screen is open.
            if (AUTO_OPEN_MASTER && client.level != null && client.gui.screen() == null
                    && !autoOpenDone) {
                autoOpenDone = true;
                client.gui.setScreen(masterScreen);
            }
            switchManager.tick();
            notifier.tick();
        });
    }

    private boolean autoOpenDone = false;
}
