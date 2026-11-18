package com.schembuilder;

import com.mojang.blaze3d.platform.InputConstants;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.keybinding.v1.KeyBindingHelper;
import net.minecraft.client.KeyMapping;
import net.minecraft.resources.Identifier;

public class SchemClient implements ClientModInitializer {
    public static final SchematicBuilder BUILDER = new SchematicBuilder();

    private static volatile boolean openNextTick;
    private static KeyMapping openKey;

    /** Used by /schem gui: the chat screen closes after a command, so open the GUI on the next free tick. */
    public static void openGuiNextTick() {
        openNextTick = true;
    }

    @Override
    public void onInitializeClient() {
        KeyMapping.Category category = KeyMapping.Category.register(
                Identifier.fromNamespaceAndPath("schembuilder", "main"));
        openKey = KeyBindingHelper.registerKeyBinding(new KeyMapping(
                "key.schembuilder.open", InputConstants.Type.KEYSYM, InputConstants.KEY_RSHIFT, category));

        ClientTickEvents.END_CLIENT_TICK.register(client -> {
            BUILDER.tick(client);
            while (openKey.consumeClick()) {
                if (client.screen == null) client.setScreen(new SchemScreen());
            }
            if (openNextTick && client.screen == null) {
                openNextTick = false;
                client.setScreen(new SchemScreen());
            }
        });
        SchemCommands.register(BUILDER);

        // The hologram is loaded by reflection: if that class was left out of the build (or fails to start),
        // everything else keeps working and the screen says the hologram isn't available.
        try {
            Class.forName("com.schembuilder.HologramRenderer").getMethod("register").invoke(null);
            Config.hologramAvailable = true;
        } catch (Throwable t) {
            System.out.println("[Schem] Hologram not available in this build: " + t);
        }
    }
}
