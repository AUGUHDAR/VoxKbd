plugins {
    application
}

description = "voxkbd-daemon: standalone desktop Java process. Captures physical input, translates to VOXKBD_* synthetic keys, injects at OS level, maintains heartbeat."

dependencies {
    implementation(project(":voxkbd-core"))
    // The Windows low-level keyboard hook uses the JDK's built-in Foreign Function & Memory API
    // (java.lang.foreign, final since Java 22). MC 26.2 mandates a Java 25 runtime, so no native
    // binding library (JNA etc.) is required and none is bundled.
}

application {
    mainClass.set("com.voxkbd.daemon.VoxKbdDaemon")
    applicationDefaultJvmArgs = listOf("-Xms32m", "-Xmx128m")
}

// The default `jar` contains ONLY the daemon's own classes — that is what the mod bundles via loom's
// `include` (so core / JNA are not duplicated). A separate `fatJar` produces the optional standalone
// executable (runnable on its own); the single-JAR mod no longer needs it.
tasks.register<Jar>("fatJar") {
    archiveClassifier.set("all")
    manifest {
        attributes["Main-Class"] = "com.voxkbd.daemon.VoxKbdDaemon"
    }
    from(configurations.runtimeClasspath.get().map { if (it.isDirectory) it else zipTree(it) }) {
        exclude("META-INF/*.SF", "META-INF/*.DSA", "META-INF/*.RSA")
    }
    duplicatesStrategy = DuplicatesStrategy.EXCLUDE
}
