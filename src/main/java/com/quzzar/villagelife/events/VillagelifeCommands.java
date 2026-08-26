package com.quzzar.villagelife.events;

import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.quzzar.villagelife.Villagelife;
import com.quzzar.villagelife.village.Village;
import com.quzzar.villagelife.village.VillageAttractiveness;
import com.quzzar.villagelife.village.VillageManager;
import com.quzzar.villagelife.village.buildings.SitePreparation;

import net.minecraft.commands.CommandSourceStack;

import net.minecraft.commands.Commands;
import net.minecraft.commands.arguments.coordinates.BlockPosArgument;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.levelgen.structure.BoundingBox;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.RegisterCommandsEvent;

@EventBusSubscriber(modid = Villagelife.MODID)
public class VillagelifeCommands {

    @SubscribeEvent
    public static void onRegisterCommands(RegisterCommandsEvent event) {
        event.getDispatcher().register(
                Commands.literal("villagelife")
                        .requires(source -> source.hasPermission(2))
                        .then(Commands.literal("create-village")
                                .then(Commands.argument("pos", BlockPosArgument.blockPos())
                                        .executes(ctx -> {
                                            ServerLevel level = ctx.getSource().getLevel();
                                            BlockPos pos = BlockPosArgument.getLoadedBlockPos(ctx, "pos");
                                            VillageManager.get(level).registerVillage(level, pos);
                                            ctx.getSource().sendSuccess(
                                                    () -> Component.literal("Village created at " + pos.toShortString()), true);
                                            return 1;
                                        })))
                        .then(Commands.literal("attractiveness")
                                .executes(ctx -> reportAttractiveness(ctx.getSource(),
                                        BlockPos.containing(ctx.getSource().getPosition())))
                                .then(Commands.argument("pos", BlockPosArgument.blockPos())
                                        .executes(ctx -> reportAttractiveness(ctx.getSource(),
                                                BlockPosArgument.getBlockPos(ctx, "pos")))))
                        .then(Commands.literal("score-site")
                                .then(Commands.argument("pos", BlockPosArgument.blockPos())
                                        .then(Commands.argument("sizeX", IntegerArgumentType.integer(1, 64))
                                                .then(Commands.argument("sizeZ", IntegerArgumentType.integer(1, 64))
                                                        .executes(ctx -> reportSiteCost(ctx.getSource(),
                                                                BlockPosArgument.getLoadedBlockPos(ctx, "pos"),
                                                                IntegerArgumentType.getInteger(ctx, "sizeX"),
                                                                IntegerArgumentType.getInteger(ctx, "sizeZ"))))))));
    }

    private static int reportSiteCost(CommandSourceStack source, BlockPos pos, int sizeX, int sizeZ) {
        Village village = VillageManager.get(source.getLevel()).getNearestVillage(pos);
        if (village == null) {
            source.sendFailure(Component.literal("No villages exist yet."));
            return 0;
        }
        BoundingBox bounds = new BoundingBox(0, 0, 0, sizeX - 1, 0, sizeZ - 1);
        SitePreparation.SiteCost cost = SitePreparation.score(source.getLevel(), village, pos, bounds);
        source.sendSuccess(() -> Component.literal(
                String.format("Site %s (%dx%d): %s", pos.toShortString(), sizeX, sizeZ, cost.describe())), false);
        return cost.impossible() ? 0 : Math.max(1, cost.blocksMoved());
    }

    private static int reportAttractiveness(CommandSourceStack source, BlockPos pos) {
        Village village = VillageManager.get(source.getLevel()).getNearestVillage(pos);
        if (village == null) {
            source.sendFailure(Component.literal("No villages exist yet."));
            return 0;
        }
        VillageAttractiveness report = village.computeAttractiveness();
        source.sendSuccess(() -> Component.literal(report.describe(village.getName())), false);
        return (int) report.total();
    }
}
