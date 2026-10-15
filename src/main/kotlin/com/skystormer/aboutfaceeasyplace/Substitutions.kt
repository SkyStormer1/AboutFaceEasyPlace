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
 * Block substitutes: profiles you make ("All glass", "Stone"), each a list of groups of blocks. In a
 * group every block is a substitute for every other, so friends of friends count: a block added to
 * a group that shares a block with another group of the same profile joins the two. Profiles are
 * kept for every schematic; each placement switches substitutes on or off (off until you turn it
 * on) and can leave out any of the profiles.
 *
 * Where a placement has them on, a substitute counts as the right block everywhere Litematica
 * looks: the verifier counts it correct, the overlay shows nothing to do, and Easy Place leaves it
 * alone. Where nothing is there yet, Easy Place puts down whichever of the block and its
 * substitutes you have, the one in your hand first.
 *
 * A substitute carries the block's shape across: a stair keeps its facing and half, a slab its
 * type, by the properties the two blocks share. It only counts where those agree and it takes up
 * the same space, so in a group of bricks and stairs, only a stair is a substitute for a stair.
 *
 * Kept in `config/aboutfaceeasyplace-substitutions.json`. Litematica's chunk builder threads ask
 * [shown] for every block they draw, so what they read is an unchanging snapshot ([areas]), swapped
 * whole on the client thread.
 */
object Substitutions {

    /**
     * A profile: a name you give it, and its groups, none sharing a block. Known to placements by
     * [id], which stays the same when it is renamed.
     */
    class Profile(val id: String, var name: String, val groups: MutableList<Set<Block>> = ArrayList()) {

        /**
         * Puts [blocks] in as a group, in place of the group at [index] (or as a new one, at the end):
         * any other group sharing a block with it joins it. Fewer than two blocks takes the group away.
         */
        fun setGroup(index: Int?, blocks: Set<Block>) {
            var at = index ?: groups.size
            if (index != null && index in groups.indices) groups.removeAt(index) else at = groups.size
            if (blocks.size < 2) return
            val merged = LinkedHashSet(blocks)
            val iterator = groups.listIterator()
            var removedBefore = 0
            while (iterator.hasNext()) {
                val position = iterator.nextIndex()
                val group = iterator.next()
                if (group.any { it in merged }) {
                    merged += group
                    iterator.remove()
                    if (position < at) removedBefore++
                }
            }
            groups.add((at - removedBefore).coerceIn(0, groups.size), merged)
        }
    }

    /** What a placement has chosen: whether substitutes apply in it, and which profiles (by id) it leaves out. */
    data class PlacementChoice(var on: Boolean = false, val off: MutableSet<String> = HashSet())

    /** A placement with substitutes on, as a box and each block's substitutes there, for the chunk builder threads. */
    private class Area(
        val minX: Int, val minY: Int, val minZ: Int, val maxX: Int, val maxY: Int, val maxZ: Int,
        val substitutes: Map<Block, Set<Block>>,
    ) {
        fun contains(x: Int, y: Int, z: Int) = x in minX..maxX && y in minY..maxY && z in minZ..maxZ
    }

    /** The profiles, in the order they were made. */
    val profiles: MutableList<Profile> = ArrayList()

    /** A new profile with an id no other has. */
    fun newProfile(name: String): Profile {
        var n = profiles.size + 1
        while (profiles.any { it.id == "profile$n" }) n++
        return Profile("profile$n", name).also { profiles += it }
    }

    private val GSON = GsonBuilder().setPrettyPrinting().create()

    private val file: Path
        get() = FabricLoader.getInstance().configDir.resolve("aboutfaceeasyplace-substitutions.json")

    /** Each placement's choice, by [keyOf]. */
    private val choices = HashMap<String, PlacementChoice>()

    @Volatile
    private var areas: List<Area> = emptyList()

    private var ticks = 0
    private var broken = false

    // ---- What Litematica's hooks ask ----

