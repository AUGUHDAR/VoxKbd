package com.voxkbd.core.io;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;

/**
 * Shared Gson instance for config and protocol (decision D11: JSON).
 */
public final class Json {
    private Json() {}

    /** Compact Gson for the line protocol; pretty variant for human-readable config files. */
    public static final Gson GSON = new GsonBuilder()
            .disableHtmlEscaping()
            .create();

    public static final Gson GSON_PRETTY = new GsonBuilder()
            .disableHtmlEscaping()
            .setPrettyPrinting()
            .create();
}
