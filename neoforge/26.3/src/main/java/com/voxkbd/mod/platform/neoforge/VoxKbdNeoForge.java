package com.voxkbd.mod.platform.neoforge;

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
        if (FMLEnvironment.getDist() != Dist.CLIENT) return;
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
