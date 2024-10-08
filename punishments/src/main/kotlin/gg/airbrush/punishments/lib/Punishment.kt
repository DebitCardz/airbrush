/*
 * This file is part of Airbrush
 *
 * Copyright (c) 2024 Airbrush Team
 *
 * This program is free software: you can redistribute it and/or modify it under the terms of the GNU General Public License as published by the Free Software Foundation, either version 3 of the License, or (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful, but WITHOUT ANY WARRANTY; without even the implied warranty of MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License along with this program. If not, see <https://www.gnu.org/licenses/>.
 */

package gg.airbrush.punishments.lib

import gg.airbrush.discord.discordConfig
import gg.airbrush.discord.useBot
import gg.airbrush.punishments.enums.PunishmentTypes
import gg.airbrush.punishments.punishmentConfig
import gg.airbrush.sdk.SDK
import gg.airbrush.sdk.lib.Placeholder
import gg.airbrush.sdk.lib.Translations
import gg.airbrush.sdk.lib.capitalize
import gg.airbrush.sdk.lib.parsePlaceholders
import gg.airbrush.server.lib.mm
import net.dv8tion.jda.api.EmbedBuilder
import net.dv8tion.jda.api.entities.MessageEmbed
import net.kyori.adventure.text.Component
import net.minestom.server.MinecraftServer
import net.minestom.server.adventure.audience.Audiences
import org.slf4j.LoggerFactory
import java.awt.Color
import java.time.Instant
import java.util.UUID

private fun getNumericValue(input: String): Pair<Int, String> {
    val regex = Regex("(\\d+)(\\D+)")
    val matchResult = regex.find(input)
    val (numericValue, timeUnit) = matchResult?.destructured ?: throw IllegalArgumentException("Invalid input format: $input")
    return Pair(numericValue.toInt(), timeUnit)
}

/**
 *  Converts a duration string to seconds.
 *
 *  @param input Duration string (ex: "1h")
 *  @return Duration in seconds
 */
fun convertDate(input: String): Long {
    if(input.lowercase() == "forever") return Instant.MAX.epochSecond

    val (numericValue, timeUnit) = getNumericValue(input)

    val minute = 60L
    val hour = minute * 60
    val day = hour * 24
    val week = day * 7
    val month = week * 4

    val timeUnits: Map<String, Long> = mapOf(
        "min" to minute,
        "h" to hour,
        "d" to day,
        "w" to week,
        "mo" to month
    )

    val secondValue =
        timeUnits[timeUnit] ?: throw IllegalArgumentException("Invalid time unit specified $timeUnit")

    return numericValue * secondValue
}

private fun String.toPluralForm(): String {
    return when {
        endsWith("n") -> this + "ned"
        endsWith("e") -> this + "d"
        else -> this + "ed"
    }
}

fun canPunish(uuid: UUID): Boolean {
    val playerExists = SDK.players.exists(uuid)
    if(!playerExists) return true
    val offenderRank = SDK.players.get(uuid).getRank()
    val offenderPermissions = offenderRank.getData().permissions
    return offenderPermissions.find { it.key == "core.staff" || it.key == "*" } === null
}

data class User(val uuid: UUID, val name: String)

fun getReasonInfo(reason: String): Pair<String, String> {
    var shortReason = reason
    var longReason = reason

    if(reason.lowercase() in punishmentConfig.punishments.keys) {
        val punishmentInfo = punishmentConfig.punishments[reason.lowercase()]!!
        shortReason = punishmentInfo.shortReason.capitalize()
        longReason = punishmentInfo.longReason
    }

    return Pair(shortReason, longReason)
}

