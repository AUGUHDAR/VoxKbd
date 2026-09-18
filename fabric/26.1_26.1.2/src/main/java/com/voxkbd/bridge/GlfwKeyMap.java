package com.voxkbd.bridge;

import com.voxkbd.core.key.PhysicalKey;
import com.voxkbd.core.key.PhysicalKeyRegistry;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Reverse lookup: GLFW physical keycode -> {@link PhysicalKey}.
 *
 * <p>Builds an immutable {@code Map<Integer, PhysicalKey>} from {@link PhysicalKeyRegistry#all()},
 * keyed by each token's {@link PhysicalKey#glfwCode()}. This is the inverse of the GLFW -> physical
 * mapping used by the desktop daemon/Android injection. It lets the mixin translate the raw GLFW
 * {@code key} argument of a key callback back into the {@link PhysicalKey} we registered in
 * {@link com.voxkbd.core.key.KeycodeTable}.</p>
 *
 * <p>Note: GLFW mouse-button codes and gamepad-button codes overlap with a subset of keyboard
 * codes (e.g. mouse button 0 == GLFW_KEY_? no, but gamepad 0 == some key). The mapping is built
 * directly from the core registry, so it stays authoritative and never needs manual maintenance —
 * only {@link PhysicalKey} declares the canonical code per input. Where codes collide (mouse vs
 * gamepad vs keyboard) the registry order wins; in practice FCL/ZL2 deliver mouse/gamepad through
 * the same GLFW callback, and the core table is the single source of truth.</p>
 */
public final class GlfwKeyMap {
    private GlfwKeyMap() {}

    /** glfwCode -> PhysicalKey, built once from the frozen core registry. */
    private static final Map<Integer, PhysicalKey> BY_GLFW_CODE = build();

    private static Map<Integer, PhysicalKey> build() {
        Map<Integer, PhysicalKey> map = new ConcurrentHashMap<>();
        for (PhysicalKey key : PhysicalKeyRegistry.all()) {
            map.put(key.glfwCode(), key);
        }
        return Map.copyOf(map);
    }

    /**
     * Resolve a raw GLFW keycode to the registered {@link PhysicalKey}, or {@code null} if the code
     * is unknown (e.g. an extended synthetic VOXKBD code, or a key outside the fixed table).
     */
    public static PhysicalKey physicalKeyOf(int glfwKeyCode) {
        return BY_GLFW_CODE.get(glfwKeyCode);
    }

    /**
     * True when the GLFW keycode is NOT a physical key we manage. Used by the mixin to avoid
     * re-translating codes it produced itself (prevents an infinite injection loop).
     */
    public static boolean isUnknown(int glfwKeyCode) {
        return !BY_GLFW_CODE.containsKey(glfwKeyCode);
    }
}
