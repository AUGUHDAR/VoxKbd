package com.voxkbd.mod.platform.forge;

import com.voxkbd.mod.ModRuntime;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.client.event.RegisterKeyMappingsEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.fml.loading.FMLEnvironment;

/** Forge client entrypoint. Registry events live on the mod bus, tick events on the game bus. */
@Mod("voxkbd")
public final class VoxKbdForge {

    public VoxKbdForge() {
        if (FMLEnvironment.dist != Dist.CLIENT) return;
        ForgeWiring.init();
    }

    @Mod.EventBusSubscriber(modid = "voxkbd", value = Dist.CLIENT, bus = Mod.EventBusSubscriber.Bus.MOD)
    public static final class ModBus {
        @SubscribeEvent
        public static void onRegisterKeyMappings(RegisterKeyMappingsEvent event) {
            ModRuntime.keyMappingRegistrar = event::register;
            for (KeyMapping km : KeybindingCollector.all()) {
                event.register(km);
            }
        }
    }

    @Mod.EventBusSubscriber(modid = "voxkbd", value = Dist.CLIENT, bus = Mod.EventBusSubscriber.Bus.FORGE)
    public static final class GameBus {
        @SubscribeEvent
        public static void onClientTick(TickEvent.ClientTickEvent event) {
            if (event.phase != TickEvent.Phase.END) return;
            if (ModRuntime.INPUT_STATE != null) {
                ModRuntime.onClientTick(Minecraft.getInstance());
            }
        }
    }
}
