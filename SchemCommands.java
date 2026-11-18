package com.schembuilder;

import com.mojang.brigadier.arguments.BoolArgumentType;
import com.mojang.brigadier.arguments.DoubleArgumentType;
import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.arguments.StringArgumentType;
import net.fabricmc.fabric.api.client.command.v2.ClientCommandManager;
import net.fabricmc.fabric.api.client.command.v2.ClientCommandRegistrationCallback;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.client.Minecraft;
import net.minecraft.commands.SharedSuggestionProvider;
import net.minecraft.core.BlockPos;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.stream.Stream;

import static net.fabricmc.fabric.api.client.command.v2.ClientCommandManager.argument;
import static net.fabricmc.fabric.api.client.command.v2.ClientCommandManager.literal;

public final class SchemCommands {
    private SchemCommands() {}

    static Path schematicDir() {
        Path dir = FabricLoader.getInstance().getGameDir().resolve("schematics");
        try {
            Files.createDirectories(dir);
        } catch (IOException ignored) {
            // listing will simply come back empty
        }
        return dir;
    }

    static List<String> listFiles() {
        try (Stream<Path> s = Files.list(schematicDir())) {
            return s.map(p -> p.getFileName().toString())
                    .filter(n -> n.toLowerCase().endsWith(".schem") || n.toLowerCase().endsWith(".litematic"))
                    .sorted()
                    .toList();
        } catch (IOException e) {
            return List.of();
        }
    }

    public static void register(SchematicBuilder builder) {
        ClientCommandRegistrationCallback.EVENT.register((dispatcher, registryAccess) -> dispatcher.register(
                literal("schem")
                        .then(literal("gui").executes(ctx -> {
                            SchemClient.openGuiNextTick();
                            return 1;
                        }))
                        .then(literal("list").executes(ctx -> {
                            List<String> files = listFiles();
                            Chat.say(files.isEmpty()
                                    ? "No .schem files in " + schematicDir()
                                    : String.join(", ", files));
                            return 1;
                        }))
                        .then(literal("load").then(argument("file", StringArgumentType.greedyString())
                                .suggests((c, b) -> SharedSuggestionProvider.suggest(listFiles(), b))
                                .executes(ctx -> {
                                    String name = StringArgumentType.getString(ctx, "file");
                                    Path dir = schematicDir().normalize();
                                    Path p = dir.resolve(name).normalize();
                                    if (!p.startsWith(dir)) {
                                        Chat.say("Schematics must be inside " + dir);
                                        return 0;
                                    }
                                    try {
                                        Chat.say(builder.load(p));
                                    } catch (IOException e) {
                                        Chat.say("Could not load: " + e.getMessage());
                                        return 0;
                                    }
                                    return 1;
                                })))
                        .then(literal("build")
                                .executes(ctx -> {
                                    Minecraft mc = Minecraft.getInstance();
                                    if (mc.player == null) return 0;
                                    Chat.say(builder.start(mc.player.blockPosition()));
                                    return 1;
                                })
                                .then(argument("x", IntegerArgumentType.integer())
                                        .then(argument("y", IntegerArgumentType.integer())
                                                .then(argument("z", IntegerArgumentType.integer())
                                                        .executes(ctx -> {
                                                            BlockPos origin = new BlockPos(
                                                                    IntegerArgumentType.getInteger(ctx, "x"),
                                                                    IntegerArgumentType.getInteger(ctx, "y"),
                                                                    IntegerArgumentType.getInteger(ctx, "z"));
                                                            Chat.say(builder.start(origin));
                                                            return 1;
                                                        })))))
                        .then(literal("stop").executes(ctx -> {
                            builder.stop();
                            Chat.say("Stopped. " + builder.status());
                            return 1;
                        }))
                        .then(literal("status").executes(ctx -> {
                            Chat.say(builder.status());
                            return 1;
                        }))
                        .then(literal("reach").then(argument("blocks", DoubleArgumentType.doubleArg(1.0, Config.EXPLOIT_MAX_REACH))
                                .executes(ctx -> {
                                    double max = Config.exploit ? Config.EXPLOIT_MAX_REACH : Config.LEGIT_MAX_REACH;
                                    double v = Math.min(DoubleArgumentType.getDouble(ctx, "blocks"), max);
                                    Config.reach = v;
                                    Chat.say("Reach set to " + v + (v == max ? " (max for current mode)" : ""));
                                    return 1;
                                })))
                        .then(literal("speed").then(argument("blocksPerTick", IntegerArgumentType.integer(1, 20))
                                .executes(ctx -> {
                                    Config.blocksPerTick = IntegerArgumentType.getInteger(ctx, "blocksPerTick");
                                    Chat.say("Placing up to " + Config.blocksPerTick + " blocks per tick.");
                                    return 1;
                                })))
                        .then(literal("exploit").then(argument("state", BoolArgumentType.bool())
                                .executes(ctx -> {
                                    boolean on = BoolArgumentType.getBool(ctx, "state");
                                    Config.exploit = on;
                                    if (on) {
                                        Config.reach = Math.max(Config.reach, Config.EXPLOIT_DEFAULT_REACH);
                                        Chat.say("Exploit mode ON: no line-of-sight check, reach up to the server's leeway ("
                                                + Config.reach + "). Anti-cheat on public servers will likely flag this.");
                                    } else {
                                        Config.reach = Math.min(Config.reach, Config.LEGIT_MAX_REACH);
                                        Chat.say("Exploit mode OFF: line of sight required, reach " + Config.reach + ".");
                                    }
                                    return 1;
                                })))));
    }
}
