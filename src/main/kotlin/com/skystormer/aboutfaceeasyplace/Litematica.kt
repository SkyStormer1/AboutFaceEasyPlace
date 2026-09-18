package com.skystormer.aboutfaceeasyplace

import net.minecraft.world.level.BlockGetter
import org.slf4j.LoggerFactory
import java.lang.reflect.Method

/**
 * The whole of this mod's contact with Litematica.
 *
 * Three questions are asked, and nothing else is: is Litematica placing a block right now, what
 * does the schematic say should be there, and is Litematica's own orientation protocol already
 * being honoured by the server.
 *
 * They are asked by reflection rather than by compiling against Litematica, and that is a
 * deliberate choice rather than a shortcut. Litematica's own classes are not remapped, and the
 * three entry points used here take no Minecraft types in their signatures, so a reflective lookup
 * is exact in a way that a compiled call would not be: it cannot be thrown off by the mappings a
 * particular Litematica build was compiled with, and it does not pin this mod to one Litematica
 * version at build time. `WorldSchematic` is a [net.minecraft.world.level.Level] at runtime, so
 * the schematic world comes back as a plain [BlockGetter] and everything past this file is
 * ordinary Minecraft.
 *
 * If any lookup fails, the mod says so once and then stands down for the rest of the session.
 * Guessing at a Litematica that has moved underneath it would mean placing blocks wrongly, which
 * is the one outcome worth more than being unavailable.
 */
object Litematica {

    private val LOGGER = LoggerFactory.getLogger("aboutfaceeasyplace")

    private const val EASY_PLACE_UTILS = "fi.dy.masa.litematica.util.EasyPlaceUtils"
    private const val SCHEMATIC_WORLD_HANDLER = "fi.dy.masa.litematica.world.SchematicWorldHandler"
    private const val PLACEMENT_HANDLER = "fi.dy.masa.litematica.util.PlacementHandler"

    /** Set once, when something Litematica was expected to have is not there. */
    var unavailable: Boolean = false
        private set

    private val isHandlingMethod: Method? by lazy { lookUp(EASY_PLACE_UTILS, "isHandling") }
    private val schematicWorldMethod: Method? by lazy { lookUp(SCHEMATIC_WORLD_HANDLER, "getSchematicWorld") }
    private val protocolMethod: Method? by lazy { lookUp(PLACEMENT_HANDLER, "getEffectiveProtocolVersion") }

    /**
     * Whether Litematica is in the middle of an Easy Place action.
     *
     * This is the only thing separating a block Easy Place is putting down from one the player is
     * placing by hand, and it has to be exact in both directions: a placement wrongly claimed would
     * turn a player's own block the wrong way, and one wrongly passed over is a schematic block
     * left crooked. Litematica raises this flag around its own placement call and lowers it
     * immediately after, so it is exact by construction.
     */
    fun isPlacing(): Boolean = invoke(isHandlingMethod) as? Boolean ?: false

    /** The schematic world, or null when no schematic is loaded. */
    fun schematicWorld(): BlockGetter? = invoke(schematicWorldMethod) as? BlockGetter

    /**
     * The name of the Easy Place protocol Litematica has settled on for this server.
     *
     * Litematica resolves `Auto` itself, against what it has detected of the server, so asking for
     * the effective value rather than the configured one is what makes [Engagement] able to tell a
     * vanilla server from a Carpet or Servux one without doing any detecting of its own.
     */
    fun effectiveProtocol(): String? = (invoke(protocolMethod) as? Enum<*>)?.name

    private fun lookUp(className: String, methodName: String): Method? {
        if (unavailable) return null
        return try {
            Class.forName(className, false, javaClass.classLoader)
                .getMethod(methodName)
                .also { it.isAccessible = true }
        } catch (e: ReflectiveOperationException) {
            standDown("could not find $className#$methodName()", e)
            null
        } catch (e: LinkageError) {
            standDown("could not load $className", e)
            null
        }
    }

    private fun invoke(method: Method?): Any? {
        if (method == null || unavailable) return null
        return try {
            method.invoke(null)
        } catch (e: ReflectiveOperationException) {
            standDown("call to ${method.name}() failed", e)
            null
        } catch (e: LinkageError) {
            standDown("call to ${method.name}() failed", e)
            null
        }
    }

    private fun standDown(what: String, cause: Throwable) {
        if (unavailable) return
        unavailable = true
        LOGGER.error(
            "About Face Easy Place: {}. This Litematica build is not one this mod knows how to " +
                "read, so it is standing down for this session and leaving Easy Place untouched.",
            what,
            cause,
        )
    }
}
