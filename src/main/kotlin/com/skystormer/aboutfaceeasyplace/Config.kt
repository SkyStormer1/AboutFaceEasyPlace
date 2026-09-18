package com.skystormer.aboutfaceeasyplace

import com.google.gson.GsonBuilder
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import net.fabricmc.loader.api.FabricLoader
import org.slf4j.LoggerFactory
import java.nio.file.Files
import java.nio.file.Path

/**
 * The three things worth deciding, kept in `config/aboutfaceeasyplace.json`.
 *
 * Everything else about this mod is meant to need no decision: Litematica says when to act and the
 * server says whether it is needed. What is left is whether the mod runs at all, what to do with a
 * block whose orientation cannot be reached, and whether to say so.
 *
 * Read and written by hand rather than through a config library, because three booleans do not
 * justify a dependency, and a config file that a missing library could stop the mod loading over
 * is a worse trade than a few lines of Gson.
 */
object Config {

    private val LOGGER = LoggerFactory.getLogger("aboutfaceeasyplace")
    private val GSON = GsonBuilder().setPrettyPrinting().create()

    private val file: Path
        get() = FabricLoader.getInstance().configDir.resolve("aboutfaceeasyplace.json")

    /** Whether the mod does anything at all. */
    @JvmStatic
    var enabled: Boolean = true

    /**
     * What to do with a block whose wanted orientation no legal click and look can produce.
     *
     * On, the placement is declined: the block shows in Litematica's overlay as something still to
     * do. Off, it is placed whichever way it lands, which has to be spotted and broken by hand.
     */
    @JvmStatic
    var skipImpossible: Boolean = true

    /** Whether to explain, on the action bar, the occasions when the mod declines or stands down. */
    @JvmStatic
    var showMessages: Boolean = true

    fun load() {
        val path = file
        try {
            if (Files.notExists(path)) {
                save()
                return
            }
            val json = JsonParser.parseString(Files.readString(path)).asJsonObject
            enabled = json.boolean("enabled", enabled)
            skipImpossible = json.boolean("skipImpossible", skipImpossible)
            showMessages = json.boolean("showMessages", showMessages)
        } catch (e: Exception) {
            // A config that cannot be read is not worth failing to start over. The defaults are
            // the ones most people would pick anyway.
            LOGGER.warn("About Face Easy Place: could not read {}; using defaults.", path, e)
        }
    }

    @JvmStatic
    fun save() {
        val path = file
        try {
            Files.createDirectories(path.parent)
            val json = JsonObject().apply {
                addProperty("enabled", enabled)
                addProperty("skipImpossible", skipImpossible)
                addProperty("showMessages", showMessages)
            }
            Files.writeString(path, GSON.toJson(json))
        } catch (e: Exception) {
            LOGGER.warn("About Face Easy Place: could not write {}.", path, e)
        }
    }

    private fun JsonObject.boolean(name: String, fallback: Boolean): Boolean =
        if (has(name)) get(name).asBoolean else fallback
}
