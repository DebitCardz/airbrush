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

package gg.airbrush.server

import gg.airbrush.server.commands.Plugins
import gg.airbrush.server.commands.Stop
import gg.airbrush.server.lib.OperatorSender
import gg.airbrush.server.lib.ServerProperties
import gg.airbrush.server.lib.mm
import gg.airbrush.server.plugins.PluginManager
import net.kyori.adventure.text.logger.slf4j.ComponentLogger
import net.minestom.server.MinecraftServer
import net.minestom.server.coordinate.Pos
import net.minestom.server.event.player.*
import net.minestom.server.event.server.ServerListPingEvent
import net.minestom.server.extras.MojangAuth
import net.minestom.server.extras.velocity.VelocityProxy
import net.minestom.server.instance.Instance
import net.minestom.server.ping.ResponseData
import net.minestom.server.timer.TaskSchedule
import java.io.BufferedReader
import java.io.File
import java.io.InputStreamReader
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path
import java.util.*
import kotlin.concurrent.thread
import kotlin.io.path.exists

private val queuedCommands = Collections.synchronizedList(mutableListOf<String>())
private val logger = ComponentLogger.logger("Server")
lateinit var consoleThread: Thread

val console = OperatorSender()
val server = MinecraftServer.init()
val pluginManager = PluginManager()
private lateinit var defaultInstance: Instance

fun main() {
    try {
        val start = System.nanoTime()
        Thread.currentThread().name = "Server thread"

        MinecraftServer.getExceptionManager().setExceptionHandler { e ->
            logger.error(e.message, e)
        }

        registerEvents()
        registerCommands()
        registerVelocity()

        pluginManager.registerPlugins()
        pluginManager.setupPlugins()

        Runtime.getRuntime().addShutdownHook(Thread {
            pluginManager.teardownPlugins()
        })

        val port = System.getenv("SERVER_PORT") ?: "25565"

        server.start("0.0.0.0", port.toInt())
        consoleThread = setupConsole()

        val time = System.nanoTime() - start
        logger.info("Done ({})! For help, type \"help\"", String.format(Locale.ROOT, "%.3fs", time.toDouble() / 1.0E9))
    } catch (exception: Exception) {
        logger.error("Airbrush has caught an exception! It may have crashed.")
        exception.printStackTrace()
    }
}

fun registerVelocity() {
    val secret = File("velocity.secret")
    val secretEnv = System.getenv("FABRIC_PROXY_SECRET")

    if (secretEnv != null || secret.exists()) {
        VelocityProxy.enable(secretEnv ?: secret.readText())
        logger.info("Registered Velocity Proxy")
        return
    }

    logger.warn("Velocity proxy could not be enabled! If this is in a dev env, ignore this.")
    MojangAuth.init()
}

fun registerCommands() {
    val manager = MinecraftServer.getCommandManager()

    // todo: improve command manager
    manager.register(Plugins())
    manager.register(Stop())
}

fun setupConsole(): Thread {
    return thread {
        val reader = BufferedReader(InputStreamReader(System.`in`, StandardCharsets.UTF_8))

        while (MinecraftServer.isStarted()) {
            val command = reader.readLine() ?: continue
            queuedCommands.add(command)
        }
    }
}

fun registerEvents() {
    val eventHandler = MinecraftServer.getGlobalEventHandler()
    val commandManager = MinecraftServer.getCommandManager()
    val scheduler = MinecraftServer.getSchedulerManager()

    eventHandler.addListener(AsyncPlayerConfigurationEvent::class.java) { event ->
        val player = event.player
        val ip = player.playerConnection.remoteAddress
        event.spawningInstance = defaultInstance
        logger.info("[${ip}] ${player.username} joined Airbrush")
    }

    eventHandler.addListener(PlayerDisconnectEvent::class.java) { event ->
        val player = event.player
        val ip = player.playerConnection.remoteAddress
        logger.info("[${ip}] ${player.username} left Airbrush")
    }

    eventHandler.addListener(PlayerSpawnEvent::class.java) { event ->
        val player = event.player
        player.teleport(Pos(0.0, 10.0, 0.0))
    }

    eventHandler.addListener(PlayerCommandEvent::class.java) { event ->
        val player = event.player
        logger.info("${player.username} executed /${event.command}")
    }

    val serverIconPath = Path.of("server-icon.png")
    val encodedFavicon = if (serverIconPath.exists()) {
        val bytes = Files.readAllBytes(serverIconPath)
        "data:image/png;base64," + Base64.getEncoder().encodeToString(bytes)
    } else {
        ""
    }

    eventHandler.addListener(ServerListPingEvent::class.java) { event ->
        event.responseData = ResponseData().apply {
            favicon = encodedFavicon
            description = ServerProperties.motd.mm()
            maxPlayer = ServerProperties.maxPlayers
        }
    }

    // TODO: This listener is executed before the chat filter's listener. Is this intentional?
    eventHandler.addListener(PlayerChatEvent::class.java) { event ->
        val player = event.player
        logger.info("${player.username}: ${event.message}")
    }

    scheduler.scheduleTask({
        while (queuedCommands.isNotEmpty()) {
            commandManager.execute(console, queuedCommands[0])
            queuedCommands.removeAt(0)
        }
    }, TaskSchedule.immediate(), TaskSchedule.tick(1))
}

fun registerDefaultInstance(instance: Instance) {
    defaultInstance = instance
}