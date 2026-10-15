package com.skystormer.aboutfaceeasyplace.test;

import com.skystormer.aboutfaceeasyplace.Substitutions;
import fi.dy.masa.litematica.config.Configs;
import fi.dy.masa.litematica.data.DataManager;
import fi.dy.masa.litematica.schematic.LitematicaSchematic;
import fi.dy.masa.litematica.schematic.container.LitematicaBlockStateContainer;
import fi.dy.masa.litematica.schematic.placement.SchematicPlacement;
import fi.dy.masa.litematica.schematic.verifier.SchematicVerifier;
import fi.dy.masa.litematica.selection.AreaSelection;
import fi.dy.masa.litematica.selection.Box;
import fi.dy.masa.litematica.util.EasyPlaceUtils;
import fi.dy.masa.litematica.util.WorldUtils;
import fi.dy.masa.litematica.world.SchematicWorldHandler;
import net.fabricmc.fabric.api.client.gametest.v1.FabricClientGameTest;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestSingleplayerContext;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.StairBlock;
import net.minecraft.world.level.block.state.BlockState;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * Block substitution in a test world: a row of stone bricks and stone brick stairs as a Litematica
 * placement, deepslate swaps for both, and checks that the hooks are in Litematica, that the
 * verifier counts the swaps as right and nothing else, and that both Easy Place paths put a swap
 * down when that is all you have.
 */
public class SubstitutionGameTest implements FabricClientGameTest {

    private static final Logger LOG = LoggerFactory.getLogger("SubstitutionGameTest");
    private static final int Y = 100;