    /**
     * Whether [client] at x, y, z is an allowed substitute for [schematic]: in a group with it, in a
     * profile the placement there uses, agreeing with the schematic's block on every property they
     * share, and the same shape (so in a group of bricks and stairs, only a stair counts for a stair).
     */
    @JvmStatic
    fun matches(x: Int, y: Int, z: Int, schematic: BlockState, client: BlockState): Boolean {
        if (schematic === client || schematic.isAir || client.isAir || schematic.block === client.block) return false
        val area = areas.firstOrNull { it.contains(x, y, z) } ?: return false
        val substitutes = area.substitutes[schematic.block] ?: return false
        return client.block in substitutes && shapesAgree(schematic, client) && sameShape(schematic, client)
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
     * The substitutes for [state] at [pos], each the same shape once swapped: none where no
     * placement with substitutes on covers it.
     */
    private fun allowedAt(pos: BlockPos, state: BlockState): List<Block> {
        val area = areas.firstOrNull { it.contains(pos.x, pos.y, pos.z) } ?: return emptyList()
        val substitutes = area.substitutes[state.block] ?: return emptyList()
        return substitutes.filter { sameShape(state, swapped(state, it)) }
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

    /** Switches substitutes on or off in [placement], saves, and redraws it. */
    fun setOn(placement: SchematicPlacement, on: Boolean) {
        choiceFor(placement).on = on
        changed()
    }

    /** After a profile or a placement's choice changed: saved, the snapshot rebuilt, every placement redrawn. */
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
            // Placements leaving out the same profiles share one table.
            val tables = HashMap<Set<String>, Map<Block, Set<Block>>>()
            for (placement in placements()) {
                if (!placement.isEnabled) continue
                val choice = choices[keyOf(placement)] ?: continue
                if (!choice.on) continue
                val table = tables.getOrPut(choice.off.toSet()) { substitutesTable(choice.off) }
                boxesOf(placement).forEach { out += Area(it.minX, it.minY, it.minZ, it.maxX, it.maxY, it.maxZ, table) }
            }
            areas = out
        } catch (e: LinkageError) {
            standDown(e)
        } catch (e: RuntimeException) {
            standDown(e)
        }
    }

    /** Each block's substitutes across the profiles not in [off]: everything else in its groups. */
    private fun substitutesTable(off: Set<String>): Map<Block, Set<Block>> {
        val out = HashMap<Block, MutableSet<Block>>()
        for (profile in profiles) {
            if (profile.id in off) continue
            for (group in profile.groups) for (block in group) out.getOrPut(block) { HashSet() } += group.filter { it !== block }
        }
        return out
    }

    private fun standDown(e: Throwable) {
        broken = true
        areas = emptyList()
        Log.error("block substitutes could not read Litematica's placements, so they are off for this session", e)
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
            Log.warn("could not ask Litematica to redraw after a substitutes change ({})", e.toString())
        }
    }

    // ---- Saving ----

    fun idOf(block: Block): String = BuiltInRegistries.BLOCK.getKey(block).toString()

    private fun blockOf(id: String): Block? =
        Identifier.tryParse(id)?.let { BuiltInRegistries.BLOCK.getOptional(it).orElse(null) }

    fun load() {
        val path = file
        profiles.clear()
        choices.clear()
        try {
            if (Files.notExists(path)) {
                starterProfiles()
                return
            }
            val json = JsonParser.parseString(Files.readString(path)).asJsonObject
            json.getAsJsonArray("profiles")?.forEach { e ->
                val o = e.asJsonObject
                val profile = Profile(o.get("id").asString, o.get("name")?.asString ?: "Profile")
                o.getAsJsonArray("groups")?.forEach { group -> profile.setGroup(null, blocksOf(group)) }
                profiles += profile
            }
            // Earlier test builds kept two-way links, or one-way swaps: they become one profile's groups.
            val pairs = ArrayList<Set<Block>>()
            json.getAsJsonArray("links")?.forEach { pairs += blocksOf(it) }
            json.getAsJsonArray("swaps")?.forEach { e ->
                val o = e.asJsonObject
                pairs += blocksOf(o.get("from")) + blocksOf(o.get("to"))
            }
            if (pairs.isNotEmpty()) newProfile("My substitutes").also { profile -> pairs.forEach { profile.setGroup(null, it) } }
            json.getAsJsonObject("placements")?.entrySet()?.forEach { (key, value) ->
                val o = value.asJsonObject
                choices[key] = PlacementChoice(
                    o.get("on")?.asBoolean ?: false,
                    o.getAsJsonArray("off")?.mapTo(HashSet()) { it.asString }?.filterTo(HashSet()) { id -> profiles.any { it.id == id } } ?: HashSet(),
                )
            }
        } catch (e: Exception) {
            Log.warn("could not read {}; starting with no substitutes. ({})", path, e.toString())
        }
    }

    /** The first time: an "All glass" profile to show how it goes, every glass block one group and every pane another. */
    private fun starterProfiles() {
        val glass = BuiltInRegistries.BLOCK.filterTo(LinkedHashSet()) { idOf(it).let { id -> id == "minecraft:glass" || id.endsWith("_stained_glass") || id == "minecraft:tinted_glass" } }
        val panes = BuiltInRegistries.BLOCK.filterTo(LinkedHashSet()) { idOf(it).let { id -> id == "minecraft:glass_pane" || id.endsWith("_stained_glass_pane") } }
        newProfile("All glass").apply {
            setGroup(null, glass)
            setGroup(null, panes)
        }
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
            json.add("profiles", JsonArray().also { array ->
                profiles.forEach { profile ->
                    array.add(JsonObject().apply {
                        addProperty("id", profile.id)
                        addProperty("name", profile.name)
                        add("groups", JsonArray().also { groups ->
                            profile.groups.forEach { group -> groups.add(JsonArray().also { a -> group.forEach { a.add(idOf(it)) } }) }
                        })
                    })
                }
            })
            json.add("placements", JsonObject().also { all ->
                // A placement left as it came (off, no profile left out) is not worth a line.
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
