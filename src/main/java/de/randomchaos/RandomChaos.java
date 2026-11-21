package de.randomchaos;

import com.mojang.brigadier.Command;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import net.fabricmc.api.ModInitializer;
import net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerEntityEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.fabricmc.fabric.api.loot.v3.LootTableEvents;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.permissions.LevelBasedPermissionSet;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;

import java.util.ArrayList;
import java.util.List;
import java.util.Random;

public final class RandomChaos implements ModInitializer {
    private static final Random RNG = new Random();
    private static MinecraftServer activeServer;
    private static long ticks;
    private static int stage;
    private static boolean running = false;
    private static int currentSpeed = 3;

    // These are harmless vanilla items, plus a few rare rewards.
    private static final List<Item> RANDOM_ITEMS = List.of(
            Items.DIRT, Items.COBBLESTONE, Items.OAK_LOG, Items.STICK,
            Items.IRON_INGOT, Items.GOLD_INGOT, Items.DIAMOND,
            Items.EMERALD, Items.BREAD, Items.APPLE, Items.ENDER_PEARL,
            Items.OBSIDIAN, Items.REDSTONE, Items.LAPIS_LAZULI,
            Items.BONE, Items.STRING, Items.COAL, Items.SLIME_BALL,
            Items.GOLDEN_APPLE, Items.ENCHANTED_GOLDEN_APPLE, Items.COPPER_INGOT, Items.WHEAT,
            Items.NETHERITE_SCRAP, Items.FEATHER, Items.SAND
    );
    private static final String[] MOBS = {
            "minecraft:cow", "minecraft:pig", "minecraft:sheep",
            "minecraft:chicken", "minecraft:zombie", "minecraft:skeleton",
            "minecraft:spider", "minecraft:creeper", "minecraft:slime",
            "minecraft:enderman", "minecraft:bee", "minecraft:iron_golem"
    };
    private static final String[] STAGES = {
            "Normaler Start", "Zufällige Block-Drops", "Zufällige Mob-Drops",
            "Zufällige Mob-Größen", "Zufällige Mob-Spawns",
            "Zufälliger Tick-Speed", "Bewegung, Wetter und Tageszeit"
    };

    // Stages occur every five minutes, counted in server game ticks.
    private static final long STAGE_TICKS = 5L * 60 * 20;

