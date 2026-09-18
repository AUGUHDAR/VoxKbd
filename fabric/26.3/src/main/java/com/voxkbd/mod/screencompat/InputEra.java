package com.voxkbd.mod.screencompat;

/** MC 26.3 switched the client input space from GLFW keycodes to SDL scancodes. */
public final class InputEra {
    private InputEra() {}
    public static final boolean SDL_SCANCODES = true;
}
