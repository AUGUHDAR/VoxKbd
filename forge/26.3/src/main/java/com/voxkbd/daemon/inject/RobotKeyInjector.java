package com.voxkbd.daemon.inject;

import com.voxkbd.core.key.PhysicalKey;
import com.voxkbd.daemon.inject.AwtKeyMap;

import java.awt.Robot;
import java.awt.event.InputEvent;

/**
 * Real OS-level pass-through injector for LOCKED keys, using {@link Robot} (honors D3 for the locked
 * path). Keyboard keys are emitted via {@link Robot#keyPress(int)}/{@link Robot#keyRelease(int)};
 * mouse buttons via {@link Robot#mousePress(int)}/{@link Robot#mouseRelease(int)}. Gamepad keys are
 * not emittable by Robot and are skipped (the daemon still forwards unlocked gamepad keys to the mod).
 */
public final class RobotKeyInjector implements KeyInjector {
    private final Robot robot;

    public RobotKeyInjector() throws java.awt.AWTException {
        this.robot = new Robot();
    }

    @Override
    public void injectPhysical(PhysicalKey phys, int action, int mods) {
        int mouse = AwtKeyMap.awtMouseButton(phys);
        if (mouse != -1) {
            int mask = switch (mouse) {
                case java.awt.event.MouseEvent.BUTTON1 -> InputEvent.BUTTON1_DOWN_MASK;
                case java.awt.event.MouseEvent.BUTTON2 -> InputEvent.BUTTON2_DOWN_MASK;
                case java.awt.event.MouseEvent.BUTTON3 -> InputEvent.BUTTON3_DOWN_MASK;
                default -> -1;
            };
            if (mask == -1) return;
            if (action == 0) robot.mouseRelease(mask);
            else robot.mousePress(mask);
            return;
        }

        int vk = AwtKeyMap.awtVk(phys);
        if (vk == -1) {
            // Gamepad / unmappable: cannot pass through via Robot.
            return;
        }
        if (action == 0) robot.keyRelease(vk);
        else robot.keyPress(vk);
    }
}
