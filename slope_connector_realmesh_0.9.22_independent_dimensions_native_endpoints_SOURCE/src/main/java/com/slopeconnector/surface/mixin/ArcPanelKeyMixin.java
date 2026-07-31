package com.slopeconnector.surface.mixin;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.injection.Constant;
import org.spongepowered.asm.mixin.injection.ModifyConstant;

@Pseudo
@Mixin(targets = "com.slopeconnector.client.SlopeConnectorClient", remap = false, priority = 2000)
public abstract class ArcPanelKeyMixin {
    @ModifyConstant(method = "onInitializeClient", constant = @Constant(intValue = 82), remap = false)
    private int slopeconnectorSurface$useG(int original) {
        return 71; // GLFW_KEY_G
    }
}
