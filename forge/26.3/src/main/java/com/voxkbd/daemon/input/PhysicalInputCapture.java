package com.voxkbd.daemon.input;

import java.util.function.Consumer;

/**
 * Pluggable physical-input capture backend. Production path on Windows is {@link WindowsKeyboardHook}
 * (global low-level keyboard hook via the JDK's FFM API); {@link HeadlessCapture} is a test double.
 */
public interface PhysicalInputCapture {
    /** Begin capturing; each captured key is delivered to {@code sink}. */
    void start(Consumer<CapturedKey> sink) throws Exception;

    /** Stop capturing and release OS hooks. */
    void stop();

    /** True once {@link #start(Consumer)} has succeeded. */
    boolean isRunning();
}
