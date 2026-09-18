package com.voxkbd.core.key;

/**
 * A single mappable physical input, drawn from a FIXED, cross-platform sequence table
 * (decision D17 / §5.3). The declaration order of this enum IS the fixed {@code idx} used by
 * {@link KeycodeTable}. Never reorder or remove entries — only append — or existing keycode
 * assignments would shift across versions and packs.
 *
 * <p>Categories, in order: keyboard (A–Z, 0–9, punctuation, function keys, navigation, modifiers,
 * numpad) → mouse → gamepad.</p>
 *
 * <p>{@code glfwCode} is the canonical GLFW code for the *physical* input (used by the Android
 * backend and by OS-level injection translation). For gamepad it is the GLFW gamepad button code.</p>
 */
public enum PhysicalKey {
    // ---- Keyboard: letters A-Z (GLFW_KEY_A=65 .. GLFW_KEY_Z=90) ----
    A(65), B(66), C(67), D(68), E(69), F(70), G(71), H(72), I(73), J(74),
    K(75), L(76), M(77), N(78), O(79), P(80), Q(81), R(82), S(83), T(84),
    U(85), V(86), W(87), X(88), Y(89), Z(90),

    // ---- Keyboard: digits 0-9 (GLFW_KEY_0=48 .. GLFW_KEY_9=57) ----
    D0(48), D1(49), D2(50), D3(51), D4(52), D5(53), D6(54), D7(55), D8(56), D9(57),

    // ---- Keyboard: punctuation (US QWERTY layout, on the main alphanumeric block) ----
    GRAVE(96),            // `  (backtick / tilde)
    MINUS(45),            // -  (minus / underscore)
    EQUAL(61),            // =  (equals / plus)
    LBRACKET(91),         // [
    RBRACKET(93),         // ]
    BACKSLASH(92),        // \
    SEMICOLON(59),        // ;
    APOSTROPHE(39),       // '
    COMMA(44),            // ,
    PERIOD(46),           // .
    SLASH(47),            // /

    // ---- Keyboard: function keys F1-F12 (GLFW_KEY_F1=290 .. GLFW_KEY_F12=301) ----
    F1(290), F2(291), F3(292), F4(293), F5(294), F6(295), F7(296), F8(297), F9(298), F10(299),
    F11(300), F12(301),

    // ---- Keyboard: navigation / editing ----
    SPACE(32), ENTER(257), TAB(258), ESC(256), BACKSPACE(259),
    INSERT(260), DELETE(261), HOME(268), END(269), PAGEUP(266), PAGEDOWN(267),
    CAPSLOCK(280),        // GLFW_KEY_CAPS_LOCK
    LEFT(263), RIGHT(262), UP(265), DOWN(264),

    // ---- Keyboard: modifiers (GLFW left/right split) ----
    LSHIFT(340), RSHIFT(344), LCTRL(341), RCTRL(345), LALT(342), RALT(346), LWIN(343), RWIN(347),

    // ---- Keyboard: numpad (GLFW_KEY_KP_* 320-336) ----
    KP_0(320), KP_1(321), KP_2(322), KP_3(323), KP_4(324), KP_5(325),
    KP_6(326), KP_7(327), KP_8(328), KP_9(329),
    KP_DECIMAL(330), KP_DIVIDE(331), KP_MULTIPLY(332),
    KP_SUBTRACT(333), KP_ADD(334), KP_ENTER(335), KP_EQUAL(336),

    // ---- Mouse: GLFW mouse button codes (GLFW_MOUSE_BUTTON_1=0 .. ) ----
    ML(0, Category.MOUSE), MR(1, Category.MOUSE), MM(2, Category.MOUSE),
    M4(3, Category.MOUSE), M5(4, Category.MOUSE), M6(5, Category.MOUSE),
    M7(6, Category.MOUSE), M8(7, Category.MOUSE),

    // ---- Gamepad: GLFW gamepad button codes (GLFW_GAMEPAD_BUTTON_*) ----
    GP_A(0, Category.GAMEPAD), GP_B(1, Category.GAMEPAD), GP_X(2, Category.GAMEPAD), GP_Y(3, Category.GAMEPAD),
    GP_LB(4, Category.GAMEPAD), GP_RB(5, Category.GAMEPAD), GP_BACK(6, Category.GAMEPAD), GP_START(7, Category.GAMEPAD),
    GP_GUIDE(8, Category.GAMEPAD), GP_LT(9, Category.GAMEPAD), GP_RT(10, Category.GAMEPAD),
    GP_LS(11, Category.GAMEPAD), GP_RS(12, Category.GAMEPAD),
    GP_UP(13, Category.GAMEPAD), GP_DOWN(14, Category.GAMEPAD), GP_LEFT(15, Category.GAMEPAD), GP_RIGHT(16, Category.GAMEPAD);

    /** Input category, in declaration order keyboard → mouse → gamepad (§5.3). */
    public enum Category { KEYBOARD, MOUSE, GAMEPAD }

    private final int glfwCode;
    private final Category category;

