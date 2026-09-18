// Root build: shared repositories, Java toolchain and common conventions.
// Each module applies its own plugins (java-library / fabric-loom / application).
plugins {
    // Loom is applied per-module so plain-Java modules stay lightweight.
}

allprojects {
    group = "com.voxkbd"
    version = "1.0.1"

    repositories {
        mavenCentral()
        // Local repo holding the 26.2 mapping (com.voxcore.mappings) provided for this environment.
        maven { url = uri("file:///D:/voxkbd-local-maven") }
        // Fabric / Minecraft toolchain
        maven { url = uri("https://maven.fabricmc.net/") }
        maven { url = uri("https://maven.minecraftforge.net/") }
        maven { url = uri("https://www.cursemaven.com/maven/") }
        maven { url = uri("https://maven.shedaniel.me/") }
        maven { url = uri("https://api.modrinth.com/maven/") }
    }
}

subprojects {
    // Common Java toolchain. Core/daemon are plain Java; loom modules override as needed.
    plugins.withType<org.gradle.api.plugins.JavaPlugin> {
        extensions.configure<org.gradle.api.plugins.JavaPluginExtension> {
            toolchain {
                languageVersion.set(JavaLanguageVersion.of(25))
            }
        }
    }
}
