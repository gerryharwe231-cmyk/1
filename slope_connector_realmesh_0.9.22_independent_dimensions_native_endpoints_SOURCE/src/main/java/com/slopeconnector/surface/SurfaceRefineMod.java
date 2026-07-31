package com.slopeconnector.surface;

import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.slopeconnector.surface.dimensions.ArcDimensionSettings;
import net.fabricmc.api.ModInitializer;
import net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback;
import net.minecraft.server.command.CommandManager;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.text.Text;

/** Common registration for the surface renderer and independent arc dimensions. */
public final class SurfaceRefineMod implements ModInitializer {
    public static final String MOD_ID = "slopeconnector_surface_refine";

    @Override
    public void onInitialize() {
        CommandRegistrationCallback.EVENT.register((dispatcher, registryAccess, environment) -> dispatcher.register(
                CommandManager.literal("slopeconnector")
                        .then(CommandManager.literal("lrwidth")
                                .then(CommandManager.argument("blocks", IntegerArgumentType.integer(
                                                ArcDimensionSettings.MIN_SIZE,
                                                ArcDimensionSettings.MAX_LEFT_RIGHT))
                                        .executes(context -> {
                                            ServerPlayerEntity player = context.getSource().getPlayerOrThrow();
                                            int value = ArcDimensionSettings.setLeftRight(player,
                                                    IntegerArgumentType.getInteger(context, "blocks"));
                                            context.getSource().sendFeedback(
                                                    () -> Text.literal("弧带左右宽度：" + value + " 格"), false);
                                            return value;
                                        })))
                        .then(CommandManager.literal("udwidth")
                                .then(CommandManager.argument("blocks", IntegerArgumentType.integer(
                                                ArcDimensionSettings.MIN_SIZE,
                                                ArcDimensionSettings.MAX_UP_DOWN))
                                        .executes(context -> {
                                            ServerPlayerEntity player = context.getSource().getPlayerOrThrow();
                                            int value = ArcDimensionSettings.setUpDown(player,
                                                    IntegerArgumentType.getInteger(context, "blocks"));
                                            context.getSource().sendFeedback(
                                                    () -> Text.literal("弧带上下厚度：" + value + " 格"), false);
                                            return value;
                                        })))
                        .then(CommandManager.literal("dimensions")
                                .executes(context -> {
                                    ServerPlayerEntity player = context.getSource().getPlayerOrThrow();
                                    int lr = ArcDimensionSettings.leftRight(player);
                                    int ud = ArcDimensionSettings.upDown(player);
                                    context.getSource().sendFeedback(
                                            () -> Text.literal("弧带尺寸：左右 " + lr + " 格，上下 " + ud + " 格"), false);
                                    return 1;
                                }))
        ));
    }
}
