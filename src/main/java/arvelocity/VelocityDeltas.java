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

import com.github.argon4w.acceleratedrendering.core.buffers.accelerated.builders.AcceleratedBufferBuilder;
import org.joml.Matrix4f;
import org.lwjgl.system.MemoryUtil;

/**
 * Layout of a delta entry. It lives in the per-draw transform ("sharing") buffer of Accelerated Rendering,
 * directly behind the transform entry it belongs to: the transform of a draw is entry k, its delta entry k + 1.
 *
 * Only the first 64 bytes are used, where a transform entry has its mat4: 16 floats, column by column.
 * Columns 0..2 and the xyz of column 3 hold the 3x4 matrix D' = M_now * P_now - M_prev * P_prev; the w of
 * column 3 is 1 for an entry with valid history; everything is 0 for an entry without.
 */
public final class VelocityDeltas {
    /** One entry of the sharing buffer: mat4 and mat3 under std430. */
    public static final long ENTRY_SIZE = AcceleratedBufferBuilder.SHARING_SIZE;
    public static final long MATRIX_SIZE = 64L;
    public static final float VALID = 1.0f;

    private VelocityDeltas() {
    }

    public static void clear(long address) {
        MemoryUtil.memSet(address, 0, ENTRY_SIZE);
    }

    public static void write(long address, Matrix4f now, Matrix4f previous) {
        MemoryUtil.memPutFloat(address, now.m00() - previous.m00());
        MemoryUtil.memPutFloat(address + 4L, now.m01() - previous.m01());
        MemoryUtil.memPutFloat(address + 8L, now.m02() - previous.m02());
        MemoryUtil.memPutFloat(address + 12L, 0.0f);

        MemoryUtil.memPutFloat(address + 16L, now.m10() - previous.m10());
        MemoryUtil.memPutFloat(address + 20L, now.m11() - previous.m11());
        MemoryUtil.memPutFloat(address + 24L, now.m12() - previous.m12());
        MemoryUtil.memPutFloat(address + 28L, 0.0f);

        MemoryUtil.memPutFloat(address + 32L, now.m20() - previous.m20());
        MemoryUtil.memPutFloat(address + 36L, now.m21() - previous.m21());
        MemoryUtil.memPutFloat(address + 40L, now.m22() - previous.m22());
        MemoryUtil.memPutFloat(address + 44L, 0.0f);

        MemoryUtil.memPutFloat(address + 48L, now.m30() - previous.m30());
        MemoryUtil.memPutFloat(address + 52L, now.m31() - previous.m31());
        MemoryUtil.memPutFloat(address + 56L, now.m32() - previous.m32());
        MemoryUtil.memPutFloat(address + 60L, VALID);
    }
}