data class Punishment(
    /** The moderator who is applying the punishment */
    val moderator: User,
    /** The player being punished */
    val player: User,
    /** The reason for the punishment */
    val reason: String,
    /** The type of punishment to apply */
    val type: PunishmentTypes,
    /** Duration string (ex: "1h") */
    val duration: String = "FOREVER",
    /** Notes to be attached to the punishment */
    val notes: String = "",
) {
    private val logger = LoggerFactory.getLogger(Punishment::class.java)

    fun getDisconnectMessage(): Component {
        val key = when (this.type) {
            PunishmentTypes.BAN -> "punishments.playerBanned"
            PunishmentTypes.AUTO_BAN -> "punishments.playerAutoBanned"
            else -> "punishments.playerKicked"
        }
        val translation = Translations.getString(key)
        val placeholders = this.getPlaceholders()
        return translation.parsePlaceholders(placeholders).trimIndent().mm()
    }

    private fun getPlaceholders(): List<Placeholder> {
        val (shortReason, longReason) = getReasonInfo(this.reason)

        val placeholders = listOf(
            Placeholder("%moderator%", this.moderator.name),
            Placeholder("%player%", this.player.name),
            Placeholder("%action%", this.type.name),
            Placeholder("%type%", this.getPluralType()),
            Placeholder("%short_reason%", shortReason),
            Placeholder("%long_reason%", longReason),
            Placeholder("%duration%", this.getFormattedDuration()),
            Placeholder("%notes%", this.notes),
        )

        return placeholders
    }

    private fun sendDiscordLog(id: String) {
        logger.info("Player ${player.name} was ${getPluralType()} by ${moderator.name} for $reason (Punishment ID: $id)")

        useBot {
            val discordLogChannel = it.getTextChannelById(discordConfig.channels.log.toLong())
                ?: throw Exception("Failed to find #punish-logs channel")

            val (shortReason) = getReasonInfo(this.reason)

            val logEmbed = EmbedBuilder().setTitle("${this.player.name} was ${getPluralType()}")
                .setColor(Color.decode("#ff6e6e"))
                .setThumbnail("https://skins.mcstats.com/body/side/${this.player.uuid}")
                .addField(MessageEmbed.Field("Reason", shortReason, true))
                .addField(MessageEmbed.Field("Moderator", this.moderator.name, true))
                .addField(MessageEmbed.Field("Punishment ID", id, false))
                .setFooter("Environment: ${if(SDK.isDev) "Development" else "Production"}")

            if(this.notes.isNotEmpty()) {
                logEmbed.addField(MessageEmbed.Field("Notes", this.notes, true))
            }

            discordLogChannel.sendMessageEmbeds(logEmbed.build()).queue()
        }
    }

    fun handle(): Punishment {
        var duration = convertDate(this.duration)
        if(this.type == PunishmentTypes.KICK) duration = 0

        val punishment = SDK.punishments.create(
            moderator = this.moderator.uuid,
            player = this.player.uuid,
            reason = this.reason,
            type = this.type.ordinal,
            duration = duration,
            notes = this.notes,
            active = this.type != PunishmentTypes.KICK
        )

        try {
            sendDiscordLog(punishment.id)
        } catch (e: Exception) {
            logger.error("[Punishments] Failed to send Discord log for punishment ${punishment.id}", e)
        }

        val logPlaceholders = this.getPlaceholders()
            .plus(Placeholder("%id%", punishment.id))

        val key = if(this.type == PunishmentTypes.KICK) "punishments.kickedPlayer" else "punishments.punishment"
        val logString = Translations.getString(key).parsePlaceholders(logPlaceholders).trimIndent()
        Audiences.players { p -> p.hasPermission("core.staff") }
            .sendMessage(logString.mm())

        val onlinePlayer = MinecraftServer.getConnectionManager().onlinePlayers.firstOrNull {
            it.uuid == player.uuid
        }

        if(onlinePlayer === null) {
            logger.warn("[Punishments] Player ${player.name} was not online, but punishment was applied.")
            return this
        }

        when(this.type) {
            PunishmentTypes.BAN,
            PunishmentTypes.AUTO_BAN,
            PunishmentTypes.KICK -> {
                onlinePlayer.kick(this.getDisconnectMessage())
            }
            PunishmentTypes.MUTE -> {
                val mutedMsg = Translations.getString("punishments.playerMuted")
                    .parsePlaceholders(logPlaceholders).trimIndent()
                onlinePlayer.sendMessage(mutedMsg.mm())
            }
        }

        return this
    }

    /**
     *  Fetches the punishment type in a human-readable format.
     *
     *  @example "kick" -> "kicked"
     */
    private fun getPluralType(): String {
        return this.type.toString().lowercase().toPluralForm()
    }

    /**
     *  Gets the formatted duration of the punishment.
     *
     *  Note: This is not the expiry of the punishment, but the overall duration of the punishment.
     *
     *  @example "1h" -> "1 hour"
     */
    private fun getFormattedDuration(): String {
        if(this.duration.lowercase() == "forever") return "forever"

        val (numericValue, timeUnit) = getNumericValue(this.duration)

        var unit = when(timeUnit) {
            "min" -> "minute"
            "h" -> "hour"
            "d" -> "day"
            "w" -> "week"
            "mo" -> "month"
            else -> throw Exception("Invalid time unit specified $timeUnit")
        }
        if(numericValue > 1) unit += "s"

        val duration = "$numericValue $unit"

        return duration
    }
}