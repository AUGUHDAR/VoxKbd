package com.voxkbd.mod.platform.fabric;

import com.voxkbd.mod.ModRuntime;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.keymapping.v1.KeyMappingHelper;
import net.fabricmc.loader.api.FabricLoader;

/** Fabric client entrypoint. */
public final class VoxKbdFabric implements ClientModInitializer {

    @Override
    public void onInitializeClient() {
        ModRuntime.keyMappingRegistrar = KeyMappingHelper::registerKeyMapping;
        ModRuntime.init(FabricLoader.getInstance().getGameDir(),
                FabricLoader.getInstance().getConfigDir());
        ClientTickEvents.END_CLIENT_TICK.register(ModRuntime::onClientTick);
    }
}
