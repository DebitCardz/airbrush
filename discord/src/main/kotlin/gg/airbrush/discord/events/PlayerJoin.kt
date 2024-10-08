/*
 * This file is part of Airbrush
 *
 * Copyright (c) 2023 Airbrush Team
 *
 * This program is free software: you can redistribute it and/or modify it under the terms of the GNU General Public License as published by the Free Software Foundation, either version 3 of the License, or (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful, but WITHOUT ANY WARRANTY; without even the implied warranty of MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License along with this program. If not, see <https://www.gnu.org/licenses/>.
 */

package gg.airbrush.discord.events

import gg.airbrush.discord.bot
import gg.airbrush.discord.discordConfig
import gg.airbrush.discord.eventNode
import gg.airbrush.sdk.lib.Placeholder
import gg.airbrush.sdk.lib.parsePlaceholders
import net.minestom.server.entity.Player
import net.minestom.server.event.player.PlayerDisconnectEvent
import net.minestom.server.event.player.PlayerSpawnEvent
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter

object PlayerJoin {
	init {
        eventNode.addListener(PlayerSpawnEvent::class.java) { event ->
			if(!event.isFirstSpawn) return@addListener
            handle(event.player, type = "join")
        }

		eventNode.addListener(PlayerDisconnectEvent::class.java) { event ->
            handle(event.player, type = "leave")
        }
	}

	private fun handle(player: Player, type: String) {
		val configMsg = when (type) {
			"join" -> discordConfig.join.content
			"leave" -> discordConfig.leave.content
			else -> throw Exception("$type is an invalid event to handle")
		}

		val timestamp = DateTimeFormatter.ofPattern("HH:mm:ss z").format(ZonedDateTime.now())

		val parsedMsg = configMsg.parsePlaceholders(
			listOf(
				Placeholder("%name%", player.username),
				Placeholder("%timestamp%", timestamp),
			)
		)

		val channel = bot.getTextChannelById(discordConfig.channels.main.toLong())
			?: throw Exception("Failed to find chat channel!")
		channel.sendMessage(parsedMsg).queue()
	}
}