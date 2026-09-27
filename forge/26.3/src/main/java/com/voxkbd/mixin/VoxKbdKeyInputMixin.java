package com.voxkbd.mixin;

import com.voxkbd.bridge.VoxKbdMixinApi;
import net.minecraft.client.KeyboardHandler;
import net.minecraft.client.input.KeyEvent;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Android / SDL-era backend (MC 26.3+, decisions D6/D7/D13/D16/D17): intercepts every key event
 * at the single SDL-era choke point {@code KeyboardHandler#keyPress} — the same entry the SDL
 * event pump calls — and swaps unlocked physical keys for synthetic {@code VOXKBD_*} codes
 * in-process, with NO external daemon and no launcher injection bridge.
 *
 * <p>When {@link VoxKbdMixinApi#translate} returns a translated event, the original call is
 * cancelled and re-invoked once with the synthetic event; the re-invocation hits this same
 * handler, fails the {@code base()} guard (already-synthetic code) and flows through untouched —
 * that terminates the recursion.</p>
 *
 * <p>Never throws: the whole game's keyboard flows through here, so any backend bug degrades to
 * forwarding the untouched event.</p>
 */
@Mixin(KeyboardHandler.class)
public abstract class VoxKbdKeyInputMixin {

    @Inject(method = "keyPress", at = @At("HEAD"), cancellable = true)
    private void voxkbd$translate(long window, int action, KeyEvent event, CallbackInfo ci) {
        try {
            KeyEvent translated = VoxKbdMixinApi.translate(event);
            if (translated != null) {
                ((KeyboardHandler) (Object) this).keyPress(window, action, translated);
                ci.cancel();
            }
        } catch (Throwable ignored) {
            // Pass the untouched event through on any backend failure.
        }
    }
}
