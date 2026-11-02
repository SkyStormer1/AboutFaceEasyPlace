package com.skystormer.aboutfaceeasyplace.test;

import com.skystormer.aboutfaceeasyplace.Stripping;
import com.skystormer.aboutfaceeasyplace.Substitutions;
import fi.dy.masa.litematica.data.DataManager;
import fi.dy.masa.litematica.schematic.LitematicaSchematic;
import fi.dy.masa.litematica.schematic.container.LitematicaBlockStateContainer;
import fi.dy.masa.litematica.schematic.placement.SchematicPlacement;
import fi.dy.masa.litematica.selection.AreaSelection;
import fi.dy.masa.litematica.selection.Box;
import net.fabricmc.fabric.api.client.gametest.v1.FabricClientGameTest;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestDedicatedServerConnection;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestDedicatedServerContext;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestServerContext;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.List;
import java.util.Properties;
import java.util.Set;

/**
 * Easy Place with an axe, on a dedicated server: stripped wood is made from the unstripped kind along
 * the schematic's axis, with the axe in the hotbar or further in, on both Easy Place paths. Stripped
 * wood you already have is used as it is, and with no axe, or one about to break, nothing goes down.
 */
public class StrippingGameTest implements FabricClientGameTest {

    private static final Logger LOG = LoggerFactory.getLogger("StrippingGameTest");
    private static final int Y = 100;
    private static final int ROWS = 7;

    @Override
    public void runTest(ClientGameTestContext context) {
        Properties properties = new Properties();
        properties.setProperty("spawn-protection", "0");
        try (TestDedicatedServerContext server = context.worldBuilder().createServer(properties);
             TestDedicatedServerConnection connection = server.connect()) {
            connection.waitForChunksRender();
            run(context, server);
        }
    }

    private static void run(ClientGameTestContext context, TestServerContext server) {
        List<String> failures = new ArrayList<>();

        String unnamed = context.computeOnClient(c -> {
            StringBuilder out = new StringBuilder();
            for (Block block : BuiltInRegistries.BLOCK) {
                if (BuiltInRegistries.BLOCK.getKey(block).getPath().startsWith("stripped_") && Stripping.INSTANCE.sourceOf(block) == null) {
                    out.append(block).append(' ');
                }
            }
            return out.toString();
        });
        if (!unnamed.isEmpty()) failures.add("stripped blocks with no source: " + unnamed);

        server.runCommand("gamemode survival @a");
        int depth = ROWS * 4;
        server.runCommand("fill -4 " + (Y - 1) + " -4 16 " + (Y - 1) + " " + depth + " stone");
        server.runCommand("fill -4 " + Y + " -4 16 " + (Y + 4) + " " + depth + " air");
        server.runCommand("tp @a 5 " + Y + " -3 0 30");
        context.waitTicks(20);

        Block[] wanted = {
                Blocks.STRIPPED_CHERRY_WOOD, Blocks.STRIPPED_OAK_LOG, Blocks.STRIPPED_CRIMSON_HYPHAE, Blocks.STRIPPED_CHERRY_WOOD,
                Blocks.STRIPPED_OAK_WOOD, Blocks.STRIPPED_OAK_WOOD, Blocks.STRIPPED_BIRCH_WOOD,
        };
        context.runOnClient(c -> {
            BlockPos origin = new BlockPos(0, Y, 0);
            AreaSelection area = new AreaSelection();
            area.setName("strip test");
            area.addSubRegionBox(new Box(origin, new BlockPos(14, Y, depth), "grid"), false);
            LitematicaSchematic schematic = LitematicaSchematic.createEmptySchematic(area, "test");
            LitematicaBlockStateContainer grid = schematic.getSubRegionContainer("grid");
            for (int row = 0; row < ROWS; row++) {
                for (int a = 0; a < 3; a++) {
                    BlockState state = wanted[row].defaultBlockState().setValue(BlockStateProperties.AXIS, Direction.Axis.values()[a]);
                    grid.set(a * 2, 0, row * 4, state);
                    grid.set(8 + a * 2, 0, row * 4, state);
                }
            }
            DataManager.getSchematicPlacementManager().addSchematicPlacement(
                    SchematicPlacement.createFor(schematic, origin, "strip test", true, true), false);
        });
        context.waitTicks(40);

        // The axe in the hotbar.
        give(context, server, "give @a cherry_wood 16", "give @a iron_axe");
        row(context, server, 0, wanted[0], true, failures);
        int damage = context.computeOnClient(c -> damageOfFirstAxe(c.player));
        LOG.info("axe damage after six strips: {} (want 6)", damage);
        if (damage != 6) failures.add("axe damage " + damage + ", wanted 6");

        // The axe out of the hotbar, and a hotbar with no room in it.
        String[] full = new String[11];
        for (int i = 0; i < 9; i++) full[i] = "item replace entity @a hotbar." + i + " with dirt";
        full[9] = "item replace entity @a container.9 with oak_log 16";
        full[10] = "item replace entity @a container.20 with iron_axe";
        give(context, server, full);
        row(context, server, 1, wanted[1], true, failures);

        // Hyphae, with a shield in the other hand, which stops an axe unless crouching.
        give(context, server, "give @a crimson_hyphae 16", "give @a iron_axe", "item replace entity @a weapon.offhand with shield");
        row(context, server, 2, wanted[2], true, failures);

        // Stripped wood in the inventory is used, and the axe is left alone.
        give(context, server, "give @a stripped_cherry_wood 16", "give @a cherry_wood 16", "give @a iron_axe");
        row(context, server, 3, wanted[3], true, failures);
        int unused = context.computeOnClient(c -> damageOfFirstAxe(c.player));
        LOG.info("axe damage with stripped wood to hand: {} (want 0)", unused);
        if (unused != 0) failures.add("axe used although stripped wood was to hand: " + unused);

        // No axe.
        give(context, server, "give @a oak_wood 16");
        row(context, server, 4, wanted[4], false, failures);

        // An axe with one use left (an iron axe has 250).
        give(context, server, "give @a oak_wood 16", "give @a iron_axe[damage=249]");
        row(context, server, 5, wanted[5], false, failures);

        // The unstripped block an allowed substitute: it stays as it is, and the axe is left alone.
        context.runOnClient(c -> {
            Substitutions.INSTANCE.getProfiles().clear();
            Substitutions.INSTANCE.newProfile("Birch").setGroup(null, Set.of(Blocks.BIRCH_WOOD, Blocks.STRIPPED_BIRCH_WOOD));
            Substitutions.INSTANCE.setOn(DataManager.getSchematicPlacementManager().getAllSchematicsPlacements().stream()
                    .filter(p -> p.getName().equals("strip test")).findFirst().orElseThrow(), true);
        });
        give(context, server, "give @a birch_wood 16", "give @a iron_axe");
        row(context, server, 6, Blocks.BIRCH_WOOD, true, failures);
        int spared = context.computeOnClient(c -> damageOfFirstAxe(c.player));
        LOG.info("axe damage with the unstripped block a substitute: {} (want 0)", spared);
        if (spared != 0) failures.add("axe used on an allowed substitute: " + spared);

        failures.forEach(f -> LOG.error("FAILED: {}", f));
        if (!failures.isEmpty()) throw new AssertionError(failures.size() + " stripping failures, first: " + failures.get(0));
        LOG.info("ALL CHECKS PASSED");
    }

