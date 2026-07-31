package com.slopeconnector.surface.mixin;

import com.slopeconnector.hotfix.ArcRibbonBlockEntity;
import com.slopeconnector.hotfix.client.UnifiedSurfaceArcRenderer;
import com.slopeconnector.hotfix.client.UvSafeArcRibbonRenderer;
import net.minecraft.client.render.VertexConsumerProvider;
import net.minecraft.client.util.math.MatrixStack;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Intercepts the 0.9.19 replacement renderer itself.  This avoids two cancellable HEAD injectors
 * competing on the old 0.9.17 renderer and guarantees exactly one material pass per frame.
 */
@Mixin(value = UvSafeArcRibbonRenderer.class, remap = false, priority = 2500)
public abstract class ArcRibbonRendererMixin {
    @Inject(method = "renderReplacement", at = @At("HEAD"), cancellable = true, remap = false)
    private static void slopeconnectorSurface$unifiedRender(ArcRibbonBlockEntity entity, float tickDelta,
                                                             MatrixStack matrices,
                                                             VertexConsumerProvider consumers,
                                                             int light, int overlay,
                                                             CallbackInfo ci) {
        UnifiedSurfaceArcRenderer.renderReplacement(entity, tickDelta, matrices, consumers, light, overlay);
        ci.cancel();
    }
}
