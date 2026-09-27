package com.voxkbd.mod.switch_;

import java.util.function.IntConsumer;

/**
 * Accumulates the trailing digits of a "combo + digit" switch (decision D8/D9) and emits the
 * targeted virtual-keyboard number once the multi-digit debounce window elapses.
 *
 * <p>The caller is responsible for two things this class deliberately does NOT own:</p>
 * <ul>
 *   <li>Detecting that the configured combo <em>prefix</em> (Ctrl/Alt/Shift/Win) is currently held.
 *       If it is not, callers should call {@link #reset()} so a stray digit does not start a
 *       combo.</li>
 *   <li>Calling {@link #tryFinalize(long)} on a periodic tick (e.g. every client tick) so the
 *       accumulated digits flush after {@code debounceMs} of inactivity (D9: "1"+"1" = keyboard 11).</li>
 * </ul>
 *
 * <p>This class is loader-agnostic (only JDK + a callback); it is part of the javac-verifiable
 * surface of voxkbd-mod.</p>
 */
public final class ComboDigitParser {

    private long debounceMs;
    private final StringBuilder acc = new StringBuilder();
    private long lastDigitTime = Long.MIN_VALUE;

    public ComboDigitParser(long debounceMs) {
        if (debounceMs < 0) throw new IllegalArgumentException("debounceMs must be >= 0");
        this.debounceMs = debounceMs;
    }

    public void setDebounceMs(long ms) {
        if (ms < 0) throw new IllegalArgumentException("debounceMs must be >= 0");
        this.debounceMs = ms;
    }

    public long getDebounceMs() {
        return debounceMs;
    }

    /** Feed a single decimal digit (0-9) recorded at {@code nowMs}. Returns true if accepted. */
    public boolean feedDigit(int digit, long nowMs) {
        if (digit < 0 || digit > 9) return false;
        acc.append(digit);
        lastDigitTime = nowMs;
        return true;
    }

    /** True while at least one digit has been buffered and not yet finalized. */
    public boolean hasPending() {
        return acc.length() > 0;
    }

    /** Milliseconds remaining before the buffered digits would auto-finalize (0 if not pending). */
    public long remainingMs(long nowMs) {
        if (!hasPending()) return 0;
        return Math.max(0, debounceMs - (nowMs - lastDigitTime));
    }

    /**
     * If the debounce window has elapsed, flush the buffered digits to {@code target} and return the
     * parsed number; otherwise return {@code -1}. Only the first non-empty, non-leading-zero-padded
     * number is meaningful (e.g. "1"+"0" -> 10). A leading zero ("0"+"5" -> 5) is allowed.
     */
    public int tryFinalize(long nowMs) {
        if (!hasPending()) return -1;
        if (nowMs - lastDigitTime < debounceMs) return -1;
        int target = Integer.parseInt(acc.toString());
        acc.setLength(0);
        lastDigitTime = Long.MIN_VALUE;
        return target;
    }

    /** Discard any buffered digits (e.g. because the combo prefix is no longer held). */
    public void reset() {
        acc.setLength(0);
        lastDigitTime = Long.MIN_VALUE;
    }

    /**
     * Convenience: feed a digit and, if the window has elapsed, immediately emit the target via
     * {@code target}. Used by callers that tick on the same path as input.
     */
    public int feedAndMaybeFinalize(int digit, long nowMs, IntConsumer target) {
        if (!feedDigit(digit, nowMs)) return -1;
        int t = tryFinalize(nowMs);
        if (t >= 0 && target != null) target.accept(t);
        return t;
    }
}
