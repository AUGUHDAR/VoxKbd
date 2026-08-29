package com.voxkbd.daemon.inject;

import com.voxkbd.core.key.PhysicalKey;

import java.awt.event.KeyEvent;

/**
 * Maps a {@link PhysicalKey} to a {@link KeyEvent} virtual-key code so {@link RobotKeyInjector}
 * can perform a real OS pass-through injection of LOCKED keys (desktop delivery split, §4.0).
 *
 * <p>Returns -1 for keys Robot cannot emit (e.g. gamepad); those are skipped with a log.</p>
 */
public final class AwtKeyMap {
    private AwtKeyMap() {}

    public static int awtVk(PhysicalKey k) {
        return switch (k) {
            // Letters / digits share codes with AWT.
            case A, B, C, D, E, F, G, H, I, J, K, L, M, N, O, P, Q, R, S, T, U, V, W, X, Y, Z ->
                    KeyEvent.VK_A + (k.ordinal() - PhysicalKey.A.ordinal());
            case D0 -> KeyEvent.VK_0;
            case D1 -> KeyEvent.VK_1;
            case D2 -> KeyEvent.VK_2;
            case D3 -> KeyEvent.VK_3;
            case D4 -> KeyEvent.VK_4;
            case D5 -> KeyEvent.VK_5;
            case D6 -> KeyEvent.VK_6;
            case D7 -> KeyEvent.VK_7;
            case D8 -> KeyEvent.VK_8;
            case D9 -> KeyEvent.VK_9;
            case F1 -> KeyEvent.VK_F1;
            case F2 -> KeyEvent.VK_F2;
            case F3 -> KeyEvent.VK_F3;
            case F4 -> KeyEvent.VK_F4;
            case F5 -> KeyEvent.VK_F5;
            case F6 -> KeyEvent.VK_F6;
            case F7 -> KeyEvent.VK_F7;
            case F8 -> KeyEvent.VK_F8;
            case F9 -> KeyEvent.VK_F9;
            case F10 -> KeyEvent.VK_F10;
            case F11 -> KeyEvent.VK_F11;
            case F12 -> KeyEvent.VK_F12;
            case SPACE -> KeyEvent.VK_SPACE;
            case ENTER -> KeyEvent.VK_ENTER;
            case TAB -> KeyEvent.VK_TAB;
            case ESC -> KeyEvent.VK_ESCAPE;
            case BACKSPACE -> KeyEvent.VK_BACK_SPACE;
            case INSERT -> KeyEvent.VK_INSERT;
            case DELETE -> KeyEvent.VK_DELETE;
            case HOME -> KeyEvent.VK_HOME;
            case END -> KeyEvent.VK_END;
            case PAGEUP -> KeyEvent.VK_PAGE_UP;
            case PAGEDOWN -> KeyEvent.VK_PAGE_DOWN;
            case LEFT -> KeyEvent.VK_LEFT;
            case RIGHT -> KeyEvent.VK_RIGHT;
            case UP -> KeyEvent.VK_UP;
            case DOWN -> KeyEvent.VK_DOWN;
            case LSHIFT, RSHIFT -> KeyEvent.VK_SHIFT;
            case LCTRL, RCTRL -> KeyEvent.VK_CONTROL;
            case LALT, RALT -> KeyEvent.VK_ALT;
            case LWIN, RWIN -> KeyEvent.VK_WINDOWS;
            default -> -1; // mouse / gamepad: not emittable via Robot keyboard
        };
    }

    /** AWT MouseEvent button mask for mouse physical keys, or -1 if not a mouse key. */
    public static int awtMouseButton(PhysicalKey k) {
        return switch (k) {
            case ML -> java.awt.event.MouseEvent.BUTTON1;
            case MR -> java.awt.event.MouseEvent.BUTTON2;
            case MM -> java.awt.event.MouseEvent.BUTTON3;
            default -> -1;
        };
    }
}
