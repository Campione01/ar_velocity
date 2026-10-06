/*
 * ar_velocity: per-vertex motion vectors (at_velocity) for the compute path of Accelerated Rendering.
 * The motion vector math is adapted from the Iris velocity extension of Super Resolution,
 * Copyright (c) 2025-2026. 187J3X1-114514.
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program.  If not, see <https://www.gnu.org/licenses/>.
 */

package arvelocity;

import net.neoforged.api.distmarker.Dist;
import net.neoforged.fml.common.Mod;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

@Mod(value = ArVelocity.MOD_ID, dist = Dist.CLIENT)
public final class ArVelocity {
    public static final String MOD_ID = "ar_velocity";
    public static final String DISABLE_PROPERTY = "arvelocity.disable";

    public static final Logger LOGGER = LoggerFactory.getLogger(MOD_ID);

    public ArVelocity() {
        if (Boolean.getBoolean(DISABLE_PROPERTY)) {
            LOGGER.info("ar_velocity: switched off by -D{}=true, nothing is changed", DISABLE_PROPERTY);
        }
    }
}
