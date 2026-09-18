package com.voxkbd.core;

/**
 * Global constants for Vox Kbd (see docs/功能文档.md).
 *
 * <p>The internal synthetic-key naming is fixed: {@code VOXKBD_<kb>_<phys>} (decision D2/D15/D16,
 * and §8 resolves the separator to {@code _}). These constants are loader-agnostic and shared by
 * every module (core, daemon, mod, mixin, fclbridge).</p>
 */
public final class Constants {
    private Constants() {}

    /** Mod identifier used in fabric.mod.json, resource domains, i18n keys, etc. */
    public static final String MOD_ID = "voxkbd";
    public static final String MOD_NAME = "Vox Kbd";

    /** Fixed internal prefix for synthetic keys (must never change; cross-pack stable, D2). */
    public static final String PREFIX = "VOXKBD";
    /** Internal separator between name parts (D2 / §8). */
    public static final String SEP = "_";

    /** JSON-line protocol version exchanged between mod and daemon (§4.3 / §5.2). */
    public static final int PROTOCOL_VERSION = 1;

    /** On-disk config file name (JSON, decision D11). */
    public static final String CONFIG_FILE_NAME = "voxkbd.json";

    /**
     * Keycode allocation defaults (decision D16 / §5.3 / §5.4).
     * {@code keycode = BASE + kb * STRIDE + idx(physicalKey)}.
     * STRIDE must be >= the size of the fixed physical-key table (see PhysicalKeyRegistry);
     * §5.3 specifies 256, while §5.4's illustrative {@code 64} is too small and is overridden here.
     */
    public static final int DEFAULT_KEYCODE_BASE = 400;
    public static final int DEFAULT_KEYCODE_STRIDE = 256;

    /**
     * The default (vanilla) keyboard index. There is intentionally NO {@code VOXKBD_0}; virtual
     * keyboards are numbered from 1 (decision D20). Index 0 is reserved as the vanilla baseline.
     */
    public static final int VANILLA_KB_INDEX = 0;
}
