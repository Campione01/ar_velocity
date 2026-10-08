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

import com.github.argon4w.acceleratedrendering.core.buffers.memory.VertexLayout;
import com.mojang.blaze3d.vertex.VertexFormat;
import com.mojang.blaze3d.vertex.VertexFormatElement;
import net.irisshaders.iris.vertices.IrisVertexFormats;
import org.lwjgl.opengl.GL11C;
import org.lwjgl.opengl.GL15C;
import org.lwjgl.opengl.GL30C;
import org.lwjgl.opengl.GL32C;
import org.lwjgl.opengl.GL43C;
import org.lwjgl.opengl.GL45C;

import java.nio.ByteBuffer;

/**
 * The "sidecar": at_velocity is not part of any vertex format. Two compute programs of this mod, which are
 * the two of Accelerated Rendering that write Iris entity vertices, also write three floats per vertex into
 * a separate buffer, at the index of the vertex in the shared vertex buffer, and that buffer feeds one more
 * attribute of the vertex array Accelerated Rendering draws with. They run in the place of its own two while
 * a shader pack program reads the attribute (VelocityAttribute, VelocityPrograms). Everything that does not
 * go through them leaves the attribute array disabled and reads the constant (0, 0, 0).
 */
public final class VelocitySidecar {
    public static final String ATTRIBUTE_NAME = "at_velocity";

    /** Attribute location, and index of the vertex buffer binding that feeds it. The entity format uses 0..8. */
    public static final int ATTRIBUTE = 9;

    /** Shader storage binding of the velocity buffer in the two writer programs; they use no other free one. */
    public static final int OUTPUT_BINDING = 6;

    /** Three floats per vertex of the shared vertex buffer. */
    public static final int VELOCITY_SIZE = 12;

    // The compute shaders of this mod are those of Accelerated Rendering 1.0.14, whose entity vertex has this size.
    private static final int VERTEX_SIZE = 56;

    // GL_VERTEX_BINDING_STRIDE of a binding point of a new vertex array (OpenGL 4.6, table 23.4).
    private static final int DEFAULT_STRIDE = 16;

    private static volatile Boolean supported;

    // Set by the thread that loads the resources, read by the one that draws.
    private static volatile boolean programsListed;
    private static boolean announced;

    // Why nothing writes velocities, from whichever thread found it, until the thread that draws has said it.
    private static volatile String refusal;

    private static VertexLayout velocityLayout;
    private static VertexLayout otherLayout;

    // The buffer of this mod that is on the binding point, 0 when there is none, and what it replaced.
    private static int boundBuffer;
    private static int savedBuffer;
    private static long savedOffset;
    private static long savedSize;

    private static boolean announcedBuffer;

    private VelocitySidecar() {
    }

    /**
     * True when the Iris entity format is the one Accelerated Rendering 1.0.14 builds, with attribute 9 free.
     * This decides whether velocities are written; the attribute location is bound without it (VelocityAttribute).
     */
    public static boolean supported() {
        Boolean known = supported;
        if (known == null) {
            supported = known = entityFormatFits();
        }
        return known;
    }

    private static boolean entityFormatFits() {
        VertexFormat entity;
        try {
            entity = IrisVertexFormats.ENTITY;
        } catch (LinkageError e) {
            refusal = "ar_velocity: inactive, no motion vectors; the installed Iris does not have what this build was made for: " + e;
            return false;
        }
        boolean fits = entity.getVertexSize() == VERTEX_SIZE && entity.getElements().size() <= ATTRIBUTE;
        if (!fits) {
            refusal = "ar_velocity: inactive, no motion vectors; expected the " + VERTEX_SIZE + "-byte Iris entity vertex format of Accelerated Rendering 1.0.14 "
                    + "with at most " + ATTRIBUTE + " elements, found " + entity.getVertexSize() + " bytes and " + entity.getElements().size() + " elements\n" + entity;
        }
        return fits;
    }

    /** The two programs of this mod are in the list Accelerated Rendering compiles its programs from. Called on whichever thread loads the resources. */
    public static void programsListed() {
        programsListed = true;
    }

    /**
     * Says that the mod is active, once, in the first frame of the game after its programs were listed, or why
     * it is not, in the first frame after that was found, on the thread that draws: a line that is logged
     * while the resources load, by one of the threads that load them, is not sure to reach the log.
     */
    public static void announce() {
        String reason = refusal;
        if (reason != null) {
            refusal = null;
            ArVelocity.LOGGER.error(reason);
        }
        if (announced || !programsListed) {
            return;
        }
        announced = true;
        ArVelocity.LOGGER.info("ar_velocity: active; {} is vertex attribute {}, fed from a per-vertex buffer (shader storage binding {}) that two programs "
                + "of this mod write in the place of the Iris entity transform and mesh uploading programs of Accelerated Rendering, while a shader pack "
                + "program reads the attribute", ATTRIBUTE_NAME, ATTRIBUTE, OUTPUT_BINDING);
        ArVelocity.LOGGER.info("ar_velocity: compute programs {} and {} are loaded next to those of Accelerated Rendering", VelocityShaders.TRANSFORM_KEY,
                VelocityShaders.UPLOADING_KEY);
    }

    public static void announceBuffer() {
        if (!announcedBuffer) {
            announcedBuffer = true;
            ArVelocity.LOGGER.info("ar_velocity: velocity buffer in use ({} bytes per vertex of the entity vertex buffer)", VELOCITY_SIZE);
        }
    }

