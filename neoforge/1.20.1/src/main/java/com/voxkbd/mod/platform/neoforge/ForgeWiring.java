package com.voxkbd.mod.platform.neoforge;

import com.voxkbd.mod.ModRuntime;

/** Static wiring so the @Mod class stays minimal. */
final class ForgeWiring {
    private ForgeWiring() {}

    static void init() {
        KeybindingCollector.arm();
        ModRuntime.keyMappingRegistrar = KeybindingCollector::collect;
        ModRuntime.init(net.minecraftforge.fml.loading.FMLPaths.GAMEDIR.get(),
                net.minecraftforge.fml.loading.FMLPaths.CONFIGDIR.get());
    }
}
