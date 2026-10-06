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

import net.minecraft.resources.ResourceLocation;

/**
 * The two compute shaders of this mod: the ones of Accelerated Rendering that write Iris entity vertices, with
 * the velocity written next to the vertex. Accelerated Rendering loads them as two more programs of its own,
 * under keys of this mod; the programs it loads from its own two files stay what they are (VelocityPrograms).
 */
public final class VelocityShaders {
    public static final String NAMESPACE = ArVelocity.MOD_ID;

    public static final ResourceLocation TRANSFORM_KEY = ResourceLocation.fromNamespaceAndPath(NAMESPACE, "compat_entity_vertex_transform_iris_velocity");
    public static final ResourceLocation UPLOADING_KEY = ResourceLocation.fromNamespaceAndPath(NAMESPACE, "compat_entity_mesh_uploading_iris_velocity");

    public static final ResourceLocation TRANSFORM_FILE = ResourceLocation.fromNamespaceAndPath(NAMESPACE, "shaders/compat/transform/iris_entity_vertex_transform_shader.compute");
    public static final ResourceLocation UPLOADING_FILE = ResourceLocation.fromNamespaceAndPath(NAMESPACE, "shaders/compat/uploading/iris_entity_mesh_uploading_shader.compute");

    private VelocityShaders() {
    }
}
