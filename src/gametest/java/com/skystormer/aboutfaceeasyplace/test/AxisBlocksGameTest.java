package com.skystormer.aboutfaceeasyplace.test;

import com.skystormer.aboutfaceeasyplace.Aligner;
import com.skystormer.aboutfaceeasyplace.Config;
import fi.dy.masa.litematica.config.Configs;
import fi.dy.masa.litematica.data.DataManager;
import fi.dy.masa.litematica.schematic.LitematicaSchematic;
import fi.dy.masa.litematica.schematic.container.LitematicaBlockStateContainer;
import fi.dy.masa.litematica.schematic.placement.SchematicPlacement;
import fi.dy.masa.litematica.selection.AreaSelection;
import fi.dy.masa.litematica.selection.Box;
import fi.dy.masa.litematica.util.EasyPlaceUtils;
import fi.dy.masa.litematica.util.WorldUtils;
import net.fabricmc.fabric.api.client.gametest.v1.FabricClientGameTest;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestDedicatedServerContext;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestDedicatedServerConnection;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestServerContext;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestSingleplayerContext;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.level.material.FluidState;
import net.minecraft.world.level.material.Fluids;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.Vec3;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.List;

/**
 * Blocks that lie along an axis: logs, the six-sided wood blocks, stripped ones and so on. Asks the
 * planner about every such block in the game, along each axis and from a click on each face, and then
 * places a grid of them with both of Litematica's Easy Place paths.
 */
public class AxisBlocksGameTest implements FabricClientGameTest {

    private static final Logger LOG = LoggerFactory.getLogger("AxisBlocksGameTest");
    private static final int Y = 100;
    private static final Direction.Axis[] AXES = Direction.Axis.values();

    private static final Block[] PLACED = {
            Blocks.CHERRY_WOOD, Blocks.STRIPPED_CHERRY_WOOD, Blocks.OAK_WOOD, Blocks.STRIPPED_OAK_WOOD,
            Blocks.PALE_OAK_WOOD, Blocks.CHERRY_WOOD, Blocks.CRIMSON_HYPHAE, Blocks.STRIPPED_WARPED_HYPHAE,
            Blocks.CHERRY_LOG, Blocks.OAK_LOG, Blocks.STRIPPED_OAK_LOG,
    };

    @Override
    public void runTest(ClientGameTestContext context) {
        try (TestSingleplayerContext world = context.worldBuilder().create()) {
            world.getConnection().waitForChunksRender();
            check(context, world.getServer());
        }
        // A real server is the path that matters: there this mod is working out placements for a
        // server that checks every click.
        java.util.Properties properties = new java.util.Properties();
        properties.setProperty("spawn-protection", "0");
        try (TestDedicatedServerContext server = context.worldBuilder().createServer(properties);
             TestDedicatedServerConnection connection = server.connect()) {
            connection.waitForChunksRender();
            check(context, server);
        }
    }

    private static void check(ClientGameTestContext context, TestServerContext server) {
        server.runCommand("gamemode survival @a");
        int depth = PLACED.length * 4;
        server.runCommand("fill -4 " + (Y - 1) + " -4 16 " + (Y - 1) + " " + depth + " stone");
        server.runCommand("fill -4 " + Y + " -4 16 " + (Y + 4) + " " + depth + " air");
        server.runCommand("tp @a 5 " + Y + " -3 0 30");
        context.waitTicks(20);
        context.runOnClient(c -> Config.setVerboseLogging(true));

        List<String> failures = new ArrayList<>();
        failures.addAll(context.computeOnClient(c -> planEveryAxisBlock(c.player)));

        // The grid: a line of three axes for each block, with the older path on the left and the
        // newer one on the right, and room in front of each to stand.
        context.runOnClient(c -> {
            BlockPos origin = new BlockPos(0, Y, 0);
            AreaSelection area = new AreaSelection();
            area.setName("axis test");
            area.addSubRegionBox(new Box(origin, new BlockPos(14, Y, depth), "grid"), false);
            LitematicaSchematic schematic = LitematicaSchematic.createEmptySchematic(area, "test");
            LitematicaBlockStateContainer grid = schematic.getSubRegionContainer("grid");
            for (int line = 0; line < PLACED.length; line++) {
                for (int a = 0; a < AXES.length; a++) {
                    BlockState state = PLACED[line].defaultBlockState().setValue(BlockStateProperties.AXIS, AXES[a]);
                    grid.set(a * 2, 0, line * 4, state);
                    grid.set(8 + a * 2, 0, line * 4, state);
                }
            }
            DataManager.getSchematicPlacementManager().addSchematicPlacement(
                    SchematicPlacement.createFor(schematic, origin, "axis test", true, true), false);
        });
        context.waitTicks(40);

        server.runCommand("clear @a");
        for (Block block : PLACED) {
            server.runCommand("give @a " + BuiltInRegistries.BLOCK.getKey(block) + " 16");
        }
        context.waitTicks(5);

        for (int line = 0; line < PLACED.length; line++) {
            for (int a = 0; a < AXES.length; a++) {
                place(context, server, a * 2, line * 4, false);
                place(context, server, 8 + a * 2, line * 4, true);
            }
        }

        for (int line = 0; line < PLACED.length; line++) {
            Block block = PLACED[line];
            for (int a = 0; a < AXES.length; a++) {
                for (int x : new int[]{a * 2, 8 + a * 2}) {
                    BlockPos pos = new BlockPos(x, Y, line * 4);
                    BlockState wanted = block.defaultBlockState().setValue(BlockStateProperties.AXIS, AXES[a]);
                    BlockState client = context.computeOnClient(c -> c.level.getBlockState(pos));
                    BlockState real = server.computeOnServer(s -> s.overworld().getBlockState(pos));
                    String path = x < 8 ? "older" : "newer";
                    if (real != wanted || client != wanted) LOG.info("{} path: wanted {} -> client {} server {}", path, wanted, client, real);
                    if (real != wanted || client != wanted) {
                        failures.add(path + " path: wanted " + wanted + ", client " + client + ", server " + real);
                    }
                }
            }
        }

        failures.forEach(f -> LOG.error("FAILED: {}", f));
        if (!failures.isEmpty()) throw new AssertionError(failures.size() + " axis block failures, first: " + failures.get(0));
        LOG.info("ALL CHECKS PASSED");

    }

