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

import arvelocity.mixin.acceleratedrendering.DecoratedRendererAccessor;
import arvelocity.mixin.acceleratedrendering.SheetedDecalTextureRendererAccessor;
import com.github.argon4w.acceleratedrendering.features.entities.AcceleratedEntityShadowRenderer;
import it.unimi.dsi.fastutil.ints.IntArrayList;
import net.irisshaders.iris.api.v0.IrisApi;
import net.irisshaders.iris.pathways.HandRenderer;
import net.irisshaders.iris.shadows.ShadowRenderingState;
import net.irisshaders.iris.uniforms.CapturedRenderingState;
import net.irisshaders.iris.vertices.ImmediateState;
import org.joml.Matrix4f;
import org.joml.Matrix4fc;

import java.util.ArrayList;
import java.util.function.LongSupplier;

/**
 * Render thread state: the frame counter, who is being drawn (owner) and by what (renderer), and the
 * computation of the per-draw delta from the history of the owner.
 *
 * A frame is the level render that starts first in a frame of the game. Any other level render of that frame
 * (another camera, a portal, a mirror, the six views of a panorama) draws the same owners from another place:
 * paired with the draws of the first they would get motion vectors as long as the two cameras are apart. Such
 * a render gets what the shadow pass gets: zero deltas, and nothing of it is looked up, remembered or counted.
 *
 * Frames are timed where the frame of the game begins: how far a draw goes in a frame depends on how long the
 * frame took, and one long frame must not make everything that moves look like something else.
 */
public final class VelocityContext {
    private static final int SWEEP_INTERVAL = 256;

    private static int frame;
    private static VelocityHistoryHolder owner;
    private static Object renderer;

    // Level renders of the frame of the game that is being drawn: whether one has started in it, how many are
    // running inside each other, and whether the first one is among them. Its draws are followed while it is
    // the innermost.
    private static boolean levelStarted;
    private static int levelDepth;
    private static boolean firstLevelRunning;
    private static boolean following;
    private static boolean warnedOtherLevel;

    // The camera position the poses of the current frame are relative to, and how far it went since the
    // previous frame.
    private static boolean cameraKnown;
    private static double cameraX;
    private static double cameraY;
    private static double cameraZ;
    private static float cameraDx;
    private static float cameraDy;
    private static float cameraDz;

    private static boolean failed;
    private static boolean warnedLate;

    private static LongSupplier clock = System::nanoTime;

    // When the frame of the game that is being drawn began, when the one did in which the frame that is
    // followed began, how long the frame before that one took (0: not known), and the ratio of the two lengths.
    private static long gameFrameNanos;
    private static boolean gameFrameTimed;
    private static long frameNanos;
    private static boolean frameTimed;
    private static long frameDuration;
    private static float frameRatio = 1.0f;

    // Entity and block entity renders nest (spawner cages, entities drawn by other renderers), and so do
    // renderer calls, so the outer value is saved and restored instead of cleared. An owner starts without a
    // renderer and ends with the renderer state it started in, whatever happened inside it.
    private static final ArrayList<VelocityHistoryHolder> outerOwners = new ArrayList<>();
    private static final ArrayList<Object> outerOwnerRenderers = new ArrayList<>();
    private static final IntArrayList outerOwnerDepths = new IntArrayList();
    private static final ArrayList<Object> outerRenderers = new ArrayList<>();

    private static final Matrix4f modelToView = new Matrix4f();

    private VelocityContext() {
    }

    public static int frame() {
        return frame;
    }

    /** Replaces the clock the frames are timed with: nanoseconds that never run backwards. For tests. */
    public static void clock(LongSupplier nanos) {
        clock = nanos;
    }

    /** The length of the frame that is being drawn over that of the frame before it, as {@link VelocityHistory#pair} takes it. */
    public static float frameRatio() {
        return frameRatio;
    }

    /** Starts a frame of the game: the level render that starts first from here on is the one that is followed. */
    public static void beginGameFrame() {
        gameFrameNanos = clock.getAsLong();
        gameFrameTimed = true;
        VelocityAttribute.forgetAbandoned();
        // A level render that ended with an exception did not say that it ended.
        levelStarted = false;
        levelDepth = 0;
        firstLevelRunning = false;
        following = false;
    }

    /**
     * A level render starts, with poses relative to the given camera position. The first one of the frame of
     * the game starts a frame here; the camera movement is the one since the first of the frame before.
     */
    public static void beginLevel(double x, double y, double z) {
        levelDepth++;
        if (levelStarted) {
            following = false;
            if (!warnedOtherLevel) {
                warnedOtherLevel = true;
                ArVelocity.LOGGER.warn("ar_velocity: the level is drawn more than once in a frame (another camera, a portal, a mirror or a panorama); only "
                        + "what the first of these draws gets motion vectors, the others get none");
            }
            return;
        }
        levelStarted = true;
        firstLevelRunning = true;
        following = true;
        boolean finite = Double.isFinite(x) && Double.isFinite(y) && Double.isFinite(z);
        if (finite && cameraKnown) {
            cameraDx = (float) (x - cameraX);
            cameraDy = (float) (y - cameraY);
            cameraDz = (float) (z - cameraZ);
        } else {
            cameraDx = 0.0f;
            cameraDy = 0.0f;
            cameraDz = 0.0f;
        }
        if (finite) {
            cameraX = x;
            cameraY = y;
            cameraZ = z;
            cameraKnown = true;
        }
        advance();
    }

