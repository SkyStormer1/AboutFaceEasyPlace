package com.skystormer.aboutfaceeasyplace

import org.slf4j.LoggerFactory

/**
 * Everything the mod writes to the log, and the one switch that decides how much of it there is.
 *
 * The split matters because of how often this code runs. Easy Place places blocks faster than a
 * person can follow, and a line per placement would be thousands of lines a minute — so the
 * per-placement detail is behind [Config.verboseLogging] and off by default, while anything that
 * represents a decision the player would want to know about is always written.
 *
 * Message strings are built by SLF4J's `{}` substitution rather than by interpolation, so a
 * detailed line that is switched off costs nothing beyond the call itself.
 */
object Log {

    private val LOGGER = LoggerFactory.getLogger("aboutfaceeasyplace")

    /** Whether per-placement detail is being written. Worth checking before building an argument. */
    val detailed: Boolean
        get() = Config.verboseLogging

    /** Something the player chose, or would want to know. Always written. */
    fun info(message: String, vararg arguments: Any?) = LOGGER.info(prefix(message), *arguments)

    /** Something went differently from how it should. Always written. */
    fun warn(message: String, vararg arguments: Any?) = LOGGER.warn(prefix(message), *arguments)

    /** Something broke. Always written. */
    fun error(message: String, cause: Throwable) = LOGGER.error(prefix(message), cause)

    /** Per-placement working, written only when [Config.verboseLogging] is on. */
    fun detail(message: String, vararg arguments: Any?) {
        if (!detailed) return
        LOGGER.info(prefix(message), *arguments)
    }

    /**
     * Written at `info` rather than `debug` deliberately.
     *
     * Someone who has switched verbose logging on is trying to find out why a block went down
     * crooked, and the usual answer to "it logs nothing" is that `debug` is filtered out by the
     * launcher before it reaches `latest.log`. Making the switch do the filtering, and the level
     * stay visible, is what makes the switch worth having.
     */
    private fun prefix(message: String) = "[About Face Easy Place] $message"
}
