package com.slopeconnector.surface.mixin;

import com.slopeconnector.hotfix.ArcHotfixMod;
import com.slopeconnector.hotfix.ArcRibbonBlockEntity;
import com.slopeconnector.surface.dimensions.ArcDimensionSettings;
import net.minecraft.block.BlockState;
import net.minecraft.block.Blocks;
import net.minecraft.block.entity.BlockEntity;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Vec3d;
import net.minecraft.world.World;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Coerce;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import java.util.ArrayList;
import java.util.List;

/**
 * Applies the independent up/down thickness inside the original prism constructor.  Because the
 * transformed vertex array is consumed by rendering, collision and automatic trimming, all three
 * systems receive the exact same geometry.
 */
@Pseudo
@Mixin(targets = "com.slopeconnector.hotfix.ArcRibbonGenerator", remap = false, priority = 2600)
public abstract class ArcRibbonDimensionMixin {
    private static final ThreadLocal<Double> SLOPECONNECTOR_SURFACE$UP_DOWN_SCALE =
            ThreadLocal.withInitial(() -> 1.0);

    @Inject(method = "generate", at = @At("HEAD"), remap = false)
    private static void slopeconnectorSurface$beginDimensions(World world, BlockPos startBlock,
                                                               BlockPos controlBlock, BlockPos endBlock,
                                                               BlockState source, @Coerce Object settings,
                                                               CallbackInfoReturnable<?> cir) {
        SLOPECONNECTOR_SURFACE$UP_DOWN_SCALE.set(
                (double) ArcDimensionSettings.upDownForSettings(settings));
    }

    @Inject(method = "prism", at = @At("RETURN"), cancellable = true, remap = false)
    private static void slopeconnectorSurface$scaleUpDown(Vec3d c0, Vec3d c1,
                                                           Vec3d radial0, Vec3d radial1,
                                                           Vec3d width,
                                                           double width0, double width1,
                                                           double normal0, double normal1,
                                                           CallbackInfoReturnable<float[]> cir) {
        double scale = SLOPECONNECTOR_SURFACE$UP_DOWN_SCALE.get();
        if (Math.abs(scale - 1.0) < 1.0E-8) return;
        float[] original = cir.getReturnValue();
        if (original == null || original.length != 24) return;
        float[] scaled = original.clone();
        for (int vertex = 0; vertex < 8; vertex++) {
            Vec3d center = vertex < 4 ? c0 : c1;
            Vec3d radial = vertex < 4 ? radial0 : radial1;
            if (radial.lengthSquared() < 1.0E-12) continue;
            radial = radial.normalize();
            int index = vertex * 3;
            Vec3d point = new Vec3d(scaled[index], scaled[index + 1], scaled[index + 2]);
            Vec3d relative = point.subtract(center);
            double component = relative.dotProduct(radial);
            Vec3d transformed = point.add(radial.multiply(component * (scale - 1.0)));
            scaled[index] = (float) transformed.x;
            scaled[index + 1] = (float) transformed.y;
            scaled[index + 2] = (float) transformed.z;
        }
        cir.setReturnValue(scaled);
    }

    @Inject(method = "generate", at = @At("RETURN"), remap = false)
    private static void slopeconnectorSurface$finishDimensions(World world, BlockPos startBlock,
                                                                BlockPos controlBlock, BlockPos endBlock,
                                                                BlockState source, @Coerce Object settings,
                                                                CallbackInfoReturnable<?> cir) {
        try {
            removeEndpointOverlayHolders(world, startBlock, source);
            removeEndpointOverlayHolders(world, endBlock, source);
        } finally {
            SLOPECONNECTOR_SURFACE$UP_DOWN_SCALE.remove();
        }
    }

    /** Endpoint blocks now render natively, so obsolete overlay-only holders must be removed. */
    private static void removeEndpointOverlayHolders(World world, BlockPos endpoint, BlockState source) {
        for (int dx = -2; dx <= 2; dx++) {
            for (int dy = -2; dy <= 2; dy++) {
                for (int dz = -2; dz <= 2; dz++) {
                    BlockPos pos = endpoint.add(dx, dy, dz);
                    BlockEntity blockEntity = world.getBlockEntity(pos);
                    if (!(blockEntity instanceof ArcRibbonBlockEntity ribbon)) continue;
                    if (!ribbon.getSourceState().equals(source) || ribbon.getSurfaces().isEmpty()) continue;
                    List<ArcRibbonBlockEntity.Prism> prisms = new ArrayList<>(ribbon.getPrisms());
                    if (prisms.isEmpty() && world.getBlockState(pos).getBlock() == ArcHotfixMod.ARC_RIBBON) {
                        world.setBlockState(pos, Blocks.AIR.getDefaultState(), 3);
                    } else {
                        ribbon.setData(ribbon.getSourceState(), prisms, List.of());
                        world.updateListeners(pos, world.getBlockState(pos), world.getBlockState(pos), 3);
                    }
                }
            }
        }
    }
}
