package com.quzzar.villagelife.events;

import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.quzzar.villagelife.Villagelife;
import com.quzzar.villagelife.village.Village;
import com.quzzar.villagelife.village.VillageAttractiveness;
import com.quzzar.villagelife.village.VillageManager;
import com.quzzar.villagelife.village.buildings.BuildingInfo;
import com.quzzar.villagelife.village.buildings.Buildings;
import com.quzzar.villagelife.village.buildings.SitePreparation;
import com.quzzar.villagelife.village.buildings.StructureGallery;

import net.minecraft.commands.CommandSourceStack;

import net.minecraft.commands.Commands;
import net.minecraft.commands.arguments.coordinates.BlockPosArgument;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Vec3i;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.level.block.Blocks;
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
                        .then(com.quzzar.villagelife.llm.LlmEvents.statusBranch())
                        .then(com.quzzar.villagelife.llm.LlmEvents.loadBranch())
                        .then(Commands.literal("create-village")
                                .then(Commands.argument("pos", BlockPosArgument.blockPos())
                                        .executes(ctx -> {
                                            ServerLevel level = ctx.getSource().getLevel();
                                            BlockPos pos = BlockPosArgument.getLoadedBlockPos(ctx, "pos");
                                            VillageManager.get(level).registerVillage(level, pos);
                                            ctx.getSource().sendSuccess(
                                                    () -> Component.literal("Village created at " + pos.toShortString()), true);
                                            return 1;
                                        }))));
    }

    /**
     * Village diagnostics and structure authoring, mounted under /vldev: useful
     * to whoever is building this mod, meaningless to whoever plays it.
     */
    public static com.mojang.brigadier.builder.LiteralArgumentBuilder<net.minecraft.commands.CommandSourceStack> devBranch() {
        return Commands.literal("village")
                        .then(Commands.literal("attractiveness")
                                .executes(ctx -> reportAttractiveness(ctx.getSource(),
                                        BlockPos.containing(ctx.getSource().getPosition())))
                                .then(Commands.argument("pos", BlockPosArgument.blockPos())
                                        .executes(ctx -> reportAttractiveness(ctx.getSource(),
                                                BlockPosArgument.getBlockPos(ctx, "pos")))))
                        .then(Commands.literal("place")
                                .then(Commands.argument("building", StringArgumentType.word())
                                        .executes(ctx -> placeBuilding(ctx.getSource(),
                                                BlockPos.containing(ctx.getSource().getPosition()),
                                                StringArgumentType.getString(ctx, "building")))
                                        .then(Commands.argument("pos", BlockPosArgument.blockPos())
                                                .executes(ctx -> placeBuilding(ctx.getSource(),
                                                        BlockPosArgument.getBlockPos(ctx, "pos"),
                                                        StringArgumentType.getString(ctx, "building"))))))
                        .then(Commands.literal("start-project")
                                .then(Commands.argument("building", StringArgumentType.word())
                                        .then(Commands.argument("pos", BlockPosArgument.blockPos())
                                                .executes(ctx -> startProject(ctx.getSource(),
                                                        BlockPosArgument.getLoadedBlockPos(ctx, "pos"),
                                                        StringArgumentType.getString(ctx, "building"))))))
                        .then(Commands.literal("capabilities")
                                .executes(ctx -> reportCapabilities(ctx.getSource(),
                                        BlockPos.containing(ctx.getSource().getPosition())))
                                .then(Commands.argument("pos", BlockPosArgument.blockPos())
                                        .executes(ctx -> reportCapabilities(ctx.getSource(),
                                                BlockPosArgument.getBlockPos(ctx, "pos")))))
                        .then(Commands.literal("gallery")
                                .executes(ctx -> buildGallery(ctx.getSource(),
                                        BlockPos.containing(ctx.getSource().getPosition())))
                                .then(Commands.argument("pos", BlockPosArgument.blockPos())
                                        .executes(ctx -> buildGallery(ctx.getSource(),
                                                BlockPosArgument.getLoadedBlockPos(ctx, "pos")))))
                        .then(Commands.literal("score-site")
                                .then(Commands.argument("pos", BlockPosArgument.blockPos())
                                        .then(Commands.argument("sizeX", IntegerArgumentType.integer(1, 64))
                                                .then(Commands.argument("sizeZ", IntegerArgumentType.integer(1, 64))
                                                        .executes(ctx -> reportSiteCost(ctx.getSource(),
                                                                BlockPosArgument.getLoadedBlockPos(ctx, "pos"),
                                                                IntegerArgumentType.getInteger(ctx, "sizeX"),
                                                                IntegerArgumentType.getInteger(ctx, "sizeZ")))))))
                        .then(Commands.literal("save-structure")
                                .then(Commands.argument("from", BlockPosArgument.blockPos())
                                        .then(Commands.argument("to", BlockPosArgument.blockPos())
                                                .then(Commands.argument("name", StringArgumentType.word())
                                                        .executes(ctx -> saveStructure(ctx.getSource(),
                                                                BlockPosArgument.getLoadedBlockPos(ctx, "from"),
                                                                BlockPosArgument.getLoadedBlockPos(ctx, "to"),
                                                                StringArgumentType.getString(ctx, "name")))))));
    }

    /**
     * Captures a world region as a structure file under
     * {@code <world>/generated/villagelife/structures/<name>.nbt}: the
     * headless content-authoring workflow (build by commands, capture, copy
     * into resources). Entities are deliberately not captured.
     */
    private static int saveStructure(CommandSourceStack source, BlockPos from, BlockPos to, String name) {
        ServerLevel level = source.getLevel();
        BlockPos min = new BlockPos(Math.min(from.getX(), to.getX()), Math.min(from.getY(), to.getY()),
                Math.min(from.getZ(), to.getZ()));
        Vec3i size = new Vec3i(Math.abs(from.getX() - to.getX()) + 1, Math.abs(from.getY() - to.getY()) + 1,
                Math.abs(from.getZ() - to.getZ()) + 1);
        ResourceLocation id = ResourceLocation.fromNamespaceAndPath(Villagelife.MODID, name);
        var template = level.getStructureManager().getOrCreate(id);
        template.fillFromWorld(level, min, size, false, Blocks.STRUCTURE_VOID);
        if (!level.getStructureManager().save(id)) {
            source.sendFailure(Component.literal("Could not save structure '" + id + "'."));
            return 0;
        }
        source.sendSuccess(() -> Component.literal(
                "Saved " + size.getX() + "x" + size.getY() + "x" + size.getZ() + " as " + id
                        + " (world generated/ folder; copy into resources to ship it)"), true);
        return 1;
    }

    /**
     * Places every loaded building definition on labelled plinths for review.
     * See {@link StructureGallery}.
     */
    private static int buildGallery(CommandSourceStack source, BlockPos origin) {
        ServerLevel level = source.getLevel();
        int placed = StructureGallery.build(level, origin, new java.util.Random());
        if (placed < 0) {
            source.sendFailure(Component.literal("No building definitions are loaded."));
            return 0;
        }
        int total = com.quzzar.villagelife.village.buildings.Buildings.allBuildings().size();
        source.sendSuccess(() -> Component.literal(
                "Gallery built at " + origin.toShortString() + ": " + placed + " of " + total
                        + " definitions placed. Any skipped are missing their structure file."), true);
        return placed;
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

    /** Drops a building into the nearest village, for testing what it changes. */
    private static int placeBuilding(CommandSourceStack source, BlockPos pos, String building) {
        Village village = VillageManager.get(source.getLevel()).getNearestVillage(pos);
        if (village == null) {
            source.sendFailure(Component.literal("No villages exist yet."));
            return 0;
        }
        if (!village.devPlaceBuilding(building)) {
            source.sendFailure(Component.literal("Could not place '" + building
                    + "': no such definition, or nowhere to put it."));
            return 0;
        }
        source.sendSuccess(() -> Component.literal(
                "Placed " + building + " in '" + village.getName() + "'."), true);
        return 1;
    }

    /**
     * Starts a real construction project at a chosen spot: the builder prepares
     * the ground and raises the structure exactly as it would for a site the
     * village picked itself. A free site always beats a costed one in the
     * village's own search, so this is the only way to watch ground being
     * cleared without waiting for a village to run out of level ground.
     */
    private static int startProject(CommandSourceStack source, BlockPos pos, String building) {
        Village village = VillageManager.get(source.getLevel()).getNearestVillage(pos);
        if (village == null) {
            source.sendFailure(Component.literal("No villages exist yet."));
            return 0;
        }
        BuildingInfo info = Buildings.getByName(building);
        if (info == null) {
            source.sendFailure(Component.literal("No building definition named '" + building + "'."));
            return 0;
        }
        if (!village.startProjectAt(info, pos)) {
            source.sendFailure(Component.literal("'" + village.getName()
                    + "' is already building something."));
            return 0;
        }
        source.sendSuccess(() -> Component.literal(
                "'" + village.getName() + "' has started " + building + " at " + pos.toShortString()), true);
        return 1;
    }

    /** What the nearest village can currently do, and what each building contributes. */
    private static int reportCapabilities(CommandSourceStack source, BlockPos pos) {
        Village village = VillageManager.get(source.getLevel()).getNearestVillage(pos);
        if (village == null) {
            source.sendFailure(Component.literal("No villages exist yet."));
            return 0;
        }
        java.util.Set<String> capabilities = village.getCapabilities();
        StringBuilder report = new StringBuilder();
        report.append("Village '").append(village.getName()).append("' can do: ")
                .append(capabilities.isEmpty() ? "nothing yet" : String.join(", ",
                        capabilities.stream().sorted().toList()));
        for (var building : village.getBuildings()) {
            var info = building.getInfo();
            if (info == null || (info.getGrants().isEmpty() && info.getConditionalGrants().isEmpty())) {
                continue;
            }
            report.append("\n  ").append(info.getName()).append(": ")
                    .append(String.join(", ", info.getGrants()));
            for (var grant : info.getConditionalGrants()) {
                boolean held = capabilities.contains(grant.capability());
                report.append("\n    ").append(grant.capability())
                        .append(held ? " (granted)" : " (withheld)")
                        .append(" needs ")
                        .append(grant.requiresCapability().isEmpty() ? "" : grant.requiresCapability().toString())
                        .append(grant.requiresSupply().isEmpty() ? "" : grant.requiresSupply().stream()
                                .map(item -> net.minecraft.core.registries.BuiltInRegistries.ITEM.getKey(item).getPath())
                                .toList().toString());
            }
        }
        source.sendSuccess(() -> Component.literal(report.toString()), false);
        return capabilities.size();
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
