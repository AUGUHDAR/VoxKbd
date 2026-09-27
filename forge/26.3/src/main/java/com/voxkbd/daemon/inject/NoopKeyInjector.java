package com.voxkbd.daemon.inject;

import com.voxkbd.core.key.PhysicalKey;

/**
 * Fallback injector used when Robot is unavailable (e.g. headless Linux without X). LOCKED keys are
 * then not re-injected, but the daemon still forwards unlocked keys to the mod so the mod never
 * crashes (decision D12: graceful degradation, not failure).
 */
public final class NoopKeyInjector implements KeyInjector {
    @Override
    public void injectPhysical(PhysicalKey phys, int action, int mods) {
        // intentionally no-op
    }
}
