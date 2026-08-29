import net.fabricmc.loom.task.RemapJarTask

// Loom is applied via the buildscript classpath (not the `plugins {}` block) so its artifact can be
// resolved from the Fabric maven without touching settings.gradle.kts pluginManagement.
buildscript {
    configurations.all {
        resolutionStrategy {
            // Loom 1.10.5's bundled ASM cannot read Java 25 class files (major version 69); force 9.9.
            force(
                "org.ow2.asm:asm:9.9",
                "org.ow2.asm:asm-tree:9.9",
                "org.ow2.asm:asm-commons:9.9",
                "org.ow2.asm:asm-analysis:9.9",
            )
        }
    }
    repositories {
        mavenCentral()
        maven { url = uri("file:///D:/voxkbd-local-maven") }
        maven { url = uri("https://maven.fabricmc.net/") }
        maven { url = uri("https://maven.minecraftforge.net/") }
        gradlePluginPortal()
    }
    dependencies {
        classpath("net.fabricmc:fabric-loom:1.18.0-alpha.16")
    }
}

apply(plugin = "fabric-loom")

plugins {
    `java-library`
}

repositories {
    // All runtime dependencies are provided by loom/MC or are plain-Java modules bundled via include.
    // Native access goes through the JDK's built-in FFM API, so no external library ships in the jar.
}

description = "voxkbd-mod: Fabric mod. Lock/switch managers, master/config UI, i18n, config persistence, GLFW KeyBinding registration + focus listener, and the in-process desktop capture pipeline."

val minecraft_version: String by project
val yarn_mappings: String by project
val fabric_loader_version: String by project
val fabric_api_version: String by project

java {
    // MC 26.2 ships as Java 25 class files (major version 69).
    sourceCompatibility = JavaVersion.VERSION_25
    targetCompatibility = JavaVersion.VERSION_25
}

dependencies {
    // Fabric / Minecraft
    // NOTE: loom is applied via `apply(plugin=...)` (not the `plugins {}` block) so the Fabric maven
    // can be used for plugin resolution without editing settings.gradle.kts. Because of that the
    // Kotlin DSL does not generate loom's typed dependency accessors, so we use the generic
    // `add("<configuration>", ...)` form for loom-specific configurations.
    add("minecraft", "com.mojang:minecraft:$minecraft_version")
    // 26.2 mapping provided for this environment (com.voxcore.mappings) — its "named" side is Mojang
    // official names (identity mapping), so the mod/mixin source is written against Mojang names.
    add("mappings", "com.voxcore.mappings:26.2:26_2.1.0:v2")
    add("modImplementation", "net.fabricmc:fabric-loader:$fabric_loader_version")
    add("modImplementation", "net.fabricmc.fabric-api:fabric-api:$fabric_api_version")

    // Shared core library (loader-agnostic contract layer). `implementation` puts it on the
    // compile classpath; `include` bundles it INTO the mod jar so the single file is self-contained.
    add("implementation", project(":voxkbd-core"))
    add("include", project(":voxkbd-core"))

    // Desktop capture pipeline (§4.0 / decision D12). The daemon module is now bundled INTO the mod so
    // a single JAR does everything — no separate process, no second download. `implementation` gives the
    // mod compile access to the daemon's capture/translate classes; `include` nests the daemon jar.
    // The daemon's Windows hook uses the JDK's built-in FFM API — nothing extra to bundle.
    add("implementation", project(":voxkbd-daemon"))
    add("include", project(":voxkbd-daemon"))

    // Android GLFW injection backend (process-internal). Bundled + remapped into the mod jar so the
    // mixin (and its voxkbd.mixins.json) ship with the mod; inert on desktop (D6 / §4.0).
    add("include", project(":voxkbd-mixin"))
    add("include", project(":voxkbd-fclbridge"))
}

tasks.processResources {
    inputs.property("version", project.version)
    filesMatching("fabric.mod.json") {
        expand("version" to project.version)
    }
}
