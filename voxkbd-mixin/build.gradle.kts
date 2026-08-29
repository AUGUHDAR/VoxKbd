plugins {
    `java-library`
}

description = "voxkbd-mixin: Fabric mixin (Android). Wraps MC's glfwSetKeyCallback to synthesize VOXKBD_* keys in-process and feed the GLFW input layer."

val fabric_loader_version: String by project

dependencies {
    api(project(":voxkbd-core"))
    compileOnly(project(":voxkbd-fclbridge"))

    // LWJGL GLFW binding (glfwSetKeyCallback lives in org.lwjgl.glfw.GLFW).
    // Provided by loom at runtime on the mod side; declared here for standalone compile.
    compileOnly("org.lwjgl:lwjgl-glfw:3.3.4")
    compileOnly("org.lwjgl:lwjgl:3.3.4")

    // SpongePowered Mixin annotations (@Mixin, @Inject, ...).
    compileOnly("org.spongepowered:mixin:0.8.5")
    compileOnly("net.fabricmc:fabric-loader:$fabric_loader_version")
}
