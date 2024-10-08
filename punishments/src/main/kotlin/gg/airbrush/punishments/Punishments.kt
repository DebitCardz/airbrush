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

package gg.airbrush.punishments

import cc.ekblad.toml.decode
import cc.ekblad.toml.model.TomlException
import cc.ekblad.toml.tomlMapper
import gg.airbrush.sdk.lib.setInterval
import gg.airbrush.punishments.commands.*
import gg.airbrush.punishments.events.*
import gg.airbrush.sdk.SDK
import gg.airbrush.sdk.lib.ConfigUtils
import gg.airbrush.server.plugins.Plugin
import net.minestom.server.MinecraftServer
import net.minestom.server.event.EventNode
import java.time.Instant

data class Punishment(
	val shortReason: String,
	val longReason: String = shortReason,
	val action: String,
	val duration: List<String>? = null,
)

data class PunishmentsConfig(
	val punishments: Map<String, Punishment>
)

lateinit var punishmentConfig: PunishmentsConfig
internal val eventNode = EventNode.all("Punishments")

class Punishments : Plugin() {
	private val mapper = tomlMapper {}

    override fun setup() {
        // On start
	    val configPath = ConfigUtils.loadResource(
		    clazz = Punishments::class.java,
		    fileName = "punishments.toml",
		    pluginInfo = this.info
	    )

		try {
			punishmentConfig = mapper.decode(configPath)
		} catch (e: TomlException) {
			// We don't want to disable the plugin since it is essential. Instead, we load the default config.
			MinecraftServer.LOGGER.error("[Punishments] Failed to load punishments config, loading default.", e)

			val stream = Punishment::class.java.getResourceAsStream("/punishments.toml")!!
			stream.use { punishmentConfig = mapper.decode(it) }
		}

	    val manager = MinecraftServer.getCommandManager()
	    manager.register(PunishCommand())
	    manager.register(PunishmentsCommand())
	    manager.register(RevertPunishmentCommand())
		manager.register(BanCommand())
		manager.register(KickCommand())
		manager.register(MuteCommand())
		manager.register(PunishmentCommand())
		manager.register(EditPunishmentCommand())

		MinecraftServer.getGlobalEventHandler().addChild(eventNode)
	    PlayerEvents()

	    // Every minute, check for punishment expiry
	    setInterval(60 * 1000) {
		    val now = Instant.now().epochSecond

		    val allPunishments = SDK.punishments.getAll().filter { it.data.active }
		    allPunishments.forEach {
				val duration = it.data.duration
			    val expiry = duration + it.getCreatedAt().epochSecond

			    if(expiry < now) {
				    MinecraftServer.LOGGER.info("[Punishments] Punishment expired, ID: ${it.data.id}.")
				    it.setActive(false)
				}
		    }
	    }
    }

    override fun teardown() {
        // On shutdown
		MinecraftServer.getGlobalEventHandler().removeChild(eventNode)
    }
}