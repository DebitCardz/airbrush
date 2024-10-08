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

import net.minestom.server.coordinate.Point
import java.util.*

data class Location(
    val x: Int,
    val y: Int,
    val z: Int,
)
fun Point.to(): Location {
    return Location(blockX(), blockY(), blockZ())
}

data class History(
    /* The UUID of the player who painted the pixel. */
    val player: UUID,
    /* The material ID of the pixel. */
    val material: Int,
    /* The UNIX timestamp of the pixel. */
    val timestamp: Long,
)

data class Pixel(
    /* The position of the pixel. */
    val position: Location,
    /* The world ID this player painted in. */
    val worldId: String,
    val changes: List<History>
)
