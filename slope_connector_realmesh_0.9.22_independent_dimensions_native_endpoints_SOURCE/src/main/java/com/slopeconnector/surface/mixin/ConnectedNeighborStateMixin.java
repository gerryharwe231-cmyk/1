package com.slopeconnector.surface.mixin;

import com.slopeconnector.surface.ConnectionStateHelper;
import net.minecraft.block.AbstractBlock;
import net.minecraft.block.BlockState;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Direction;
import net.minecraft.world.WorldAccess;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/** Makes vanilla and Conquest-style endpoint blocks react to both the custom arc and later fences. */
@Mixin(AbstractBlock.AbstractBlockState.class)
public abstract class ConnectedNeighborStateMixin {
    @Inject(method = "getStateForNeighborUpdate", at = @At("RETURN"), cancellable = true)
    private void slopeconnectorSurface$connectedEndpointUpdate(Direction direction,
                                                                BlockState neighborState,
                                                                WorldAccess world,
                                                                BlockPos pos,
                                                                BlockPos neighborPos,
                                                                CallbackInfoReturnable<BlockState> cir) {
        if (direction.getAxis().isVertical()) return;
        BlockState current = cir.getReturnValue();
        if (!ConnectionStateHelper.shouldForce(current, neighborState)) return;
        BlockState connected = ConnectionStateHelper.forceConnection(current, direction);
        if (!connected.equals(current)) cir.setReturnValue(connected);
    }
}
