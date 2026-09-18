package com.skystormer.aboutfaceeasyplace

/**
 * Notices Tweakeroo's own placement rotation, which fights this mod's.
 *
 * Tweakeroo's Accurate Block Placement sends a rotation of its own just before every ordinary
 * placement, and with `rememberFlexibleFromClick` it keeps reusing the orientation of the first
 * block for as long as the key is held. On a vanilla server that is a working rotation claim, just
 * like this mod's — and when Easy Place lets a click through to vanilla, the two arrive at the
 * server interleaved, and blocks come out facing whichever way Tweakeroo remembered.
 *
 * This only says so, once a session. Tweakeroo is the player's to configure.
 */
object Tweakeroo {

    private const val FEATURE_TOGGLE = "fi.dy.masa.tweakeroo.config.FeatureToggle"

    private var warned = false

    private val accuratePlacement: Pair<Any, java.lang.reflect.Method>? by lazy {
        try {
            val toggle = Class.forName(FEATURE_TOGGLE, false, javaClass.classLoader)
                .getField("TWEAK_ACCURATE_BLOCK_PLACEMENT").get(null)
            toggle to toggle.javaClass.getMethod("getBooleanValue")
        } catch (e: ReflectiveOperationException) {
            null
        } catch (e: LinkageError) {
            null
        }
    }

    fun warnIfFighting() {
        if (warned) return
        val (toggle, getter) = accuratePlacement ?: return
        val on = try {
            getter.invoke(toggle) == true
        } catch (e: ReflectiveOperationException) {
            false
        }
        if (!on) return
        warned = true
        Log.warn(
            "Tweakeroo's Accurate Block Placement is on. It sends its own rotation with every " +
                "ordinary placement, which on a vanilla server competes with this mod's for any click " +
                "Easy Place lets through. Turn it off while Easy Placing for reliable orientation.",
        )
        Messages.tweakerooFighting()
    }

    fun forget() {
        warned = false
    }
}