    @Override
    public void onInitialize() {
        ServerLifecycleEvents.SERVER_STARTED.register(server -> {
            activeServer = server;
            ticks = 0;
            stage = 0;
            running = false;
            currentSpeed = 3;
        });
        ServerLifecycleEvents.SERVER_STOPPED.register(server -> activeServer = null);
        ServerTickEvents.END_SERVER_TICK.register(RandomChaos::onTick);

        ServerEntityEvents.ENTITY_LOAD.register((entity, world) -> {
            if (stage >= 3 && entity instanceof LivingEntity living && !(entity instanceof ServerPlayer)) {
                randomizeSize(living);
            }
        });

        // A block type always drops the SAME randomly assigned item in this world.
        // The mapping is derived from world seed + loot table ID, so it survives restarts.
        // Mob drops still change each time, but only after stage 2 is unlocked.
        LootTableEvents.MODIFY_DROPS.register((holder, context, drops) -> {
            if (!running || activeServer == null) return;
            String id = holder.unwrapKey().map(key -> key.identifier().toString()).orElse("");
            boolean block = id.startsWith("minecraft:blocks/");
            boolean mob = id.startsWith("minecraft:entities/");
            if (block && stage >= 1) {
                long worldSeed = activeServer.overworld().getSeed();
                long mixedSeed = worldSeed ^ (0x9E3779B97F4A7C15L * id.hashCode());
                Random fixedRandom = new Random(mixedSeed);
                Item item = RANDOM_ITEMS.get(fixedRandom.nextInt(RANDOM_ITEMS.size()));
                int amount = 1 + fixedRandom.nextInt(3);
                drops.clear();
                drops.add(new ItemStack(item, amount));
            } else if (mob && stage >= 2) {
                if (drops.isEmpty()) return;
                int count = Math.min(4, Math.max(1, drops.size()));
                drops.clear();
                for (int i = 0; i < count; i++) {
                    drops.add(new ItemStack(RANDOM_ITEMS.get(RNG.nextInt(RANDOM_ITEMS.size())), 1 + RNG.nextInt(3)));
                }
            }
        });

        CommandRegistrationCallback.EVENT.register((dispatcher, registryAccess, environment) -> {
            dispatcher.register(Commands.literal("challenge")
                .then(Commands.literal("start").requires(Commands.hasPermission(Commands.LEVEL_GAMEMASTERS)).executes(ctx -> {
                    if (running) {
                        ctx.getSource().sendFailure(Component.literal("Challenge läuft bereits. Zeit: " + formatTime(ticks)));
                        return 0;
                    }
                    running = true;
                    announce(ctx.getSource().getServer(), "Challenge gestartet! Viel Erfolg! Timer läuft.");
                    return Command.SINGLE_SUCCESS;
                }))
                .then(Commands.literal("restart").requires(Commands.hasPermission(Commands.LEVEL_GAMEMASTERS)).executes(ctx -> {
                    resetChallenge(ctx.getSource().getServer());
                    return Command.SINGLE_SUCCESS;
                }))
                .then(Commands.literal("timer").executes(ctx -> {
                    ctx.getSource().sendSuccess(() -> Component.literal("Challenge-Zeit: " + formatTime(ticks) + (running ? " (läuft)" : " (angehalten)")), false);
                    return Command.SINGLE_SUCCESS;
                }))
            );
            dispatcher.register(Commands.literal("chaos")
                .then(Commands.literal("status").executes(ctx -> {
                    ctx.getSource().sendSuccess(() -> Component.literal(
                        "Random Chaos: Stufe " + stage + "/6 - " + STAGES[stage]
                        + " | " + (running ? "aktiv" : "pausiert")
                        + " | " + ((STAGE_TICKS - (ticks % STAGE_TICKS)) / 20) + "s bis zur nächsten Stufe"), false);
                    return Command.SINGLE_SUCCESS;
                }))
                .then(Commands.literal("pause").requires(Commands.hasPermission(Commands.LEVEL_GAMEMASTERS)).executes(ctx -> {
                    running = false;
                    ctx.getSource().sendSuccess(() -> Component.literal("Chaos pausiert."), true);
                    return Command.SINGLE_SUCCESS;
                }))
                .then(Commands.literal("resume").requires(Commands.hasPermission(Commands.LEVEL_GAMEMASTERS)).executes(ctx -> {
                    running = true;
                    ctx.getSource().sendSuccess(() -> Component.literal("Chaos läuft wieder."), true);
                    return Command.SINGLE_SUCCESS;
                }))
                .then(Commands.literal("next").requires(Commands.hasPermission(Commands.LEVEL_GAMEMASTERS)).executes(ctx -> {
                    advance(ctx.getSource().getServer());
                    return Command.SINGLE_SUCCESS;
                }))
            );
        });
    }

    private static void onTick(MinecraftServer server) {
        if (!running || server.getPlayerList().getPlayers().isEmpty()) return;
        ticks++;
        if (ticks % STAGE_TICKS == 0) advance(server);
        if (ticks % 20 != 0) return;

        String title = "§bRandomChaos §7| §f" + formatTime(ticks) + " §7| §eStufe " + stage + "/6";
        for (ServerPlayer player : server.getPlayerList().getPlayers()) {
            player.displayClientMessage(Component.literal(title), true);
        }

        if (stage >= 4 && RNG.nextInt(30) == 0) {
            List<ServerPlayer> players = server.getPlayerList().getPlayers();
            ServerPlayer player = players.get(RNG.nextInt(players.size()));
            String mob = MOBS[RNG.nextInt(MOBS.length)];
            execute(server, player.createCommandSourceStack(), "summon " + mob + " ~3 ~ ~3");
        }
        if (stage >= 5 && RNG.nextInt(25) == 0) {
            currentSpeed = new int[]{0, 1, 3, 8, 16, 30, 50}[RNG.nextInt(7)];
            execute(server, server.createCommandSourceStack(), "gamerule randomTickSpeed " + currentSpeed);
            announce(server, "Tick-Speed ist jetzt " + currentSpeed);
        }
        if (stage >= 6 && RNG.nextInt(40) == 0) {
            ServerPlayer player = server.getPlayerList().getPlayers().get(RNG.nextInt(server.getPlayerList().getPlayers().size()));
            String[] effects = {
                "effect give @s minecraft:speed 20 1 true",
                "effect give @s minecraft:jump_boost 20 2 true",
                "effect give @s minecraft:slow_falling 20 0 true",
                "effect give @s minecraft:slowness 10 0 true"
            };
            execute(server, player.createCommandSourceStack(), effects[RNG.nextInt(effects.length)]);
            if (RNG.nextInt(5) == 0) execute(server, server.createCommandSourceStack(), "weather " + (RNG.nextBoolean() ? "rain" : "clear"));
            if (RNG.nextInt(5) == 0) execute(server, server.createCommandSourceStack(), "time set " + RNG.nextInt(24000));
        }
    }

