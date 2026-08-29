package com.voxkbd.mod;

import com.voxkbd.mod.platform.JavaDetector;
import net.fabricmc.api.ClientModInitializer;

/**
 * Secondary client entrypoint used on Android (FCL / ZL2). On Android the mod runs inside the
 * launcher JVM and capture/translate/delivery of synthetic keys happens in-process via the
 * {@code voxkbd-mixin} backend (which wraps MC's {@code glfwSetKeyCallback}); this entrypoint flips
 * {@link VoxKbdMod#INPUT_STATE#setInProcessTranslation(boolean)} to {@code true} so the daemon split
 * in the desktop delivery model (§4.0 / D6) is disabled and the mixin bridge is authoritative.
 *
 * <p>On desktop this is a no-op (the flag stays {@code false}, set by {@link VoxKbdMod}). Included as
 * a second client entrypoint so the Android build path is wired without branching the main mod.</p>
 */
public final class ClientEntrypoint implements ClientModInitializer {

    @Override
    public void onInitializeClient() {
        if (JavaDetector.isAndroid() && VoxKbdMod.INPUT_STATE != null) {
            VoxKbdMod.INPUT_STATE.setInProcessTranslation(true);
        }
    }
}
