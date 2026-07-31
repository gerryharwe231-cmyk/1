package com.slopeconnector.surface.mixin;

import com.slopeconnector.surface.RefinedConnectedGenerator;
import net.minecraft.block.BlockState;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.util.math.BlockPos;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import java.lang.reflect.Constructor;

@Mixin(targets = "com.slopeconnector.connected.ConnectedArcGenerator", remap = false, priority = 2000)
public abstract class ConnectedArcGeneratorMixin {
    @Inject(method = "generate", at = @At("HEAD"), cancellable = true, remap = false)
    private static void slopeconnectorSurface$replaceGenerator(ServerWorld world, BlockPos templatePos,
                                                               BlockState templateState, BlockPos startPos,
                                                               BlockPos controlPos, BlockPos endPos,
                                                               boolean threePoint, int side,
                                                               CallbackInfoReturnable<Object> cir) {
        RefinedConnectedGenerator.Result result = RefinedConnectedGenerator.generate(
                world, templatePos, templateState, startPos, controlPos, endPos, threePoint, side);
        cir.setReturnValue(originalResult(result));
    }

    private static Object originalResult(RefinedConnectedGenerator.Result result) {
        try {
            Class<?> type = Class.forName("com.slopeconnector.connected.ConnectedArcGenerator$Result");
            Constructor<?> constructor = type.getDeclaredConstructor(int.class, int.class, String.class);
            constructor.setAccessible(true);
            return constructor.newInstance(result.placed(), result.sections(), result.error());
        } catch (ReflectiveOperationException error) {
            throw new RuntimeException("Cannot construct ConnectedArcGenerator.Result", error);
        }
    }
}
