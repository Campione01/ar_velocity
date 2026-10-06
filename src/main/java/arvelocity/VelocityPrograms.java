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

import com.github.argon4w.acceleratedrendering.core.buffers.accelerated.AcceleratedRingBuffers;
import com.github.argon4w.acceleratedrendering.core.buffers.accelerated.builders.AcceleratedBufferBuilder;
import com.github.argon4w.acceleratedrendering.core.programs.dispatchers.TransformProgramDispatcher;
import com.github.argon4w.acceleratedrendering.core.programs.dispatchers.meshes.MeshUploadingProgramDispatcher;
import com.github.argon4w.acceleratedrendering.core.programs.overrides.IUploadingOverride;
import com.github.argon4w.acceleratedrendering.core.programs.overrides.ProgramOverride;

import java.util.IdentityHashMap;

/**
 * Which programs write the vertices of a builder. Accelerated Rendering keeps a pair of them, vertex transform
 * and mesh uploading, in a ProgramOverride; number 0 of a vertex format is its own pair. For the Iris entity
 * format this mod has a second pair that reads a delta entry behind every transform entry and writes the
 * velocity buffer. That pair is put in the place of number 0 where the programs are run, and only for
 * builders that have written those entries: every other builder, and every builder while no shader pack
 * program reads velocities, runs the programs of Accelerated Rendering as if this mod were not there.
 */
public final class VelocityPrograms {
    // Number 0 of a vertex format that builders with velocities were seen with, and the pair that takes its place.
    private static final IdentityHashMap<ProgramOverride, ProgramOverride> PAIRS = new IdentityHashMap<>();

    private static boolean failed;

    private VelocityPrograms() {
    }

    /** False once the two programs of this mod could not be had from Accelerated Rendering: nothing writes velocities after that. */
    public static boolean available() {
        return !failed;
    }

    /**
     * The velocity pair for number 0 of the Iris entity format, made from the programs of this mod when first
     * asked for; null when Accelerated Rendering does not have those programs.
     */
    public static ProgramOverride pair(ProgramOverride own) {
        ProgramOverride pair = PAIRS.get(own);
        if (pair == null && !failed) {
            try {
                // The same sizes of a varying and of a mesh info entry: the two shaders are those of Accelerated Rendering plus the velocity.
                pair = new ProgramOverride(0, new TransformProgramDispatcher.Default(VelocityShaders.TRANSFORM_KEY, own.getVaryingSize()),
                        new MeshUploadingProgramDispatcher.Default(VelocityShaders.UPLOADING_KEY, own.getMeshInfoSize()));
                PAIRS.put(own, pair);
            } catch (RuntimeException e) {
                // Accelerated Rendering throws for a program that is not among those it has loaded, here in the middle of a frame.
                failed = true;
                ArVelocity.LOGGER.error("ar_velocity: no motion vectors from now on; the compute programs of this mod are not among those Accelerated Rendering "
                        + "has loaded: {}", e.toString());
            }
        }
        return pair;
    }

    /** The pair the vertex transform of a builder is run with. */
    public static ProgramOverride of(AcceleratedBufferBuilder builder, ProgramOverride override) {
        if (override.overrideId() != 0 || !(builder instanceof VelocityWriter writer) || !writer.arvelocity$writesVelocities()) {
            return override;
        }
        ProgramOverride pair = pair(override);
        return pair == null ? override : pair;
    }

    /**
     * The mesh uploading program the dense meshes of a ring buffer set are run with, for one override. One
     * dispatch takes the meshes of all builders of the set that have that override, so the set answers for
     * them: its builders with velocities have named their number 0 here before its compute phase began, and
     * a builder that has that very object holds Iris entity vertices and writes velocities like them.
     */
    public static IUploadingOverride uploading(AcceleratedRingBuffers.Buffers ring, ProgramOverride override, IUploadingOverride uploading) {
        if (!(ring instanceof VelocityRing set) || !set.arvelocity$writesVelocities()) {
            return uploading;
        }
        ProgramOverride pair = PAIRS.get(override);
        return pair == null ? uploading : pair.uploading();
    }
}
