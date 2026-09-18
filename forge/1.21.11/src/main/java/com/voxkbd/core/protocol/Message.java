package com.voxkbd.core.protocol;

import com.voxkbd.core.io.Json;

import java.util.List;

/**
 * A single protocol message. Implemented as one flexible POJO (only non-null fields are
 * serialized) so the JSON-line protocol stays tolerant to extension. Use the static factories to
 * build well-formed messages; {@link #fromJson(String)} / {@link #toJson()} for transport.
 */
public final class Message {

    public String t;            // message type (Protocol.T_*)
    public Long ts;             // heartbeat timestamp (ping/pong)
    public Integer mcPid;       // hello
    public String ver;          // hello / daemon version
    public Integer activeKeyboard; // state: current active virtual keyboard index
    public Boolean focus;       // state: MC window focused (D7)
    public List<String> lock;   // state: locked physical-key tokens (dynamic, never hardcoded)
    public String reason;       // daemon_exit

    // key_event (desktop): a captured+translated physical key destined for GLFW delivery by the mod
    public Integer kb;          // target virtual keyboard index at capture time
    public String phys;         // physical-key token (e.g. "W", "ML", "GP_A")
    public Integer action;      // 0=release, 1=press, 2=repeat (GLFW action semantics)
    public Integer mods;        // modifier bitmask at capture time

    private Message(String type) { this.t = type; }

    // ---- factories ----

    public static Message hello(int mcPid, String ver) {
        Message m = new Message(Protocol.T_HELLO);
        m.mcPid = mcPid; m.ver = ver; return m;
    }

    public static Message ready() { return new Message(Protocol.T_READY); }

    public static Message ping(long ts) {
        Message m = new Message(Protocol.T_PING); m.ts = ts; return m;
    }

    public static Message pong(long ts) {
        Message m = new Message(Protocol.T_PONG); m.ts = ts; return m;
    }

    public static Message state(int activeKeyboard, boolean focus, List<String> lock) {
        Message m = new Message(Protocol.T_STATE);
        m.activeKeyboard = activeKeyboard; m.focus = focus; m.lock = lock;
        return m;
    }

    public static Message reload() { return new Message(Protocol.T_RELOAD); }

    public static Message shutdown() { return new Message(Protocol.T_SHUTDOWN); }

    public static Message daemonExit(String reason) {
        Message m = new Message(Protocol.T_DAEMON_EXIT); m.reason = reason; return m;
    }

    /** Desktop: daemon captured + translated a physical key; mod delivers it into GLFW. */
    public static Message keyEvent(int kb, String phys, int action, int mods) {
        Message m = new Message(Protocol.T_KEY_EVENT);
        m.kb = kb; m.phys = phys; m.action = action; m.mods = mods; return m;
    }

    // ---- (de)serialization ----

    public String toJson() { return Json.GSON.toJson(this); }

    public static Message fromJson(String line) { return Json.GSON.fromJson(line, Message.class); }

    @Override
    public String toString() { return "Message{" + t + (ts != null ? ",ts=" + ts : "")
            + (activeKeyboard != null ? ",kb=" + activeKeyboard : "")
            + (focus != null ? ",focus=" + focus : "") + "}"; }
}
