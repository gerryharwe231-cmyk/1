package com.slopeconnector.surface.mixin;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.injection.Constant;
import org.spongepowered.asm.mixin.injection.ModifyConstant;

/** Keeps the on-screen hint consistent with the remapped G key. */
@Pseudo
@Mixin(targets = "com.slopeconnector.client.ArcWandHud", remap = false, priority = 2000)
public abstract class ArcHudPromptMixin {
    @ModifyConstant(method = "render",
            constant = @Constant(stringValue = "按 R 打开双侧面板"), remap = false)
    private static String slopeconnectorSurface$showG(String original) {
        return "按 G 打开双侧面板";
    }
}
