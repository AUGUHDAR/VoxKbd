package com.voxkbd.daemon.inject;

import com.voxkbd.core.key.PhysicalKey;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * Test double for {@link KeyInjector} that records calls instead of touching hardware. Used by the
 * headless smoke test to assert the pass-through path.
 */
public final class RecordingInjector implements KeyInjector {
    public record Call(PhysicalKey phys, int action, int mods) {}

    private final List<Call> calls = new CopyOnWriteArrayList<>();

    @Override
    public void injectPhysical(PhysicalKey phys, int action, int mods) {
        calls.add(new Call(phys, action, mods));
    }

    public List<Call> calls() { return List.copyOf(calls); }

    public void clear() { calls.clear(); }
}