    /**
     * True for the vertex layout of the Iris entity format. A builder with this layout was created while
     * Accelerated Rendering was in its Iris mode, so its vertices are written by the two programs that also
     * write velocities. Decided from the layout itself, not from what Iris is doing at the time of the call.
     */
    public static boolean isVelocityLayout(VertexLayout layout) {
        if (layout == null || layout == otherLayout) {
            return false;
        }
        if (layout == velocityLayout) {
            return true;
        }
        if (supported() && matches(layout, IrisVertexFormats.ENTITY)) {
            velocityLayout = layout;
            return true;
        }
        otherLayout = layout;
        return false;
    }

    private static boolean matches(VertexLayout layout, VertexFormat format) {
        if (layout.getSize() != format.getVertexSize()) {
            return false;
        }
        for (VertexFormatElement element : format.getElements()) {
            if (!layout.containsElement(element) || layout.getElementOffset(element) != format.getOffset(element)) {
                return false;
            }
        }
        return true;
    }

    /** Size of the velocity buffer for a shared vertex buffer of the given size: one slot for every vertex index. */
    public static long bytesFor(long vertexBufferBytes) {
        return (vertexBufferBytes / VERTEX_SIZE + 1L) * VELOCITY_SIZE;
    }

    /**
     * Binds the velocity buffer for the compute dispatches that follow. What was bound to the binding point
     * before is remembered until restoreOutput(); the generic binding point is left as it was.
     */
    public static void bindOutput(int buffer) {
        int generic = GL11C.glGetInteger(GL43C.GL_SHADER_STORAGE_BUFFER_BINDING);
        int current = GL30C.glGetIntegeri(GL43C.GL_SHADER_STORAGE_BUFFER_BINDING, OUTPUT_BINDING);
        if (boundBuffer == 0 || current != boundBuffer) {
            savedBuffer = current;
            savedOffset = GL32C.glGetInteger64i(GL43C.GL_SHADER_STORAGE_BUFFER_START, OUTPUT_BINDING);
            savedSize = GL32C.glGetInteger64i(GL43C.GL_SHADER_STORAGE_BUFFER_SIZE, OUTPUT_BINDING);
        }
        GL30C.glBindBufferBase(GL43C.GL_SHADER_STORAGE_BUFFER, OUTPUT_BINDING, buffer);
        GL15C.glBindBuffer(GL43C.GL_SHADER_STORAGE_BUFFER, generic);
        boundBuffer = buffer;
    }

    /**
     * Puts back what bindOutput() replaced, once the last dispatch that writes velocities is done. Does
     * nothing when the binding point no longer holds the buffer of this mod.
     */
    public static void restoreOutput() {
        int ours = boundBuffer;
        if (ours == 0) {
            return;
        }
        boundBuffer = 0;
        if (GL30C.glGetIntegeri(GL43C.GL_SHADER_STORAGE_BUFFER_BINDING, OUTPUT_BINDING) != ours) {
            return;
        }
        int generic = GL11C.glGetInteger(GL43C.GL_SHADER_STORAGE_BUFFER_BINDING);
        if (savedBuffer == 0 || !GL15C.glIsBuffer(savedBuffer)) {
            GL30C.glBindBufferBase(GL43C.GL_SHADER_STORAGE_BUFFER, OUTPUT_BINDING, 0);
        } else if (savedOffset == 0L && savedSize == 0L) {
            GL30C.glBindBufferBase(GL43C.GL_SHADER_STORAGE_BUFFER, OUTPUT_BINDING, savedBuffer);
        } else {
            GL30C.glBindBufferRange(GL43C.GL_SHADER_STORAGE_BUFFER, OUTPUT_BINDING, savedBuffer, savedOffset, savedSize);
        }
        GL15C.glBindBuffer(GL43C.GL_SHADER_STORAGE_BUFFER, generic);
    }

    /** Fills the whole buffer with zeros: "no data" for every vertex. No binding point is touched. */
    public static void clear(int buffer) {
        GL45C.glClearNamedBufferData(buffer, GL30C.GL_R32F, GL11C.GL_RED, GL11C.GL_FLOAT, (ByteBuffer) null);
    }

    /**
     * Makes the buffer the source of attribute 9 of a vertex array (three floats, 12 bytes apart, from byte
     * 0). For buffer 0 the attribute is put back to what it is in a vertex array nobody has touched: array
     * off, no buffer, the format and the stride OpenGL starts with. Accelerated Rendering never sets this
     * attribute, so that is the state it left it in. Direct state access: no binding point of the context is
     * touched, in particular not GL_ARRAY_BUFFER and not the bound vertex array.
     */
    public static void attach(int vertexArray, int buffer) {
        if (buffer != 0) {
            GL45C.glVertexArrayVertexBuffer(vertexArray, ATTRIBUTE, buffer, 0L, VELOCITY_SIZE);
            GL45C.glVertexArrayAttribFormat(vertexArray, ATTRIBUTE, 3, GL11C.GL_FLOAT, false, 0);
            GL45C.glVertexArrayAttribBinding(vertexArray, ATTRIBUTE, ATTRIBUTE);
            GL45C.glEnableVertexArrayAttrib(vertexArray, ATTRIBUTE);
        } else {
            GL45C.glDisableVertexArrayAttrib(vertexArray, ATTRIBUTE);
            GL45C.glVertexArrayAttribFormat(vertexArray, ATTRIBUTE, 4, GL11C.GL_FLOAT, false, 0);
            GL45C.glVertexArrayVertexBuffer(vertexArray, ATTRIBUTE, 0, 0L, DEFAULT_STRIDE);
        }
    }
}