    /** Clears the inventory and runs [commands]. */
    private static void give(ClientGameTestContext context, TestServerContext server, String... commands) {
        server.runCommand("clear @a");
        for (String command : commands) server.runCommand(command);
        context.runOnClient(c -> c.player.getInventory().setSelectedSlot(0));
        context.waitTicks(5);
    }

    /** Easy Places a row with each path and checks it came out as [block], or stayed empty. */
    private static void row(ClientGameTestContext context, TestServerContext server, int row, Block block, boolean placed, List<String> failures) {
        for (int a = 0; a < 3; a++) {
            for (int x : new int[]{a * 2, 8 + a * 2}) {
                AxisBlocksGameTest.place(context, server, x, row * 4, x >= 8);
                context.waitTicks(30);
                BlockPos pos = new BlockPos(x, Y, row * 4);
                BlockState want = placed ? block.defaultBlockState().setValue(BlockStateProperties.AXIS, Direction.Axis.values()[a]) : Blocks.AIR.defaultBlockState();
                BlockState client = context.computeOnClient(c -> c.level.getBlockState(pos));
                BlockState real = server.computeOnServer(s -> s.overworld().getBlockState(pos));
                LOG.info("row {} x {}: wanted {} -> client {} server {}", row, x, want, client, real);
                if (real != want || client != want) failures.add("row " + row + " x " + x + ": wanted " + want + ", client " + client + ", server " + real);
            }
        }
    }

    private static int damageOfFirstAxe(net.minecraft.client.player.LocalPlayer player) {
        var inventory = player.getInventory();
        for (int i = 0; i < inventory.getContainerSize(); i++) {
            if (inventory.getItem(i).is(net.minecraft.world.item.Items.IRON_AXE)) return inventory.getItem(i).getDamageValue();
        }
        return -1;
    }
}
