package com.skystormer.aboutfaceeasyplace

import com.google.gson.GsonBuilder
import com.google.gson.JsonArray
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import fi.dy.masa.litematica.data.DataManager
import fi.dy.masa.litematica.schematic.placement.SchematicPlacement
import fi.dy.masa.litematica.schematic.placement.SubRegionPlacement
import fi.dy.masa.litematica.world.SchematicWorldHandler
import net.fabricmc.loader.api.FabricLoader
import net.minecraft.client.Minecraft
import net.minecraft.core.BlockPos
import net.minecraft.core.registries.BuiltInRegistries
import net.minecraft.resources.Identifier
import net.minecraft.world.entity.player.Inventory
import net.minecraft.world.entity.player.Player
import net.minecraft.world.item.BlockItem
import net.minecraft.world.level.BlockGetter
import net.minecraft.world.level.EmptyBlockGetter
import net.minecraft.world.level.block.Block
import net.minecraft.world.level.block.entity.BlockEntity
import net.minecraft.world.level.block.state.BlockState
import net.minecraft.world.level.material.FluidState
import net.minecraft.world.phys.shapes.Shapes
import java.nio.file.Files
import java.nio.file.Path

/**
 * Block substitution: a list of swaps, each a group ("Stone Bricks or Cobblestone may be Deepslate
 * Bricks or Tuff"), kept for every schematic, and switched on placement by placement, each of which can leave out any of the swaps.
 * Where a placement has it on, a swapped block counts as the right one everywhere Litematica looks:
 * the verifier counts it correct, the overlay shows nothing to do, and Easy Place leaves it alone.
 * Where nothing is there yet, Easy Place puts down whichever of the block and its swaps you have,
 * the one in your hand first.
 *
 * A swap carries the block's shape across: a swapped stair keeps its facing and half, a slab its
 * type, by the properties the two blocks share. A block only counts as the swap when those agree.
 *
 * Kept in `config/aboutfaceeasyplace-substitutions.json`. Litematica's chunk builder threads ask
 * [shown] for every block they draw, so what they read is an unchanging snapshot ([areas], [byBlock]),
 * swapped whole on the client thread.
 */
object Substitutions {

    /**
     * One swap: any block of [to] may stand in for any block of [from]. Known by [id], which stays
     * the same when the swap is edited, so a placement that leaves it out still does.
     */
    class Swap(val id: String, val from: Set<Block>, val to: Set<Block>)

    /** A new swap with an id no other has. */
    fun newSwap(from: Set<Block>, to: Set<Block>): Swap {
        var n = swaps.size + 1
        while (swaps.any { it.id == "swap$n" }) n++
        return Swap("swap$n", from, to)
    }

    /** What a placement has chosen: whether swaps apply in it, and which ones it leaves out. */
    data class PlacementChoice(var on: Boolean = false, val off: MutableSet<String> = HashSet())

    /** A placement with swaps on, as a box and the swaps it allows, for the chunk builder threads. */
    private class Area(
        val minX: Int, val minY: Int, val minZ: Int, val maxX: Int, val maxY: Int, val maxZ: Int,
        val off: Set<String>,
    ) {
        fun contains(x: Int, y: Int, z: Int) = x in minX..maxX && y in minY..maxY && z in minZ..maxZ
    }

    private val GSON = GsonBuilder().setPrettyPrinting().create()

    private val file: Path
        get() = FabricLoader.getInstance().configDir.resolve("aboutfaceeasyplace-substitutions.json")

    /** The swaps, in the order they were added. */
    val swaps: MutableList<Swap> = ArrayList()

    /** Each placement's choice, by [keyOf]. */
    private val choices = HashMap<String, PlacementChoice>()

    @Volatile
    private var areas: List<Area> = emptyList()

    @Volatile
    private var byBlock: Map<Block, List<Swap>> = emptyMap()

    private var ticks = 0
    private var broken = false

    // ---- What Litematica's hooks ask ----