    private static void resetChallenge(MinecraftServer server) {
        running = false;
        ticks = 0;
        stage = 0;
        currentSpeed = 3;

        // The integrated Minecraft/NoRisk server cannot safely replace its open save.
        // Only the player state and RandomChaos are reset here. Terrain remains unchanged.
        var spawn = server.getRespawnData().pos();
        String spawnCoords = spawn.getX() + " " + spawn.getY() + " " + spawn.getZ();
        for (ServerPlayer player : server.getPlayerList().getPlayers()) {
            CommandSourceStack source = player.createCommandSourceStack();
            execute(server, source, "clear @s");
            execute(server, source, "effect clear @s");
            execute(server, source, "experience set @s 0 levels");
            execute(server, source, "experience set @s 0 points");
            execute(server, source, "gamemode survival @s");
            execute(server, source, "execute in minecraft:overworld run tp @s " + spawnCoords);
            execute(server, source, "spawnpoint @s " + spawnCoords);
            player.displayClientMessage(Component.literal("§bRandomChaos §7| §f00:00:00 §7| §eStufe 0/6"), true);
        }
        execute(server, server.createCommandSourceStack(), "gamerule randomTickSpeed 3");
        execute(server, server.createCommandSourceStack(), "weather clear");
        execute(server, server.createCommandSourceStack(), "time set day");
        announce(server, "Challenge zurückgesetzt: Inventare leer, Spieler am Spawn, Timer 00:00:00, Chaos Stufe 0.");
        announce(server, "WICHTIG: Gebaute/abgebaute Blöcke und Welt-Seed bleiben bei NoRisk bestehen. Für eine komplett neue Welt Minecraft beenden und neue Welt erzeugen.");
        announce(server, "Mit /challenge start beginnt die neue Runde.");
    }

    private static String formatTime(long elapsedTicks) {
        long seconds = elapsedTicks / 20;
        return String.format(java.util.Locale.ROOT, "%02d:%02d:%02d", seconds / 3600, (seconds / 60) % 60, seconds % 60);
    }

    private static void advance(MinecraftServer server) {
        if (stage >= 6) {
            announce(server, "Maximales Chaos ist bereits aktiv!");
            return;
        }
        stage++;
        announce(server, "STUFE " + stage + "/6 FREIGESCHALTET: " + STAGES[stage]);
        if (stage == 3) {
            server.getAllLevels().forEach(world -> world.getAllEntities().forEach(entity -> {
                if (entity instanceof LivingEntity living && !(entity instanceof ServerPlayer)) randomizeSize(living);
            }));
        }
    }

    private static void randomizeSize(LivingEntity entity) {
        var scale = entity.getAttribute(Attributes.SCALE);
        if (scale != null) scale.setBaseValue(0.35 + RNG.nextDouble() * 2.65);
    }

    private static void announce(MinecraftServer server, String message) {
        server.getPlayerList().broadcastSystemMessage(Component.literal("[Random Chaos] " + message), false);
    }

    private static void execute(MinecraftServer server, CommandSourceStack source, String command) {
        server.getCommands().performPrefixedCommand(source.withPermission(LevelBasedPermissionSet.GAMEMASTER).withSuppressedOutput(), command);
    }
}