    /** The level render that started last has ended. */
    public static void endLevel() {
        if (levelDepth > 0) {
            levelDepth--;
        }
        if (levelDepth == 0) {
            firstLevelRunning = false;
        }
        following = firstLevelRunning && levelDepth == 1;
    }

    /** Starts a frame of the game and its level render, the camera where it was in the frame before. */
    public static void beginFrame() {
        beginGameFrame();
        beginLevel(Double.NaN, Double.NaN, Double.NaN);
    }

    private static void advance() {
        frame++;
        long duration = frameTimed && gameFrameTimed ? gameFrameNanos - frameNanos : 0L;
        frameRatio = VelocityHistory.ratio(duration, frameDuration);
        frameDuration = duration;
        frameNanos = gameFrameNanos;
        frameTimed = gameFrameTimed;
        // A render that ended with an exception leaves the stacks unbalanced; every frame starts clean.
        outerOwners.clear();
        outerOwnerRenderers.clear();
        outerOwnerDepths.clear();
        outerRenderers.clear();
        owner = null;
        renderer = null;
        if (frame % SWEEP_INTERVAL == 0) {
            VelocityHistory.sweep(frame);
        }
    }

    public static void pushOwner(Object candidate) {
        outerOwners.add(owner);
        outerOwnerRenderers.add(renderer);
        outerOwnerDepths.add(outerRenderers.size());
        owner = candidate instanceof VelocityHistoryHolder holder ? holder : null;
        renderer = null;
    }

    public static void popOwner() {
        int last = outerOwners.size() - 1;
        if (last < 0) {
            owner = null;
            renderer = null;
            outerRenderers.clear();
            return;
        }
        owner = outerOwners.remove(last);
        renderer = outerOwnerRenderers.remove(last);
        int depth = outerOwnerDepths.removeInt(last);
        while (outerRenderers.size() > depth) {
            outerRenderers.remove(outerRenderers.size() - 1);
        }
    }

    public static void pushRenderer(Object candidate) {
        outerRenderers.add(renderer);
        renderer = innermost(candidate);
    }

    public static void popRenderer() {
        renderer = outerRenderers.isEmpty() ? null : outerRenderers.remove(outerRenderers.size() - 1);
    }

    public static Object currentOwner() {
        return owner;
    }

    public static Object currentRenderer() {
        return renderer;
    }

    // Accelerated Rendering wraps a renderer in a new decorating object for every call that goes through an
    // outline, sprite or decal consumer; the wrapped object is the one that stays the same between frames.
    private static Object innermost(Object candidate) {
        Object inner = candidate;
        for (int depth = 0; depth < 16; depth++) {
            if (inner instanceof DecoratedRendererAccessor decorated) {
                inner = decorated.arvelocity$renderer();
            } else if (inner instanceof SheetedDecalTextureRendererAccessor decal) {
                inner = decal.arvelocity$renderer();
            } else {
                break;
            }
        }
        // The blob shadow is rebuilt from the blocks under the entity every frame and does not move with the
        // entity: it gets no delta, which leaves it to the shader pack's handling of static geometry.
        return inner instanceof AcceleratedEntityShadowRenderer ? null : inner;
    }

    /**
     * Fills the (zeroed) delta entry of the transform that was just begun: D' = M_now * P_now - M_prev * P_prev,
     * with P the transform of this draw and M the gbuffer model view matrix of Iris. The entry stays zero when
     * the draw has no owner or renderer, in the shadow and hand passes, outside shader pack level rendering,
     * in every level render but the first of a frame of the game, and when the history of the owner has no
     * draw of the frame before to pair this one with.
     *
     * @param ring the ring buffer set the entry is in, which can take it back until its compute programs run;
     *             null when nothing can
     */
    public static void writeDelta(long address, Matrix4f transform, VelocityRing ring) {
        VelocityHistoryHolder holder = owner;
        Object key = renderer;
        if (!following || holder == null || key == null || failed) {
            return;
        }
        Matrix4fc modelView;
        try {
            if (!ImmediateState.isRenderingLevel || ShadowRenderingState.areShadowsCurrentlyBeingRendered() || HandRenderer.INSTANCE.isActive()
                    || !IrisApi.getInstance().isShaderPackInUse()) {
                return;
            }
            modelView = CapturedRenderingState.INSTANCE.getGbufferModelView();
        } catch (LinkageError e) {
            // The check at startup (VelocityLinkage) should have kept the mod from getting here.
            failed = true;
            ArVelocity.LOGGER.error("ar_velocity: no motion vectors from now on; the installed Iris does not have what this build was made for: {}", e.toString());
            return;
        }
        if (modelView == null) {
            return;
        }
        // Iris keeps a reference to the live matrix of the frame; the product is a copy.
        Matrix4f now = modelToView.set(modelView).mul(transform);
        if (!now.isFinite()) {
            return;
        }
        long refused = VelocityHistory.refused();
        Matrix4f previous = holder.arvelocity$history().pair(key, frame, now, transform, cameraDx, cameraDy, cameraDz, frameRatio, ring, address);
        if (previous != null) {
            VelocityDeltas.write(address, now, previous);
        }
        if (VelocityHistory.refused() != refused && !warnedLate) {
            warnedLate = true;
            ArVelocity.LOGGER.warn("ar_velocity: a motion vector that the first draw of a renderer ({}, for {}) was given as its only draw could not be taken back "
                    + "when a second draw followed: its vertices had gone to the compute programs. That draw may have a false motion vector in this one frame; for "
                    + "this owner the renderer is held to the rules for several draws from now on (reported once)", key.getClass().getName(), holder.getClass().getName());
        }
    }
}
