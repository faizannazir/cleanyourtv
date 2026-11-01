package com.faizannazir.cleanyourtv.tv.model

import java.io.Serializable
import java.util.Locale

/**
 * The TV a baseline was captured from, as the ADB console sees it.
 *
 * This is deliberately *not* a physical-device identity. It is only the configured host and port.
 * A DHCP server can hand the same address to another TV later, and a USB or emulator connection can
 * reuse the same port, so restoring still requires the person to confirm they are pointed at the TV
 * the baseline came from. See `tv-app/README.md`.
 */
data class BackupTarget(val host: String, val port: Int) : Serializable {

    val display: String
        get() = "$host:$port"

    fun sameAs(other: BackupTarget): Boolean = host == other.host && port == other.port

    companion object {
        /**
         * Normalises a host/port pair for storage and comparison. Returns `null` when the pair
         * cannot identify a TCP endpoint, so no backup is ever written with an empty binding.
         */
        fun of(rawHost: String, port: Int): BackupTarget? {
            val host = rawHost.trim()
                .lowercase(Locale.ROOT)
                .removeSurrounding("[", "]")
            if (host.isBlank() || port !in 1..65535) return null
            return BackupTarget(host, port)
        }
    }
}

/**
 * Versioned on-disk wrapper around a [CleanupBaseline].
 *
 * The target binding lives in the envelope, not in [CleanupBaseline], so a baseline read from an
 * older build is unbound and can never be mistaken for one captured from the currently configured
 * TV. `version` is checked before any restore so a newer file is refused instead of guessed at.
 */
data class BackupEnvelope(
    val version: Int,
    val target: BackupTarget,
    val createdAt: Long,
    val baseline: CleanupBaseline
) : Serializable {

    companion object {
        const val CURRENT_VERSION = 1
        const val MIN_SUPPORTED_VERSION = 1
    }
}
