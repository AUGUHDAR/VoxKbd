package com.voxkbd.core.key;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Read-only view over the fixed {@link PhysicalKey} sequence table. The ordinal of each enum
 * constant is its fixed {@code idx} (decision D16 / §5.3). Lookups are dynamic (never hardcode
 * key lists elsewhere — see §3.4.3 / §3.4.4 "动态检测，非硬编码").
 */
public final class PhysicalKeyRegistry {
    private PhysicalKeyRegistry() {}

    private static final PhysicalKey[] VALUES = PhysicalKey.values();
    private static final Map<String, PhysicalKey> BY_TOKEN = new LinkedHashMap<>();
    static {
        for (PhysicalKey k : VALUES) BY_TOKEN.put(k.token(), k);
    }

    /**
     * Tokens a capture backend must NEVER intercept/suppress at the OS layer: the Escape key
     * (hardwired in MC outside KeyMappings — hijacking it would break pause/menus) and all eight
     * physical modifiers (they are reported live via modifier bits; suppressing them would break
     * system shortcuts, AltGr typing and combo prefixes themselves). This is capture policy for
     * OS-level hooks — NOT a hardcoded game-lock list (§3.4 stays fully dynamic). The set is
     * built from the enum so adding a new modifier / reserved token to {@link PhysicalKey} takes
     * effect without further code changes.
     */
    private static final Set<String> RESERVED;
    static {
        Set<String> r = new java.util.HashSet<>();
        r.add(PhysicalKey.ESC.token());
        for (PhysicalKey k : VALUES) if (k.isModifier()) r.add(k.token());
        RESERVED = Set.copyOf(r);
    }

    /** True when the token must pass through natively, untouched by any OS-level capture. */
    public static boolean isReserved(String token) {
        return RESERVED.contains(token);
    }

    /** All physical keys in fixed declaration order (the sequence table). */
    public static List<PhysicalKey> all() {
        return List.of(VALUES);
    }

    /** Number of entries in the fixed table (used to validate STRIDE >= table size). */
    public static int size() {
        return VALUES.length;
    }

    /** Fixed {@code idx} of a physical key (= its enum ordinal). */
    public static int idx(PhysicalKey key) {
        return key.ordinal();
    }

    /** Lookup by token (case-sensitive, matches enum name). Returns null if unknown. */
    public static PhysicalKey byToken(String token) {
        return token == null ? null : BY_TOKEN.get(token);
    }

    public static boolean contains(String token) {
        return BY_TOKEN.containsKey(token);
    }

    /** Immutable token → key map, handy for building lock snapshots. */
    public static Map<String, PhysicalKey> byTokenMap() {
        return Collections.unmodifiableMap(BY_TOKEN);
    }
}
