package com.voxkbd.mixin;

import com.mojang.blaze3d.platform.InputConstants;
import net.minecraft.client.KeyMapping;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

/**
 * Exposes KeyMapping's live {@code key} field (the player-chosen binding). On intermediary / SRG
 * runtimes a reflection-by-name lookup cannot find the obfuscated field, so the 26.2 approach is
 * replaced by this accessor mixin (the AP remaps "key" per version/loader automatically).
 */
@Mixin(KeyMapping.class)
public interface KeyMappingAccessor {

    @Accessor("key")
    InputConstants.Key voxkbd$key();
}
