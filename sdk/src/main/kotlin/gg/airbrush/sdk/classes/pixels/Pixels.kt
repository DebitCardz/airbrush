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

package gg.airbrush.sdk.classes.pixels

import com.zaxxer.hikari.HikariConfig
import com.zaxxer.hikari.HikariDataSource
import gg.ingot.iron.Iron
import gg.ingot.iron.annotations.Model
import gg.ingot.iron.ironSettings
import gg.ingot.iron.representation.DatabaseDriver
import gg.ingot.iron.sql.params.sqlParams
import gg.ingot.iron.strategies.NamingStrategy
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.launch
import net.minestom.server.MinecraftServer
import net.minestom.server.coordinate.Point
import net.minestom.server.item.Material
import org.intellij.lang.annotations.Language
import org.slf4j.LoggerFactory
import java.io.File
import java.sql.ResultSet
import java.time.Instant
import java.util.*
import kotlin.math.roundToInt
import kotlin.system.measureTimeMillis
import kotlin.time.measureTimedValue

data class PixelData(
    val id: Int? = null,
    val timestamp: Long,
    val worldId: String,
    val playerUuid: String,
    val x: Int,
    val y: Int,
    val z: Int,
    val material: Int,
) {
    companion object {
        val tableDefinition = """
            CREATE TABLE IF NOT EXISTS pixel_data (
                id INTEGER PRIMARY KEY AUTOINCREMENT,
                timestamp INTEGER NOT NULL,
                world_id TEXT NOT NULL,
                player_uuid TEXT NOT NULL,
                x INTEGER NOT NULL,
                y INTEGER NOT NULL,
                z INTEGER NOT NULL,
                material INTEGER NOT NULL
            );
        """.trimIndent()

        fun fromResult(rs: ResultSet): PixelData {
            return PixelData(
                id = rs.getInt("id"),
                timestamp = rs.getLong("timestamp"),
                worldId = rs.getString("world_id"),
                playerUuid = rs.getString("player_uuid"),
                x = rs.getInt("x"),
                y = rs.getInt("y"),
                z = rs.getInt("z"),
                material = rs.getInt("material")
            )
        }
    }
}

class Pixels {
    val data = File("data")

//    private val iron = Iron("jdbc:sqlite:${data.absolutePath}/pixel_data.db") {
//        namingStrategy = NamingStrategy.SNAKE_CASE
//        driver = DatabaseDriver.SQLITE
//    }

//    private val iron = Iron("jdbc:sqlite:${data.absolutePath}/pixel_data.db", ironSettings {
//        namingStrategy = NamingStrategy.SNAKE_CASE
//        driver = DatabaseDriver.SQLITE
//        minimumActiveConnections = 2
//        maximumConnections = 8
//    })

    private val hikariConf = HikariConfig().apply {
        jdbcUrl = "jdbc:sqlite:${data.absolutePath}/pixel_data.db"
        driverClassName = "org.sqlite.JDBC"
        maximumPoolSize = 8
    }
    private val ds = HikariDataSource(hikariConf)

    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    private val logger = LoggerFactory.getLogger(Pixels::class.java)

    init {
        if(!data.exists()) data.mkdir()
//        iron.connect()

        scope.launch {
            ds.connection.use { conn ->
                conn.createStatement().use { statement ->
                    statement.execute(PixelData.tableDefinition)
                }
            }
        }
    }

    suspend fun paintMulti(positions: List<Point>, player: UUID, material: Material, world: String) {
        val connection = ds.connection

        try {
            connection.autoCommit = false
            // id better be serial.
            val statement = connection.prepareStatement(sql("""
                INSERT INTO pixel_data (id, timestamp, world_id, player_uuid, x, y, z, material)
                    VALUES(DEFAULT, ?, ?, ?, ?, ?, ?, ?)
            """.trimIndent()))

            for((index, pos) in positions.withIndex()) {
                val data = PixelData(
                    worldId = world,
                    timestamp = System.currentTimeMillis(),
                    playerUuid = player.toString(),
                    x = pos.x().roundToInt(),
                    y = pos.y().roundToInt(),
                    z = pos.z().roundToInt(),
                    material = material.id()
                )

                statement.setLong(1, data.timestamp)
                statement.setString(2, data.worldId)
                statement.setString(3, data.playerUuid)
                statement.setInt(4, data.x)
                statement.setInt(5, data.y)
                statement.setInt(6, data.z)
                statement.setInt(7, data.material)

                statement.addBatch()

                // periodically commit the batch.
                if(index % 50 == 0) {
                    val (commands, time) = measureTimedValue {
                        val amount = statement.executeBatch()
                        connection.commit()

                        amount
                    }
                    logger.trace("Committed {} commands in {}ms.", commands.size, time.inWholeMilliseconds)
                }
            }

            // push remaining commands.
            val (commands, time) = measureTimedValue {
                val amount = statement.executeBatch()
                connection.commit()

                amount
            }
            logger.trace("Committed {} commands in {}ms.", commands.size, time.inWholeMilliseconds)
        } finally {
            if(connection != null) {
                if(!connection.autoCommit) {
                    connection.autoCommit = true
                }

                // manually close.
                connection.close()
            }
        }
    }

    suspend fun getHistoryByTime(threshold: Instant, world: String): Flow<PixelData> = flow {
        val history = iron.prepare("""
            SELECT * FROM pixel_data
            WHERE world_id = :worldId AND timestamp >= :timestamp
            ORDER BY timestamp ASC
        """.trimIndent(), sqlParams(
            "worldId" to world,
            "timestamp" to threshold.toEpochMilli()
        ))

        while (history.next())
            emit(history.get()!!)
    }

    suspend fun getHistoryAt(position: Point, limit: Int, world: String): List<PixelData> {
        val history: List<PixelData> = iron.prepare("""
                 SELECT * FROM pixel_data
                 WHERE world_id = :worldId AND x = :x AND y = :y AND z = :z
                 ORDER BY timestamp DESC
                 LIMIT :limit
            """.trimIndent(),
            sqlParams(
                "worldId" to world,
                "x" to position.x().roundToInt(),
                "y" to position.y().roundToInt(),
                "z" to position.z().roundToInt(),
                "limit" to limit,
            )
        ).all<PixelData>()
        return history
    }

    suspend fun wipeHistoryForWorld(world: String) {
        MinecraftServer.LOGGER.info("[SDK] Wiping history for world $world...")
        val time = measureTimeMillis {
            iron.prepare("""
                DELETE FROM pixel_data
                WHERE world_id = :worldId
            """.trimIndent(), sqlParams("worldId" to world))
        }
        MinecraftServer.LOGGER.info("[SDK] Wiped history for world $world in $time ms")
    }

    @Model
    data class MaterialPair(val material: Int, val count: Int)

    suspend fun getTopMaterials(player: UUID): List<Pair<Material, Int>> {
        val query = """
            SELECT material, COUNT(*) AS count
            FROM pixel_data
            WHERE player_uuid = ?
            GROUP BY material
            ORDER BY count DESC
            LIMIT 3;
        """.trimIndent()

        val topMaterials = iron.prepare(query, player).all<MaterialPair>()

        return topMaterials
            .map { Material.fromId(it.material)!! to it.count }
            .toList()
    }
}

private fun sql(@Language("SQL") sql: String) = sql