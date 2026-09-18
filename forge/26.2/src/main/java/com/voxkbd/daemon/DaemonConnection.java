package com.voxkbd.daemon;

import com.voxkbd.core.input.InputState;
import com.voxkbd.core.protocol.Message;
import com.voxkbd.core.protocol.Protocol;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.PrintWriter;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

/**
 * TCP client that connects back to the mod (which runs the TCP server, §5.2). Handles the JSON-line
 * protocol: sends {@code hello}/{@code ping}/{@code key_event}; receives {@code ready}/{@code state}/
 * {@code ping}/{@code reload}/{@code shutdown}. Maintains the heartbeat and applies the timeout
 * (mod is considered dead → {@link Listener#onTimeout()}).
 */
public final class DaemonConnection implements AutoCloseable {
    /** Callbacks for control messages coming from the mod. */
    public interface Listener {
        void onReady();
        void onReload();
        /** Mod/MC is exiting; daemon must close now. */
        void onShutdown();
        /** Heartbeat timeout: the mod peer is dead; daemon should exit so it can be restarted. */
        void onTimeout();
    }

    private final InputState state;
    private final String host;
    private final int port;
    private final Listener listener;
    private final AtomicLong lastReceived = new AtomicLong(System.currentTimeMillis());

    private Socket socket;
    private PrintWriter out;
    private BufferedReader in;
    private final ScheduledExecutorService heartbeat = Executors.newSingleThreadScheduledExecutor(r -> {
        Thread t = new Thread(r, "voxkbd-daemon-heartbeat");
        t.setDaemon(true);
        return t;
    });
    private volatile boolean closed;

    public DaemonConnection(InputState state, String host, int port, Listener listener) {
        this.state = state;
        this.host = host;
        this.port = port;
        this.listener = listener;
    }

    /** Connect and start reader + heartbeat. Blocks until connected (throws if it cannot). */
    public void connect(int mcPid, String ver) throws IOException {
        socket = new Socket(host, port);
        out = new PrintWriter(new java.io.OutputStreamWriter(socket.getOutputStream(), StandardCharsets.UTF_8), true);
        in = new BufferedReader(new InputStreamReader(socket.getInputStream(), StandardCharsets.UTF_8));
        lastReceived.set(System.currentTimeMillis());
        send(Message.hello(mcPid, ver));

        Thread reader = new Thread(this::readLoop, "voxkbd-daemon-reader");
        reader.setDaemon(true);
        reader.start();

        heartbeat.scheduleAtFixedRate(this::tick, Protocol.HEARTBEAT_PERIOD_MS, Protocol.HEARTBEAT_PERIOD_MS, TimeUnit.MILLISECONDS);
    }

    private void readLoop() {
        try {
            String line;
            while (!closed && (line = in.readLine()) != null) {
                lastReceived.set(System.currentTimeMillis());
                Message m = Message.fromJson(line);
                if (m == null || m.t == null) continue;
                switch (m.t) {
                    case Protocol.T_READY -> listener.onReady();
                    case Protocol.T_STATE -> {
                        if (m.activeKeyboard != null) state.setActiveKeyboard(m.activeKeyboard);
                        if (m.focus != null) state.setFocus(m.focus);
                        if (m.lock != null) state.setLocked((List<String>) m.lock);
                    }
                    case Protocol.T_PING -> send(Message.pong(System.currentTimeMillis()));
                    case Protocol.T_RELOAD -> listener.onReload();
                    case Protocol.T_SHUTDOWN -> { listener.onShutdown(); return; }
                    default -> { /* ignore unknown */ }
                }
            }
        } catch (IOException e) {
            if (!closed) listener.onTimeout();
        }
    }

    private void tick() {
        if (closed) return;
        if (System.currentTimeMillis() - lastReceived.get() > Protocol.HEARTBEAT_TIMEOUT_MS) {
            listener.onTimeout();
            return;
        }
        send(Message.ping(System.currentTimeMillis()));
    }

    /** Thread-safe send of a protocol message (one JSON object per line). */
    public synchronized void send(Message m) {
        if (closed || out == null) return;
        out.print(m.toJson());
        out.print(Protocol.DELIMITER);
        out.flush();
    }

    @Override
    public void close() {
        closed = true;
        heartbeat.shutdownNow();
        try { if (socket != null) socket.close(); } catch (IOException ignored) {}
    }
}
