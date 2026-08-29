plugins {
    `java-library`
}

description = "voxkbd-core: loader-agnostic shared library (naming rules, keycode allocation, config schema, protocol)."

dependencies {
    // JSON (de)serialization for config + protocol. Common in the MC ecosystem.
    api("com.google.code.gson:gson:2.11.0")
}