    /**
     * Whether [client] at x, y, z is an allowed swap for [schematic]: a block of a swap the
     * placement there has on, agreeing with the schematic's block on every property they share,
     * and the same shape (so in a swap of bricks and stairs, only a stair stands in for a stair).
     */
    @JvmStatic
    fun matches(x: Int, y: Int, z: Int, schematic: BlockState, client: BlockState): Boolean {
        if (schematic === client || schematic.isAir || client.isAir || schematic.block === client.block) return false
        val swaps = byBlock[schematic.block] ?: return false
        if (swaps.none { client.block in it.to }) return false
        val area = areas.firstOrNull { it.contains(x, y, z) } ?: return false
        return swaps.any { client.block in it.to && it.id !in area.off } && shapesAgree(schematic, client) && sameShape(schematic, client)
    }

    /** What Litematica should take the schematic to hold at [pos], with [client] there: the swap when it [matches]. */
    @JvmStatic
    fun shown(pos: BlockPos, schematic: BlockState, client: BlockState): BlockState =
        if (matches(pos.x, pos.y, pos.z, schematic, client)) client else schematic

    /**
     * The block Easy Place should put at [pos], where the schematic has [schematic]: what is
     * already there when it is an allowed swap (so Easy Place leaves it alone); else, where the
     * spot is free, the block in your hand when it is the schematic's or an allowed swap, then the
     * schematic's when you have it, then the first swap you have; else the schematic's.
     */
    @JvmStatic
    fun toPlace(pos: BlockPos, schematic: BlockState): BlockState {
        val minecraft = Minecraft.getInstance()
        val client = minecraft.level?.getBlockState(pos) ?: return schematic
        if (matches(pos.x, pos.y, pos.z, schematic, client)) return client
        val player = minecraft.player ?: return schematic
        val allowed = allowedAt(pos, schematic).ifEmpty { return schematic }
        val held = (player.mainHandItem.item as? BlockItem)?.block
        if (held === schematic.block) return schematic
        if (held != null && held in allowed) return swapped(schematic, held)
        if (has(player, schematic.block)) return schematic
        val have = allowed.firstOrNull { has(player, it) } ?: return schematic
        return swapped(schematic, have)
    }

    /**
     * The schematic as this mod's own planning sees it: like [shown], and where nothing is there
     * yet, the swap in your hand, so the block you are about to place is the one it lines up.
     */
    fun planningView(schematic: BlockGetter): BlockGetter = object : BlockGetter {
        override fun getBlockState(pos: BlockPos): BlockState {
            val state = schematic.getBlockState(pos)
            if (state.isAir || areas.isEmpty()) return state
            val minecraft = Minecraft.getInstance()
            val client = minecraft.level?.getBlockState(pos) ?: return state
            if (matches(pos.x, pos.y, pos.z, state, client)) return client
            val held = (minecraft.player?.mainHandItem?.item as? BlockItem)?.block ?: return state
            return if (held !== state.block && held in allowedAt(pos, state)) swapped(state, held) else state
        }

        override fun getBlockEntity(pos: BlockPos): BlockEntity? = schematic.getBlockEntity(pos)
        override fun getFluidState(pos: BlockPos): FluidState = schematic.getFluidState(pos)
        override fun getHeight(): Int = schematic.height
        override fun getMinY(): Int = schematic.minY
    }

    /**
     * The blocks that may stand in for [state] at [pos], each the same shape once swapped: none
     * where no placement with swaps on covers it.
     */
    private fun allowedAt(pos: BlockPos, state: BlockState): List<Block> {
        val swaps = byBlock[state.block] ?: return emptyList()
        val area = areas.firstOrNull { it.contains(pos.x, pos.y, pos.z) } ?: return emptyList()
        return swaps.filter { it.id !in area.off }.flatMap { it.to }.distinct()
            .filter { it !== state.block && sameShape(state, swapped(state, it)) }
    }

    /** Whether the two take up the same space: a full block for a full block, a stair for a stair facing the same way. */
    private fun sameShape(a: BlockState, b: BlockState): Boolean =
        Shapes.equal(a.getShape(EmptyBlockGetter.INSTANCE, BlockPos.ZERO), b.getShape(EmptyBlockGetter.INSTANCE, BlockPos.ZERO))

