package com.schembuilder;

import net.minecraft.client.Minecraft;
import net.minecraft.network.chat.Component;

final class Chat {
    private Chat() {}

    static void say(String message) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.player != null) {
            mc.player.displayClientMessage(Component.literal("[Schem] " + message), false);
        }
    }
}
