package com.slopeconnector.surface.dimensions;

import net.minecraft.client.MinecraftClient;

/** Client-side value shown by the G panel. The server remains authoritative through commands. */
public final class ArcDimensionClientState {
    private static int upDown = 1;

    private ArcDimensionClientState() {}

    public static int upDown() {
        return upDown;
    }

    public static void changeUpDown(int delta) {
        upDown = ArcDimensionSettings.clampUpDown(upDown + delta);
        send("slopeconnector udwidth " + upDown);
    }

    public static String label() {
        return "上下厚度：" + upDown;
    }

    private static void send(String command) {
        MinecraftClient client = MinecraftClient.getInstance();
        if (client.player != null && client.player.networkHandler != null) {
            client.player.networkHandler.sendChatCommand(command);
        }
    }
}
