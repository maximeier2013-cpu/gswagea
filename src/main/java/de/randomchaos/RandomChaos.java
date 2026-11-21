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
    private static boolean running = true;
    private static int currentSpeed = 3;

    // These are harmless vanilla items, plus a few rare rewards.
    private static final List<Item> RANDOM_ITEMS = List.of(
            Items.DIRT, Items.COBBLESTONE, Items.OAK_LOG, Items.STICK,
            Items.IRON_INGOT, Items.GOLD_INGOT, Items.DIAMOND,
            Items.EMERALD, Items.BREAD, Items.APPLE, Items.ENDER_PEARL,
            Items.OBSIDIAN, Items.REDSTONE, Items.LAPIS_LAZULI,
            Items.BONE, Items.STRING, Items.COAL, Items.SLIME_BALL,
            Items.GOLDEN_APPLE, Items.COPPER_INGOT, Items.WHEAT,
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
            running = true;
            currentSpeed = 3;
        });
        ServerLifecycleEvents.SERVER_STOPPED.register(server -> activeServer = null);
        ServerTickEvents.END_SERVER_TICK.register(RandomChaos::onTick);

        ServerEntityEvents.ENTITY_LOAD.register((entity, world) -> {
            if (stage >= 3 && entity instanceof LivingEntity living && !(entity instanceof ServerPlayer)) {
                randomizeSize(living);
            }
        });

        // Only actual block/entity loot tables, not chests or other loot sources.
        LootTableEvents.MODIFY_DROPS.register((holder, context, drops) -> {
            if (!running || activeServer == null || drops.isEmpty()) return;
            String path = holder.unwrapKey().map(key -> key.identifier().getPath()).orElse("");
            boolean block = path.startsWith("blocks/");
            boolean mob = path.startsWith("entities/");
            if ((!block || stage < 1) && (!mob || stage < 2)) return;
            // Only change most drops so players can sometimes obtain vanilla resources.
            if (RNG.nextInt(100) >= 85) return;
            int count = Math.min(4, Math.max(1, drops.size()));
            drops.clear();
            for (int i = 0; i < count; i++) {
                drops.add(new ItemStack(RANDOM_ITEMS.get(RNG.nextInt(RANDOM_ITEMS.size())), 1 + RNG.nextInt(3)));
            }
        });

        CommandRegistrationCallback.EVENT.register((dispatcher, registryAccess, environment) -> {
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