    @Override
    public void runTest(ClientGameTestContext context) {
        try (TestSingleplayerContext world = context.worldBuilder().create()) {
            world.getConnection().waitForChunksRender();
            var server = world.getServer();
            server.runCommand("gamemode survival @a");
            server.runCommand("fill -4 " + (Y - 1) + " -4 10 " + (Y - 1) + " 4 stone");
            server.runCommand("fill -4 " + Y + " -4 10 " + (Y + 4) + " 4 air");

            // The hooks are woven into Litematica's classes once those load.
            String missing = context.computeOnClient(c -> {
                List<String> out = new ArrayList<>();
                hook(out, "fi.dy.masa.litematica.schematic.verifier.SchematicVerifier", "countSwapsAsRight");
                hook(out, "fi.dy.masa.litematica.render.schematic.ChunkRendererSchematicVbo", "swapsLookFinished");
                hook(out, "fi.dy.masa.litematica.util.WorldUtils", "placeSwaps");
                hook(out, "fi.dy.masa.litematica.util.EasyPlaceUtils", "placeSwaps");
                return out.isEmpty() ? null : String.join(", ", out);
            });
            LOG.info("hooks missing: {}", missing == null ? "none" : missing);
            if (missing != null) throw new AssertionError("hooks not applied: " + missing);

            // The schematic: x 0..5 at Y; 0, 2, 4, 5 stone bricks, 1 and 3 stairs facing east.
            BlockState stair = Blocks.STONE_BRICK_STAIRS.defaultBlockState().setValue(StairBlock.FACING, Direction.EAST);
            context.runOnClient(c -> {
                BlockPos origin = new BlockPos(0, Y, 0);
                AreaSelection area = new AreaSelection();
                area.setName("swap test");
                area.addSubRegionBox(new Box(origin, new BlockPos(5, Y, 0), "row"), false);
                LitematicaSchematic schematic = LitematicaSchematic.createEmptySchematic(area, "test");
                LitematicaBlockStateContainer row = schematic.getSubRegionContainer("row");
                for (int x = 0; x <= 5; x++) {
                    row.set(x, 0, 0, x == 1 || x == 3 ? stair : Blocks.STONE_BRICKS.defaultBlockState());
                }
                SchematicPlacement placement = SchematicPlacement.createFor(schematic, origin, "swap test", true, true);
                DataManager.getSchematicPlacementManager().addSchematicPlacement(placement, false);

                Substitutions.INSTANCE.getSwaps().clear();
                // One swap as a group: either stone block may be either deepslate one, a stair only as a stair.
                Substitutions.INSTANCE.getSwaps().add(new Substitutions.Swap("swap1",
                        java.util.Set.of(Blocks.STONE_BRICKS, Blocks.STONE_BRICK_STAIRS),
                        java.util.Set.of(Blocks.DEEPSLATE_BRICKS, Blocks.DEEPSLATE_BRICK_STAIRS)));
                Substitutions.INSTANCE.setOn(placement, true);
            });
            // Built: a swap, a swapped stair the right way, a swapped stair the wrong way, and a wrong block.
            server.runCommand("setblock 0 " + Y + " 0 deepslate_bricks");
            server.runCommand("setblock 1 " + Y + " 0 deepslate_brick_stairs[facing=east]");
            server.runCommand("setblock 2 " + Y + " 0 cobblestone");
            server.runCommand("setblock 3 " + Y + " 0 deepslate_brick_stairs[facing=west]");
            context.waitTicks(40);

            String matching = context.computeOnClient(c -> {
                var level = c.level;
                StringBuilder sb = new StringBuilder();
                for (int x = 0; x <= 3; x++) {
                    BlockPos pos = new BlockPos(x, Y, 0);
                    BlockState s = SchematicWorldHandler.getSchematicWorld().getBlockState(pos);
                    sb.append(Substitutions.matches(x, Y, 0, s, level.getBlockState(pos)) ? 'Y' : 'n');
                }
                return sb.toString();
            });
            LOG.info("matches at x 0..3: {} (want YYnn)", matching);
            if (!matching.equals("YYnn")) throw new AssertionError("matches gave " + matching);

            // In a group of bricks and stairs, a full block does not stand in for a stair.
            boolean blockForStair = context.computeOnClient(c ->
                    Substitutions.matches(1, Y, 0, stair, Blocks.DEEPSLATE_BRICKS.defaultBlockState()));
            LOG.info("full block counted for a stair: {}", blockForStair);
            if (blockForStair) throw new AssertionError("a full block counted for a stair");

            // The verifier: only the cobblestone and the backwards stair are wrong (not swaps); 4 and 5 are missing.
            context.runOnClient(c -> {
                SchematicPlacement placement = placement();
                placement.getSchematicVerifier().startVerification(c.level, SchematicWorldHandler.getSchematicWorld(), placement, () -> {});
            });
            context.waitFor(c -> placement().getSchematicVerifier().isFinished(), 400);
            String verdict = context.computeOnClient(c -> {
                SchematicVerifier v = placement().getSchematicVerifier();
                StringBuilder sb = new StringBuilder();
                for (int x = 0; x <= 5; x++) {
                    SchematicVerifier.BlockMismatch m = v.getMismatchForPosition(new BlockPos(x, Y, 0));
                    sb.append(x).append('=').append(m == null ? "ok" : m.mismatchType().name()).append(' ');
                }
                return sb.toString().trim() + " | correct " + v.getCorrectStatesCount();
            });
            LOG.info("verifier: {}", verdict);
            if (!verdict.startsWith("0=ok 1=ok 2=WRONG_BLOCK 3=WRONG_BLOCK 4=MISSING 5=MISSING")) {
                throw new AssertionError("verifier: " + verdict);
            }

            // Easy Place, the older path then the newer one, holding only deepslate bricks.
            server.runCommand("clear @a");
            server.runCommand("item replace entity @a hotbar.0 with deepslate_bricks 16");
            context.runOnClient(c -> c.player.getInventory().setSelectedSlot(0));
            easyPlace(context, server, 4, false);
            easyPlace(context, server, 5, true);
            String placed = context.computeOnClient(c ->
                    c.level.getBlockState(new BlockPos(4, Y, 0)).getBlock() + " " + c.level.getBlockState(new BlockPos(5, Y, 0)).getBlock());
            LOG.info("Easy Place put down: {}", placed);
            if (!placed.equals(Blocks.DEEPSLATE_BRICKS + " " + Blocks.DEEPSLATE_BRICKS)) throw new AssertionError("Easy Place put down " + placed);

            // Swaps off: the swap is wrong again.
            context.runOnClient(c -> Substitutions.INSTANCE.setOn(placement(), false));
            boolean stillMatches = context.computeOnClient(c ->
                    Substitutions.matches(0, Y, 0, Blocks.STONE_BRICKS.defaultBlockState(), c.level.getBlockState(new BlockPos(0, Y, 0))));
            LOG.info("with swaps off, x 0 still matches: {}", stillMatches);
            if (stillMatches) throw new AssertionError("a swap still counted with swaps off");

            context.runOnClient(c -> Substitutions.INSTANCE.setOn(placement(), true));
            server.runCommand("tp @a 2.5 " + (Y + 2) + " -3.5 0 35");
            context.waitTicks(40);
            context.takeScreenshot("swaps-overlay");
            // The settings: the swaps screen and the block picker.
            context.setScreen(() -> new com.skystormer.aboutfaceeasyplace.gui.SubstitutionsScreen(null));
            context.waitTicks(5);
            context.takeScreenshot("swaps-screen");
            context.setScreen(() -> new com.skystormer.aboutfaceeasyplace.gui.BlockPickScreen(null,
                    net.minecraft.network.chat.Component.translatable("gui.aboutfaceeasyplace.pick_from"),
                    java.util.Set.of(Blocks.STONE_BRICKS, Blocks.STONE_BRICK_STAIRS),
                    net.minecraft.network.chat.Component.translatable("gui.aboutfaceeasyplace.next"), picked -> kotlin.Unit.INSTANCE));
            context.waitTicks(5);
            context.takeScreenshot("swaps-picker");
            context.setScreen(() -> null);
            LOG.info("ALL CHECKS PASSED");
        }
    }

    private static SchematicPlacement placement() {
        return DataManager.getSchematicPlacementManager().getAllSchematicsPlacements().stream()
                .filter(p -> p.getName().equals("swap test")).findFirst().orElseThrow();
    }

    /** Stands in front of x, looks at it and asks Litematica's Easy Place to place there. */
    private static void easyPlace(ClientGameTestContext context, net.fabricmc.fabric.api.client.gametest.v1.context.TestServerContext server, int x, boolean postRewrite) {
        context.runOnClient(c -> {
            Configs.Generic.EASY_PLACE_MODE.setBooleanValue(true);
            Configs.Generic.EASY_PLACE_POST_REWRITE.setBooleanValue(postRewrite);
        });
        server.runCommand("tp @a " + (x + 0.5) + " " + Y + " -2.5 0 30");
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

    private static void hook(List<String> missing, String className, String handler) {
        try {
            Class<?> type = Class.forName(className);
            if (Arrays.stream(type.getDeclaredMethods()).noneMatch(m -> m.getName().contains(handler))) {
                missing.add(className + "#" + handler);
            }
        } catch (ClassNotFoundException e) {
            missing.add(className);
        }
    }
}
