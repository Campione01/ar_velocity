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

import com.mojang.blaze3d.platform.GlStateManager;
import com.mojang.blaze3d.shaders.Uniform;
import com.mojang.blaze3d.vertex.VertexFormat;
import org.lwjgl.opengl.GL11C;
import org.lwjgl.opengl.GL20C;

import java.lang.ref.Reference;
import java.lang.ref.ReferenceQueue;
import java.lang.ref.WeakReference;
import java.util.HashSet;
import java.util.Set;

/**
 * Where at_velocity sits in the programs the game links. A program that declares the attribute without a
 * location gets one from the linker, and that can be the location of an array its vertex format enables: the
 * shader then reads a normal or a colour as a motion vector. So the name is bound to the one location that the
 * velocity buffer feeds, in every program whose format leaves that location alone, whatever the format is and
 * whether or not anything writes velocities. Where no buffer is attached the program reads (0, 0, 0).
 *
 * The other way round, a program that does not have the attribute leaves the location to the linker, which may
 * put there whatever else the program reads without an array of its own. So the programs that do have it at
 * that location are counted, from link to close, and nothing is written or attached while there is none.
 *
 * Not every program is closed: one whose owner fails half-way through building its set of programs is just
 * dropped. The count therefore holds its programs weakly, and one that nothing refers to any more, which
 * nothing can draw with either, leaves the count when the garbage collector has found it.
 *
 * Nothing in this class needs Iris or Accelerated Rendering.
 */
public final class VelocityAttribute {
    private static final Set<String> warned = new HashSet<>();
    private static boolean announced;

    // Programs that are alive and have at_velocity at the location the velocity buffer feeds: how many, and
    // which. Changed on the render thread only; the number may be read from any thread.
    private static int readers;
    private static final Set<Reference<?>> COUNTED = new HashSet<>();
    private static final ReferenceQueue<Object> DROPPED = new ReferenceQueue<>();

    private VelocityAttribute() {
    }

    /**
     * To be called for a program that the constructor of its object has linked. Not null when the program has
     * at_velocity at the location the velocity buffer feeds: it is counted then, until what is returned is
     * handed to {@link #closed} or the object is found to be dropped.
     *
     * @param shader the object that stands for the program and is needed to draw with it
     */
    public static Object linked(Object shader, int program, Object name) {
        forgetAbandoned();
        // The linker failed and the game went on: such a program has no locations to ask for.
        if (GlStateManager.glGetProgrami(program, GL20C.GL_LINK_STATUS) != GL11C.GL_TRUE) {
            return null;
        }
        if (Uniform.glGetAttribLocation(program, VelocitySidecar.ATTRIBUTE_NAME) != VelocitySidecar.ATTRIBUTE) {
            return null;
        }
        return count(shader, name);
    }

    /** Counts the object as a program that reads velocities; what is returned takes it out of the count again. */
    public static Object count(Object shader, Object name) {
        Reference<?> reader = new WeakReference<>(shader, DROPPED);
        COUNTED.add(reader);
        if (readers++ == 0) {
            ArVelocity.LOGGER.info("ar_velocity: a program that reads {} from attribute {} is in use (first: {})", VelocitySidecar.ATTRIBUTE_NAME, VelocitySidecar.ATTRIBUTE, name);
        }
        return reader;
    }

    /** To be called for what {@link #linked} has returned, when the program is deleted. */
    public static void closed(Object reader) {
        if (reader instanceof Reference<?> counted && COUNTED.remove(counted)) {
            counted.clear();
            gone();
        }
        forgetAbandoned();
    }

    /** Takes the programs out of the count whose objects were dropped without being closed and have been collected since. */
    public static void forgetAbandoned() {
        Reference<?> dropped;
        while ((dropped = DROPPED.poll()) != null) {
            if (COUNTED.remove(dropped)) {
                gone();
            }
        }
    }

    private static void gone() {
        if (readers > 0 && --readers == 0) {
            ArVelocity.LOGGER.info("ar_velocity: no program reads {} any more", VelocitySidecar.ATTRIBUTE_NAME);
        }
    }

    /** How many programs are counted. */
    public static int readers() {
        return readers;
    }

    /** True while a program is alive that has at_velocity at the location the velocity buffer feeds. */
    public static boolean hasReaders() {
        return readers > 0;
    }

    /** True when the constructor of ShaderInstance, which binds the element names of the format to 0, 1, 2, ..., does not reach the location. */
    public static boolean isFree(VertexFormat format) {
        return format.getElementAttributeNames().size() <= VelocitySidecar.ATTRIBUTE;
    }

    /** To be called after the constructor has bound the names of the format and before it links the program. */
    public static void bindBeforeLink(int program, VertexFormat format, Object name) {
        if (!isFree(format)) {
            if (warned.add(String.valueOf(name))) {
                ArVelocity.LOGGER.warn("ar_velocity: {} is left to the linker in program {}: its vertex format has {} elements and uses attribute {} itself",
                        VelocitySidecar.ATTRIBUTE_NAME, name, format.getElementAttributeNames().size(), VelocitySidecar.ATTRIBUTE);
            }
            return;
        }
        Uniform.glBindAttribLocation(program, VelocitySidecar.ATTRIBUTE, VelocitySidecar.ATTRIBUTE_NAME);
        if (!announced) {
            announced = true;
            ArVelocity.LOGGER.info("ar_velocity: binding {} to attribute {} in every program whose vertex format has at most {} elements (first: {})",
                    VelocitySidecar.ATTRIBUTE_NAME, VelocitySidecar.ATTRIBUTE, VelocitySidecar.ATTRIBUTE, name);
        }
    }
}
