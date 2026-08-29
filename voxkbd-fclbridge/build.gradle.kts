plugins {
    `java-library`
}

description = "voxkbd-fclbridge: optional Android helper. Reaches FCL/ZL2 native GLFW_invoke_Key bridge via reflection (class-loader isolation safe, per D6)."

dependencies {
    api(project(":voxkbd-core"))
    // Pure reflection against FCL/ZL2 runtime classes; no hard dependency.
}
