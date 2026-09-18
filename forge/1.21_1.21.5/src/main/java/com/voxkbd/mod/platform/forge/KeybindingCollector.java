package com.voxkbd.mod.platform.forge;

import net.minecraft.client.KeyMapping;

import java.util.ArrayList;
import java.util.List;

/** Buffers KeyMappings created by ModRuntime.init so the Forge register event can push them. */
public final class KeybindingCollector {
    private static final List<KeyMapping> COLLECTED = new ArrayList<>();
    private static boolean live = false;

    private KeybindingCollector() {}

    public static void arm() {
        COLLECTED.clear();
        live = true;
    }

    public static void collect(KeyMapping km) {
        if (live) COLLECTED.add(km);
    }

    public static List<KeyMapping> all() {
        return COLLECTED;
    }
}
