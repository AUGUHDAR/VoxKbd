package com.voxkbd.core.protocol;

/**
 * JSON-line protocol constants exchanged between the mod (in the MC process) and the desktop
 * daemon (decision D3/D14/D16; §4.3 / §5.2). One JSON object per line, terminated by {@code \n}.
 *
 * <p>Message types:</p>
 * <ul>
 *   <li>{@code hello}      mod → daemon : handshake ({@code mcPid}, {@code ver})</li>
 *   <li>{@code ready}      daemon → mod  : handshake ack</li>
 *   <li>{@code ping}/{@code pong}  bidirectional heartbeat ({@code ts})</li>
 *   <li>{@code state}      mod → daemon : {@code activeKeyboard}, {@code focus}, {@code lock[]}</li>
 *   <li>{@code reload}     mod → daemon : re-read config (or daemon watches file itself)</li>
 *   <li>{@code shutdown}   mod → daemon : MC/mod exiting, daemon must close now</li>
 *   <li>{@code daemon_exit} daemon → mod : daemon about to die ({@code reason}) → mod self-heals</li>
 *   <li>{@code key_event}  daemon → mod : a captured+translated physical key to deliver into GLFW
 *        ({@code kb}, {@code phys}, {@code action}, {@code mods}). Desktop only — see below.</li>
 * </ul>
 *
 * <h3>Desktop delivery split (engineering reconciliation of D3 + D16)</h3>
 * A separate-process daemon cannot emit a 400+ extended GLFW code into MC's GLFW via OS injection
 * (Robot/keyboard hooks are limited to the real keycode range). Therefore on desktop:
 * <ul>
 *   <li>Locked physical keys: the daemon performs a real OS-level Robot injection (pass-through),
 *       so MC receives them exactly as vanilla (honors D3).</li>
 *   <li>Unlocked physical keys: the daemon captures + suppresses them and forwards a {@code key_event}
 *       to the mod, which delivers the synthetic 400+ extended code into GLFW in-process (the mod is
 *       the only component inside MC's process). This honors D16's extended-keycode registration and
 *       keeps the bound action identical across platforms (decision D15/D16 intent).</li>
 * </ul>
 * On Android there is no daemon: the mixin performs capture+translation+GLFW delivery in one place.
 */
public final class Protocol {
    private Protocol() {}

    public static final String T_HELLO = "hello";
    public static final String T_READY = "ready";
    public static final String T_PING = "ping";
    public static final String T_PONG = "pong";
    public static final String T_STATE = "state";
    public static final String T_RELOAD = "reload";
    public static final String T_SHUTDOWN = "shutdown";
    public static final String T_DAEMON_EXIT = "daemon_exit";
    public static final String T_KEY_EVENT = "key_event";

    /** Line delimiter for the JSON-line stream. */
    public static final String DELIMITER = "\n";

    /** Heartbeat timeout in ms; either side considers the peer dead after this (§5.2). */
    public static final long HEARTBEAT_TIMEOUT_MS = 3000;

    /** Heartbeat period in ms. */
    public static final long HEARTBEAT_PERIOD_MS = 1000;
}
