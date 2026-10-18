package com.marleyboss.incursion.command;

import com.marleyboss.incursion.registry.ModItems;
import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.event.RegisterCommandsEvent;

/**
 * Helper commands for testing. {@code /ca} (Core activate) puts everything needed to create and activate the Core into
 * the inventory: the Sarcophagus, a stack of gunpowder, a stack of blaze powder, the Core Rune and a Mythic Potion.
 * Needs operator rights (cheats), like {@code /give}.
 */
public final class CoreCommands {
    private CoreCommands() {
    }

    public static void register() {
        NeoForge.EVENT_BUS.addListener(CoreCommands::onRegisterCommands);
    }

    private static void onRegisterCommands(RegisterCommandsEvent event) {
        CommandDispatcher<CommandSourceStack> dispatcher = event.getDispatcher();
        // Commands are case sensitive, so both spellings work.
        dispatcher.register(literal("ca"));
        dispatcher.register(literal("CA"));
    }

    private static LiteralArgumentBuilder<CommandSourceStack> literal(String name) {
        return Commands.literal(name).requires(source -> source.hasPermission(2)).executes(context -> {
            ServerPlayer player = context.getSource().getPlayerOrException();
            give(player, new ItemStack(ModItems.CORE.get()));
            give(player, new ItemStack(Items.GUNPOWDER, 64));
            give(player, new ItemStack(Items.BLAZE_POWDER, 64));
            give(player, new ItemStack(ModItems.CORE_RUNE.get()));
            give(player, new ItemStack(ModItems.MYTHIC_POTION.get()));
            context.getSource().sendSuccess(() -> Component.literal(
                    "Sarkophag, Schwarzpulver, Lohenstaub, Kernrune und Mythischer Trank erhalten."), false);
            return 1;
        });
    }

    private static void give(ServerPlayer player, ItemStack stack) {
        if (!player.getInventory().add(stack)) {
            player.drop(stack, false);
        }
    }
}
