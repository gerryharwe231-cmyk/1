package com.slopeconnector.surface.mixin;

import com.slopeconnector.connected.ConnectedArcMod;
import net.minecraft.client.MinecraftClient;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/** Lets the same G panel open while the dedicated connected-profile wand is held. */
@Pseudo
@Mixin(targets = "com.slopeconnector.client.ArcWandHud", remap = false, priority = 2000)
public abstract class ArcWandHoldingMixin {
    @Inject(method = "isHoldingArcWand", at = @At("RETURN"), cancellable = true, remap = false)
    private static void slopeconnectorSurface$includeConnectedWand(
            MinecraftClient client, CallbackInfoReturnable<Boolean> cir) {
        if (cir.getReturnValueZ() || client.player == null) return;
        if (client.player.getMainHandStack().isOf(ConnectedArcMod.CONNECTED_ARC_WAND)
                || client.player.getOffHandStack().isOf(ConnectedArcMod.CONNECTED_ARC_WAND)) {
            cir.setReturnValue(true);
        }
    }
}
