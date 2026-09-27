package com.voxkbd.mixin;

import com.voxkbd.bridge.VoxKbdMixinApi;
import org.lwjgl.glfw.GLFWKeyCallback;
import org.lwjgl.glfw.GLFWKeyCallbackI;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

/**
 * Android backend (decisions D6/D7/D13/D16/D17): wraps the keyboard callback MC installs so the
 * mod can synthesize {@code VOXKBD_*} extended keycodes in-process, with NO external daemon.
 *
 * <p>Target (MC 26.2): {@code com.mojang.blaze3d.platform.InputConstants#setupKeyboardCallbacks} is
 * the single choke point where MC registers its {@link GLFWKeyCallbackI} ({@code
 * KeyboardHandler.setup(Window)} funnels through it). We redirect its {@code GLFW.glfwSetKeyCallback}
 * call so our translating wrapper sits in front of MC's real callback.</p>
 *
 * <p>All logic lives in {@link VoxKbdMixinApi} (outside the mixin package — classes under a
 * declared mixin package can never be loaded as regular classes): mixin classes may only contain
 * private methods, and the mod reaches the wrapper through that class via reflection (no
 * build-time dependency).</p>
 */
@Mixin(targets = "com.mojang.blaze3d.platform.InputConstants")
public abstract class VoxKbdKeyCallbackMixin {

    @Redirect(
            method = "setupKeyboardCallbacks",
            at = @At(
                    value = "INVOKE",
                    target = "Lorg/lwjgl/glfw/GLFW;glfwSetKeyCallback(JLorg/lwjgl/glfw/GLFWKeyCallbackI;)Lorg/lwjgl/glfw/GLFWKeyCallback;",
                    remap = false),
            remap = false)
    private static GLFWKeyCallback voxkbd$wrapInstall(long window, GLFWKeyCallbackI callback) {
        return VoxKbdMixinApi.wrapInstall(window, callback);
    }
}