    /** Whether the two blocks agree on every property they both have (a stair's facing, a slab's type). */
    private fun shapesAgree(a: BlockState, b: BlockState): Boolean {
        for (property in b.properties) {
            val same = a.block.stateDefinition.getProperty(property.name) ?: continue
            if (BlockStates.valueName(a, same) != BlockStates.valueName(b, property)) return false
        }
        return true
    }

    /** [state]'s shape on [block]: each property they share set the same, the rest as [block] comes. */
    @JvmStatic
    fun swapped(state: BlockState, block: Block): BlockState {
        var out = block.defaultBlockState()
        for (property in block.stateDefinition.properties) {
            val same = state.block.stateDefinition.getProperty(property.name) ?: continue
            out = BlockStates.withValueNamed(out, property, BlockStates.valueName(state, same))
        }
        return out
    }

    private fun has(player: Player, block: Block): Boolean {
        if (player.hasInfiniteMaterials()) return true
        val inventory = player.inventory
        return (0 until Inventory.INVENTORY_SIZE).any { (inventory.getItem(it).item as? BlockItem)?.block === block }
    }

    // ---- Placements ----

    /** What a placement is known by across sessions: its schematic's file and its own name. */
    fun keyOf(placement: SchematicPlacement): String =
        "${placement.schematicFile?.fileName?.toString() ?: ""}|${placement.name}"

    fun choiceFor(placement: SchematicPlacement): PlacementChoice = choices.getOrPut(keyOf(placement)) { PlacementChoice() }

    /** Every placement loaded now, in Litematica's order. */
    fun placements(): List<SchematicPlacement> = try {
        DataManager.getSchematicPlacementManager().allSchematicsPlacements.toList()
    } catch (e: LinkageError) {
        emptyList()
    }

    /** The placement Litematica has selected, or else the first one you are standing in. */
    fun currentPlacement(): SchematicPlacement? {
        val manager = DataManager.getSchematicPlacementManager()
        manager.selectedSchematicPlacement?.let { return it }
        val pos = Minecraft.getInstance().player?.blockPosition() ?: return null
        return placements().firstOrNull { p -> boxesOf(p).any { it.contains(pos.x, pos.y, pos.z) } }
    }

    /** Switches swaps on or off in [placement], saves, and redraws it. */
    fun setOn(placement: SchematicPlacement, on: Boolean) {
        choiceFor(placement).on = on
        changed()
    }

    /** After the swaps or a placement's choice changed: saved, the snapshot rebuilt, every placement redrawn. */
    fun changed() {
        save()
        refresh()
        redraw()
    }

    /** Called every client tick; the snapshot follows placements that are added, moved or removed. */
    fun tick() {
        if (broken) return
        if (++ticks % REFRESH_TICKS == 0) refresh()
    }

    private fun refresh() {
        try {
            val out = ArrayList<Area>()
            for (placement in placements()) {
                if (!placement.isEnabled) continue
                val choice = choices[keyOf(placement)] ?: continue
                if (!choice.on) continue
                val off = choice.off.toSet()
                boxesOf(placement).forEach { out += Area(it.minX, it.minY, it.minZ, it.maxX, it.maxY, it.maxZ, off) }
            }
            areas = out
            byBlock = indexed()
        } catch (e: LinkageError) {
            standDown(e)
        } catch (e: RuntimeException) {
            standDown(e)
        }
    }

    private fun standDown(e: Throwable) {
        broken = true
        areas = emptyList()
        Log.error("block substitution could not read Litematica's placements, so it is off for this session", e)
    }

    private class Bounds(val minX: Int, val minY: Int, val minZ: Int, val maxX: Int, val maxY: Int, val maxZ: Int) {
        fun contains(x: Int, y: Int, z: Int) = x in minX..maxX && y in minY..maxY && z in minZ..maxZ
    }

    private fun boxesOf(placement: SchematicPlacement): List<Bounds> =
        placement.getSubRegionBoxes(SubRegionPlacement.RequiredEnabled.PLACEMENT_ENABLED).values.mapNotNull { box ->
            val a = box.pos1 ?: return@mapNotNull null
            val b = box.pos2 ?: return@mapNotNull null
            Bounds(minOf(a.x, b.x), minOf(a.y, b.y), minOf(a.z, b.z), maxOf(a.x, b.x), maxOf(a.y, b.y), maxOf(a.z, b.z))
        }