    PhysicalKey(int glfwCode) {
        this(glfwCode, Category.KEYBOARD);
    }

    PhysicalKey(int glfwCode, Category category) {
        this.glfwCode = glfwCode;
        this.category = category;
    }

    /** Canonical GLFW code of the *physical* input (used for injection/translation). */
    public int glfwCode() { return glfwCode; }

    public Category category() { return category; }

    /** Stable token used in the internal name, e.g. {@code W}, {@code ML}, {@code GP_A}. */
    public String token() { return name(); }

    /**
     * True when this is a left/right modifier key (LSHIFT/RSHIFT/LCTRL/RCTRL/LALT/RALT/LWIN/RWIN).
     * Lets callers enumerate modifiers without hardcoding the literal token strings (§3.4.3
     * "动态检测，非硬编码").
     */
    public boolean isModifier() {
        return this == LSHIFT || this == RSHIFT
                || this == LCTRL || this == RCTRL
                || this == LALT  || this == RALT
                || this == LWIN  || this == RWIN;
    }

    /**
     * True when this is a US QWERTY punctuation key (operators & brackets on the main block).
     */
    public boolean isPunctuation() {
        return this == GRAVE || this == MINUS || this == EQUAL
                || this == LBRACKET || this == RBRACKET || this == BACKSLASH
                || this == SEMICOLON || this == APOSTROPHE
                || this == COMMA || this == PERIOD || this == SLASH;
    }

    /** True when this is a numpad key. */
    public boolean isNumpad() {
        return this == KP_0 || this == KP_1 || this == KP_2 || this == KP_3 || this == KP_4
                || this == KP_5 || this == KP_6 || this == KP_7 || this == KP_8 || this == KP_9
                || this == KP_DECIMAL || this == KP_DIVIDE || this == KP_MULTIPLY
                || this == KP_SUBTRACT || this == KP_ADD || this == KP_ENTER || this == KP_EQUAL;
    }

    /**
     * User-facing label for the master / config UI. Returns the natural English keycap name
     * (e.g. "Enter", "Backspace", "Page Up", "Shift") — these are the labels a real keyboard
     * prints, not programmer tokens, so the player can read them without translation.
     */
    public String label() {
        return switch (this) {
            case SPACE -> "Space";
            case LBRACKET -> "[";
            case RBRACKET -> "]";
            case BACKSLASH -> "\\";
            case SEMICOLON -> ";";
            case APOSTROPHE -> "'";
            case COMMA -> ",";
            case PERIOD -> ".";
            case SLASH -> "/";
            case GRAVE -> "`";
            case MINUS -> "-";
            case EQUAL -> "=";
            case PAGEUP -> "Page Up";
            case PAGEDOWN -> "Page Down";
            case CAPSLOCK -> "Caps Lock";
            case LSHIFT -> "Shift";
            case RSHIFT -> "Shift";
            case LCTRL -> "Ctrl";
            case RCTRL -> "Ctrl";
            case LALT -> "Alt";
            case RALT -> "Alt";
            case LWIN -> "Win";
            case RWIN -> "Win";
            case KP_0 -> "Num 0";
            case KP_1 -> "Num 1";
            case KP_2 -> "Num 2";
            case KP_3 -> "Num 3";
            case KP_4 -> "Num 4";
            case KP_5 -> "Num 5";
            case KP_6 -> "Num 6";
            case KP_7 -> "Num 7";
            case KP_8 -> "Num 8";
            case KP_9 -> "Num 9";
            case KP_DECIMAL -> "Num .";
            case KP_DIVIDE -> "Num /";
            case KP_MULTIPLY -> "Num *";
            case KP_SUBTRACT -> "Num -";
            case KP_ADD -> "Num +";
            case KP_ENTER -> "Num Enter";
            case KP_EQUAL -> "Num =";
            case ML -> "Mouse L";
            case MR -> "Mouse R";
            case MM -> "Mouse M";
            case M4 -> "Mouse 4";
            case M5 -> "Mouse 5";
            case M6 -> "Mouse 6";
            case M7 -> "Mouse 7";
            case M8 -> "Mouse 8";
            // Digits: keycap character, not the D<n> token
            case D0 -> "0";
            case D1 -> "1";
            case D2 -> "2";
            case D3 -> "3";
            case D4 -> "4";
            case D5 -> "5";
            case D6 -> "6";
            case D7 -> "7";
            case D8 -> "8";
            case D9 -> "9";
            // Editing / navigation: keycap text or arrow glyphs
            case ENTER -> "Enter";
            case TAB -> "Tab";
            case ESC -> "Esc";
            case BACKSPACE -> "Backspace";
            case INSERT -> "Insert";
            case DELETE -> "Delete";
            case HOME -> "Home";
            case END -> "End";
            case LEFT -> "←";
            case RIGHT -> "→";
            case UP -> "↑";
            case DOWN -> "↓";
            default -> token(); // A..Z, F1..F12 and gamepad fall through to their enum name
        };
    }
}