    /** What the planner answers for every block with an axis, along each axis, from each face. */
    private static List<String> planEveryAxisBlock(net.minecraft.client.player.LocalPlayer player) {
        List<String> failures = new ArrayList<>();
        BlockPos target = player.blockPosition().south(2).above(1);
        Aligner.Rotation real = new Aligner.Rotation(player.getYRot(), player.getXRot());
        int blocks = 0;
        long started = System.nanoTime();
        for (Block block : BuiltInRegistries.BLOCK) {
            if (!(block.asItem() instanceof BlockItem) || !block.defaultBlockState().hasProperty(BlockStateProperties.AXIS)) continue;
            blocks++;
            ItemStack stack = new ItemStack(block);
            for (Direction.Axis axis : AXES) {
                BlockState wanted = block.defaultBlockState().setValue(BlockStateProperties.AXIS, axis);
                BlockGetter schematic = only(target, wanted);
                for (Direction face : Direction.values()) {
                    Vec3 point = Vec3.atCenterOf(target).add(face.getStepX() * 0.5, face.getStepY() * 0.5, face.getStepZ() * 0.5);
                    Aligner.Outcome outcome = Aligner.INSTANCE.plan(player, InteractionHand.MAIN_HAND, stack,
                            new BlockHitResult(point, face, target, false), schematic, real, List.of(real.getYaw()));
                    boolean right = outcome instanceof Aligner.Outcome.Aligned aligned && aligned.getExpected() == wanted;
                    if (!right) failures.add("plan for " + wanted + " clicked on " + face + ": " + outcome);
                }
            }
        }
        LOG.info("planned {} blocks with an axis in {} ms, {} wrong", blocks, (System.nanoTime() - started) / 1_000_000, failures.size());
        return failures;
    }

    /** A schematic with nothing in it but [state] at [at]. */
    private static BlockGetter only(BlockPos at, BlockState state) {
        return new BlockGetter() {
            @Override
            public BlockState getBlockState(BlockPos pos) {
                return pos.equals(at) ? state : Blocks.AIR.defaultBlockState();
            }

            @Override
            public BlockEntity getBlockEntity(BlockPos pos) {
                return null;
            }

            @Override
            public FluidState getFluidState(BlockPos pos) {
                return Fluids.EMPTY.defaultFluidState();
            }

            @Override
            public int getHeight() {
                return 384;
            }

            @Override
            public int getMinY() {
                return -64;
            }
        };
    }

    /** Stands in front of x, z, looks at it and asks Litematica's Easy Place to place there. */
    static void place(ClientGameTestContext context, TestServerContext server, int x, int z, boolean postRewrite) {
        context.runOnClient(c -> {
            Configs.Generic.EASY_PLACE_MODE.setBooleanValue(true);
            Configs.Generic.EASY_PLACE_POST_REWRITE.setBooleanValue(postRewrite);
        });
        server.runCommand("tp @a " + (x + 0.5) + " " + Y + " " + (z - 2.5) + " 0 30");
        context.waitTicks(10);
        context.runOnClient(c -> {
            c.player.setYRot(0);
            c.player.setXRot(30);
            if (postRewrite) {
                EasyPlaceUtils.setHandling(false);
                EasyPlaceUtils.handleEasyPlaceWithMessage();
            } else {
                WorldUtils.handleEasyPlace(c);
            }
        });
        context.waitTicks(10);
    }
}
