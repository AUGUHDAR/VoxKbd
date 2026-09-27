package com.voxkbd.mod.switch_;

import com.voxkbd.core.Constants;
import com.voxkbd.core.config.ComboPrefixMask;
import com.voxkbd.core.config.Config;
import com.voxkbd.core.input.InputState;

import java.util.ArrayList;
import java.util.List;
import java.util.function.IntConsumer;
import java.util.function.LongSupplier;

/**
 * Holds the active keyboard and drives switching (decision D4 / D20 as revised in v0.5; §3.3):
 * <ul>
 *   <li><b>previous()</b> / <b>next()</b> &#8212; single-activation loop over <b>all switchable
 *       keyboards</b>: the 默认键盘 (vanilla baseline, id 0) first, then every configured virtual
 *       keyboard in ascending id order. The default keyboard IS a switch target.</li>
 *   <li><b>combo + digit</b> &#8212; {@link ComboDigitParser} accumulates digits while the fully-open
 *       prefix (D8) is held; after the debounce window (D9) the buffered number selects that keyboard
 *       (keyboards are infinite per D2, so any non-negative number is accepted — 0 = 默认键盘).</li>
 * </ul>
 *
 * <p>On every switch it updates {@link InputState} and invokes the supplied {@code statePusher}
 * (which delivers {@code state} to the daemon on desktop / mixin on Android) plus the
 * {@code switchNotifier} (which shows the switch notification, §3.7.1).</p>
 *
 * <p>This class is loader-agnostic (only JDK + core); it is part of the javac-verifiable surface of
 * voxkbd-mod.</p>
 */
public final class SwitchManager {

    private final Config config;
    private final InputState inputState;
    private final Runnable statePusher;
    private final IntConsumer switchNotifier;
    private final LongSupplier clock;
    private final ComboDigitParser parser;
    private int prefixMask;

    public SwitchManager(Config config, InputState inputState, Runnable statePusher, IntConsumer switchNotifier) {
        this(config, inputState, statePusher, switchNotifier, System::currentTimeMillis);
    }

    public SwitchManager(Config config, InputState inputState, Runnable statePusher,
                         IntConsumer switchNotifier, LongSupplier clock) {
        this.config = config;
        this.inputState = inputState;
        this.statePusher = statePusher;
        this.switchNotifier = switchNotifier;
        this.clock = clock;
        this.prefixMask = ComboPrefixMask.parse(config.switchCfg.comboPrefix);
        this.parser = new ComboDigitParser(config.switchCfg.comboDigitDebounceMs);
        // Capture backends (Windows hook / Android mixin) read the mask from shared state so they
        // can let prefix+digit through even when that digit is locked to vanilla.
        this.inputState.setComboPrefixMask(prefixMask);
    }

    /** Re-read the combo prefix (e.g. after a config hot-reload). */
    public void refreshPrefix() {
        this.prefixMask = ComboPrefixMask.parse(config.switchCfg.comboPrefix);
        this.parser.setDebounceMs(config.switchCfg.comboDigitDebounceMs);
        this.inputState.setComboPrefixMask(prefixMask);
    }

    /** All switchable keyboard ids: 默认键盘 (0) first, then configured virtual keyboards ascending. */
    private List<Integer> cycleIds() {
        List<Integer> ids = new ArrayList<>();
        ids.add(Constants.VANILLA_KB_INDEX);
        for (Config.KeyboardEntry e : config.keyboards) {
            if (e.id != Constants.VANILLA_KB_INDEX) ids.add(e.id);
        }
        ids.sort(Integer::compareTo);
        return ids;
    }

    public void previous() {
        List<Integer> ids = cycleIds();
        int cur = inputState.activeKeyboard();
        int idx = ids.indexOf(cur);
        int next = (idx <= 0) ? ids.get(ids.size() - 1) : ids.get(idx - 1);
        applySwitch(next);
    }

    public void next() {
        List<Integer> ids = cycleIds();
        int cur = inputState.activeKeyboard();
        int idx = ids.indexOf(cur);
        int next = (idx < 0 || idx == ids.size() - 1) ? ids.get(0) : ids.get(idx + 1);
        applySwitch(next);
    }

    /** Switch directly to a keyboard index (D9, infinite; 0 = 默认键盘). */
    public void switchTo(int kb) {
        if (kb < 0) kb = 0;
        applySwitch(kb);
    }

    private void applySwitch(int kb) {
        inputState.setActiveKeyboard(kb);
        if (statePusher != null) statePusher.run();
        if (switchNotifier != null) switchNotifier.accept(kb);
    }

    /**
     * Feed a physical key token that may be a combo digit. Callers should pass the GLFW modifier
     * bits captured at the same moment; only when they exactly match the configured prefix do we
     * accumulate (otherwise any in-progress combo is cancelled, D8/D9).
     */
    public void onComboDigit(String physToken, int glfwMods) {
        boolean prefixHeld = matchesPrefix(glfwMods);
        if (!prefixHeld) {
            parser.reset();
            return;
        }
        Integer d = digitOf(physToken);
        if (d != null) parser.feedDigit(d, clock.getAsLong());
    }

    /** Periodic tick: finalize a multi-digit combo after the debounce window and switch if ready. */
    public int tick() {
        int target = parser.tryFinalize(clock.getAsLong());
        if (target >= 0) applySwitch(target);
        return target;
    }

    /** Whether the currently-held GLFW modifier bits exactly equal the configured combo prefix.
     *  A zero mask means combo switching is disabled — nothing ever matches. */
    public boolean matchesPrefix(int glfwMods) {
        return prefixMask != 0 && glfwModsToMask(glfwMods) == prefixMask;
    }

    public int prefixMask() {
        return prefixMask;
    }

    private static int glfwModsToMask(int glfwMods) {
        return ComboPrefixMask.fromGlfwMods(glfwMods);
    }

    /** Map a physical-key token such as {@code D0}..{@code D9} to its digit, else null. */
    private static Integer digitOf(String physToken) {
        if (!ComboPrefixMask.isDigitToken(physToken)) return null;
        return physToken.charAt(1) - '0';
    }

    /** Debounce window accessor (exposed for tests / diagnostics). */
    public long debounceMs() {
        return parser.getDebounceMs();
    }
}