    /** Asks Litematica to draw every placement's chunks again, so the overlay follows a change. */
    private fun redraw() {
        try {
            val world = SchematicWorldHandler.getSchematicWorld() ?: return
            for (placement in placements()) {
                for (box in boxesOf(placement)) {
                    for (cx in (box.minX shr 4)..(box.maxX shr 4)) {
                        for (cz in (box.minZ shr 4)..(box.maxZ shr 4)) world.scheduleChunkRenders(cx, cz)
                    }
                }
            }
        } catch (e: LinkageError) {
            Log.warn("could not ask Litematica to redraw after a substitution change ({})", e.toString())
        }
    }

    // ---- Saving ----

    fun idOf(block: Block): String = BuiltInRegistries.BLOCK.getKey(block).toString()

    private fun blockOf(id: String): Block? =
        Identifier.tryParse(id)?.let { BuiltInRegistries.BLOCK.getOptional(it).orElse(null) }

    fun load() {
        val path = file
        swaps.clear()
        choices.clear()
        try {
            if (Files.notExists(path)) return
            val json = JsonParser.parseString(Files.readString(path)).asJsonObject
            json.getAsJsonArray("swaps")?.forEach { e ->
                val o = e.asJsonObject
                val from = blocksOf(o.get("from"))
                val to = blocksOf(o.get("to"))
                if (from.isNotEmpty() && to.isNotEmpty()) swaps += Swap(o.get("id")?.asString ?: newSwap(from, to).id, from, to)
            }
            json.getAsJsonObject("placements")?.entrySet()?.forEach { (key, value) ->
                val o = value.asJsonObject
                choices[key] = PlacementChoice(
                    o.get("on")?.asBoolean ?: false,
                    o.getAsJsonArray("off")?.mapTo(HashSet()) { it.asString } ?: HashSet(),
                )
            }
        } catch (e: Exception) {
            Log.warn("could not read {}; starting with no swaps. ({})", path, e.toString())
        }
        byBlock = indexed()
    }

    /** The swaps by each block they replace, for the lookups. */
    private fun indexed(): Map<Block, List<Swap>> {
        val out = HashMap<Block, MutableList<Swap>>()
        swaps.forEach { swap -> swap.from.forEach { out.getOrPut(it) { ArrayList() } += swap } }
        return out
    }

    /** A list of block ids (or one, as the first version wrote) as blocks, leaving out any this game does not have. */
    private fun blocksOf(element: com.google.gson.JsonElement?): Set<Block> = when {
        element == null -> emptySet()
        element.isJsonArray -> element.asJsonArray.mapNotNullTo(LinkedHashSet()) { blockOf(it.asString) }
        else -> setOfNotNull(blockOf(element.asString))
    }

    fun save() {
        val path = file
        try {
            Files.createDirectories(path.parent)
            val json = JsonObject()
            json.add("swaps", JsonArray().also { array ->
                swaps.forEach { swap ->
                    array.add(JsonObject().apply {
                        addProperty("id", swap.id)
                        add("from", JsonArray().also { a -> swap.from.forEach { a.add(idOf(it)) } })
                        add("to", JsonArray().also { a -> swap.to.forEach { a.add(idOf(it)) } })
                    })
                }
            })
            json.add("placements", JsonObject().also { all ->
                // A placement left as it came (off, nothing left out) is not worth a line.
                choices.filter { (_, c) -> c.on || c.off.isNotEmpty() }.toSortedMap().forEach { (key, c) ->
                    all.add(key, JsonObject().apply {
                        addProperty("on", c.on)
                        add("off", JsonArray().also { array -> c.off.sorted().forEach { array.add(it) } })
                    })
                }
            })
            Files.writeString(path, GSON.toJson(json))
        } catch (e: Exception) {
            Log.warn("could not write {}. ({})", path, e.toString())
        }
    }

    private const val REFRESH_TICKS = 10
}
