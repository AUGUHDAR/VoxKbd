package com.voxkbd.mod.platform.neoforge;

import net.minecraft.client.KeyMapping;

import java.util.ArrayList;
import java.util.List;

/** Buffers KeyMappings created by ModRuntime.init so the register event can push them. */
public final class KeybindingBuffer {
    private static final List<KeyMapping> COLLECTED = new ArrayList<>();

    private KeybindingBuffer() {}

    public static synchronized void collect(KeyMapping km) {
        COLLECTED.add(km);
    }

    public static synchronized List<KeyMapping> drain() {
        List<KeyMapping> out = new ArrayList<>(COLLECTED);
        COLLECTED.clear();
        return out;
    }
}
