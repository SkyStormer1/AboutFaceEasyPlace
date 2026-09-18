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
 * The last two are asked by reflection rather than by compiling against Litematica, and that is a
 * deliberate choice rather than a shortcut. Litematica's own classes are not remapped, and the
 * entry points used here take no Minecraft types in their signatures, so a reflective lookup is
 * exact in a way that a compiled call would not be: it cannot be thrown off by the mappings a
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

    private const val SCHEMATIC_WORLD_HANDLER = "fi.dy.masa.litematica.world.SchematicWorldHandler"
    private const val PLACEMENT_HANDLER = "fi.dy.masa.litematica.util.PlacementHandler"

    /** Everything Litematica owns lives under here. */
    private const val LITEMATICA_PACKAGE = "fi.dy.masa.litematica."

    /**
     * How far down the stack to look for Litematica.
     *
     * Litematica's own frame sits two or three below the placement it is making, so this is
     * generous rather than necessary. Bounding it keeps the cost flat on the deep stacks that a
     * player's own right click arrives on.
     */
    private const val STACK_SEARCH_DEPTH = 32L

    private val STACK_WALKER: StackWalker = StackWalker.getInstance()

    /** Set once, when something Litematica was expected to have is not there. */
    var unavailable: Boolean = false
        private set

    private val schematicWorldMethod: Method? by lazy { lookUp(SCHEMATIC_WORLD_HANDLER, "getSchematicWorld") }
    private val protocolMethod: Method? by lazy { lookUp(PLACEMENT_HANDLER, "getEffectiveProtocolVersion") }

    /**
     * Whether the placement being made right now is Litematica's rather than the player's.
     *
     * This is the only thing separating a block Easy Place is putting down from one the player is
     * placing by hand, and it has to be exact in both directions: a placement wrongly claimed
     * would turn a player's own block the wrong way, and one wrongly passed over is a schematic
     * block left crooked.
     *
     * It is answered by looking for Litematica on the call stack, which is a blunter instrument
     * than asking Litematica directly and is used anyway, because Litematica has **two** Easy
     * Place implementations and only one of them can be asked. `EasyPlaceUtils` raises a flag
     * around its placement; the older `WorldUtils` path raises nothing — and that older path is
     * the one that runs by default, because `easyPlacePostRewrite` defaults to off. A flag-based
     * test would therefore do nothing at all for most people.
     *
     * Looking at the stack has neither problem. Both paths call the placement from a class under
     * [LITEMATICA_PACKAGE], so both are caught, and so is whatever replaces them. A player's own
     * right click never is: Litematica's own hook into this method is a mixin, and a mixin's
     * injected code runs as a method **of the class it was merged into**, so it appears on the
     * stack as Minecraft rather than as Litematica.
     */
    fun isPlacing(): Boolean = STACK_WALKER.walk { frames ->
        frames.limit(STACK_SEARCH_DEPTH).anyMatch { it.className.startsWith(LITEMATICA_PACKAGE) }
    }

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
