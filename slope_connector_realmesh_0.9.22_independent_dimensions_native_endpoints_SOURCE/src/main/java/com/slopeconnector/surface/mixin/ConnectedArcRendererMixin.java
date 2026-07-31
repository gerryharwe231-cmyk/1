package com.slopeconnector.surface.mixin;

import com.slopeconnector.connected.ConnectedArcBlockEntity;
import com.slopeconnector.connected.client.ConnectedArcRenderer;
import com.slopeconnector.surface.client.RefinedConnectedArcRenderer;
import net.minecraft.client.render.VertexConsumerProvider;
import net.minecraft.client.util.math.MatrixStack;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(value = ConnectedArcRenderer.class, remap = false, priority = 2000)
public abstract class ConnectedArcRendererMixin {
    @Inject(method = "render", at = @At("HEAD"), cancellable = true, remap = false)
    private void slopeconnectorSurface$render(ConnectedArcBlockEntity entity, float tickDelta,
                                              MatrixStack matrices, VertexConsumerProvider consumers,
                                              int light, int overlay, CallbackInfo ci) {
        RefinedConnectedArcRenderer.renderReplacement(entity, tickDelta, matrices, consumers, light, overlay);
        ci.cancel();
    }
}
