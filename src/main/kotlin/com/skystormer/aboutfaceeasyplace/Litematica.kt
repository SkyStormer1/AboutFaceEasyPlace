package com.skystormer.aboutfaceeasyplace

import net.minecraft.world.level.BlockGetter
import java.lang.reflect.Method

/**
 * Every question this mod asks Litematica: is it placing a block right now, what does the schematic
 * say should be there, is its own orientation protocol already being honoured by the server, and is
 * Easy Place switched on. (The one place it reaches into Litematica instead of asking is
 * `LitematicaEasyPlaceUtilsMixin`.)
 *
 * They are asked by reflection rather than by compiling against Litematica, and that is a
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

    private const val SCHEMATIC_WORLD_HANDLER = "fi.dy.masa.litematica.world.SchematicWorldHandler"
    private const val PLACEMENT_HANDLER = "fi.dy.masa.litematica.util.PlacementHandler"
    private const val EASY_PLACE_UTILS = "fi.dy.masa.litematica.util.EasyPlaceUtils"
    private const val GENERIC_CONFIGS = "fi.dy.masa.litematica.config.Configs\$Generic"
    private const val DATA_MANAGER = "fi.dy.masa.litematica.data.DataManager"

    /** Everything Litematica owns lives under here. */
    private const val LITEMATICA_PACKAGE = "fi.dy.masa.litematica."

    /** Where both of Litematica's Easy Place implementations live. */
    private const val EASY_PLACE_PACKAGE = "fi.dy.masa.litematica.util."

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

    /** Litematica classes already mentioned in the log, so that each is mentioned only once. */
    private val reportedInitiators = HashSet<String>()

    private val schematicWorldMethod: Method? by lazy { lookUp(SCHEMATIC_WORLD_HANDLER, "getSchematicWorld") }
    private val protocolMethod: Method? by lazy { lookUp(PLACEMENT_HANDLER, "getEffectiveProtocolVersion") }

    /**
     * The Litematica class that started the placement being made right now, or null if none did.
     *
     * Looking at the stack is a blunter instrument than asking Litematica directly, and is used
     * anyway, because Litematica has **two** Easy Place implementations and only one of them can be
     * asked. `EasyPlaceUtils` raises a flag around its placement; the older `WorldUtils` path
     * raises nothing — and that older path is the one that runs by default, because
     * `easyPlacePostRewrite` defaults to off. A flag-based test would do nothing at all for most
     * people.
     *
     * The stack has neither problem, and it answers a more useful question besides: not just
     * whether Litematica is placing, but which part of it. A player's own right click reaches this
     * with no Litematica frame at all — Litematica's own hook into the placement is a mixin, and
     * injected code runs as a method **of the class it was merged into**, so it appears on the
     * stack as Minecraft rather than as Litematica.
     */
    fun placingFrom(): String? = STACK_WALKER.walk { frames ->
        frames.limit(STACK_SEARCH_DEPTH)
            .map { it.className }
            .filter { it.startsWith(LITEMATICA_PACKAGE) }
            .findFirst()
            .orElse(null)
    }

    /**
     * Whether [initiator] is a part of Litematica whose placements are this mod's business.
     *
     * Not every Litematica placement is an Easy Place one. Pasting a schematic puts a block down at
     * a scratch position nearby purely to capture its block entity, and then takes it straight back
     * out again; claiming a rotation for that would be meddling in something that has nothing to do
     * with orientation, on a path that places in bulk.
     *
     * Both Easy Place implementations live in the same package, so that is the test. Anything else
     * under Litematica is declined — and said so, once, because a future Litematica that moves Easy
     * Place somewhere else would otherwise make this mod quietly stop working, which is exactly the
     * failure that a flag-based test already produced once.
     */
    fun isEasyPlace(initiator: String): Boolean {
        if (initiator.startsWith(EASY_PLACE_PACKAGE)) return true
        if (reportedInitiators.add(initiator)) {
            Log.info(
                "{} is placing a block, which is not one of the Easy Place paths this mod knows " +
                    "about ({}*), so it has been left alone. If Easy Place is no longer aligning " +
                    "blocks, this is why.",
                initiator, EASY_PLACE_PACKAGE,
            )
        }
        return false
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

    /**
     * Whether Easy Place is switched on and not set aside by the Rebuild tool, for the one case
     * where this mod acts on a click Litematica did not make — see `EasyPlaceHook`.
     *
     * Read, like everything else here, by reflection. Unlike everything else, a failure only turns
     * that one case off: nothing about it is worth standing the whole mod down for.
     */
    fun easyPlaceActive(): Boolean {
        if (easyPlaceSettingBroken) return false
        val setting = easyPlaceSetting ?: return false
        return try {
            if (setting.second.invoke(setting.first) != true) return false
            val mode = toolModeMethod?.invoke(null) as? Enum<*>
            mode?.name != "REBUILD"
        } catch (e: ReflectiveOperationException) {
            easyPlaceSettingBroken = true
            false
        }
    }

    private var easyPlaceSettingBroken = false

    private val easyPlaceSetting: Pair<Any, Method>? by lazy {
        try {
            val value = Class.forName(GENERIC_CONFIGS, false, javaClass.classLoader)
                .getField("EASY_PLACE_MODE").get(null)
            value to value.javaClass.getMethod("getBooleanValue")
        } catch (e: ReflectiveOperationException) {
            Log.info("could not read Litematica's easyPlaceMode setting ({}); clicks on containers are left to Litematica", e.toString())
            null
        } catch (e: LinkageError) {
            null
        }
    }

    private val toolModeMethod: Method? by lazy {
        try {
            Class.forName(DATA_MANAGER, false, javaClass.classLoader).getMethod("getToolMode")
        } catch (e: ReflectiveOperationException) {
            null
        } catch (e: LinkageError) {
            null
        }
    }

    private val setHandlingMethod: Method? by lazy {
        try {
            Class.forName(EASY_PLACE_UTILS, false, javaClass.classLoader)
                .getMethod("setHandling", Boolean::class.javaPrimitiveType)
        } catch (e: ReflectiveOperationException) {
            null
        } catch (e: LinkageError) {
            null
        }
    }

    /**
     * Runs [action] with Litematica's newer Easy Place believing it is already handling a click.
     *
     * A held placement is made a few ticks after Litematica asked for it, from outside its call.
     * With `easyPlacePostRewrite` on, Litematica would otherwise treat that placement as a fresh
     * right click of its own and try to handle it all over again.
     */
    fun <T> whileHandling(action: () -> T): T {
        val method = setHandlingMethod
        try {
            method?.invoke(null, true)
        } catch (_: ReflectiveOperationException) {
        }
        try {
            return action()
        } finally {
            try {
                method?.invoke(null, false)
            } catch (_: ReflectiveOperationException) {
            }
        }
    }

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
        Log.error(
            "$what. This Litematica build is not one this mod knows how to read, so it is " +
                "standing down for this session and leaving Easy Place untouched.",
            cause,
        )
    }
}
