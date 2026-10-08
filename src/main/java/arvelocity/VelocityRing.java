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

/**
 * Implemented by the ring buffer sets of Accelerated Rendering. A set goes through cycles: builders are handed
 * out and filled, the compute programs run over all of them, the result is drawn, the set is reset.
 */
public interface VelocityRing {
    /**
     * True when the builders of the current cycle that hold Iris entity vertices write velocities. Asked by
     * the first of them and the same for all of them until the set is reset: whether a shader pack program
     * reads velocities may change at any time, the way the vertices of one cycle are processed may not.
     */
    boolean arvelocity$velocityCycle();

    /** True from the start of the compute phase of a cycle to its end when one of its builders writes velocities. */
    boolean arvelocity$writesVelocities();

    /**
     * A number for the entry of the per-draw transform buffer that is at this address now, to find the entry
     * again with; negative when there is no such entry. The address itself does not last: the buffer is mapped
     * again, elsewhere, whenever it grows.
     */
    long arvelocity$entry(long address);

    /**
     * True as long as the entry with that number can be changed: the cycle it was written in is still being
     * filled. False once the compute programs of that cycle have been given the buffer, and from then on: the
     * memory holds the entries of other cycles after that.
     */
    boolean arvelocity$open(long entry);

    /**
     * Zeroes the delta entry with that number, which leaves its draw without a motion vector. Nothing is
     * written for an entry that is not open.
     */
    void arvelocity$withdraw(long entry);
}
