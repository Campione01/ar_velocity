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
import arvelocity.mixin.acceleratedrendering.ItemLayerColorsAccessor;
import arvelocity.mixin.acceleratedrendering.SheetedDecalTextureRendererAccessor;
import com.github.argon4w.acceleratedrendering.features.entities.AcceleratedEntityShadowRenderer;
import com.github.argon4w.acceleratedrendering.features.items.contexts.AcceleratedModelRenderContext;
import it.unimi.dsi.fastutil.ints.IntArrayList;
import net.irisshaders.iris.api.v0.IrisApi;
import net.irisshaders.iris.pathways.HandRenderer;
import net.irisshaders.iris.shadows.ShadowRenderingState;
import net.irisshaders.iris.uniforms.CapturedRenderingState;
import net.irisshaders.iris.vertices.ImmediateState;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.entity.BlockEntity;
import org.joml.Matrix4f;
import org.joml.Matrix4fc;

import java.util.ArrayList;

/**
 * Render thread state: the frame counter, who is being drawn (owner), by what (renderer) and for what (thing),
 * and the computation of the per-draw delta from the history of the owner.
 *
 * A frame is the level render the game itself makes in a frame of the game: the one GameRenderer.render gets
 * to through its own call of GameRenderer.renderLevel and that method's own call of LevelRenderer.renderLevel.
 * Any other level render (another camera, a portal, a mirror, the six views of a panorama) draws the same
 * owners from another place: paired with the draws of the game's own they would get motion vectors as long as
 * the two cameras are apart. Such a render gets what the shadow pass gets: zero deltas, and nothing of it is
 * looked up, remembered or counted. A mod that makes the game's own call twice makes two of these, which
 * nothing tells apart: the first is followed. In a frame in which the game's own way is not gone, the level
 * render that starts first is followed. Whether it will be gone is not known when another level render starts
 * first: it is expected where the game says that it draws the level in the frame, unless it did not come the
 * last time the game said so.
 *
 * Two frames are compared by the timer of the game: how far a draw goes in a frame depends on how much time
 * the game gave that frame, and one long frame must not make everything that moves look like something else.
 * A pause is real time: a frame that begins more than a second after the one before it has no history.
 */
public final class VelocityContext {
    private static final int SWEEP_INTERVAL = 256;

    /** Nanoseconds of game time in a tick of the timer of the game. */
    private static final double TICK_NANOS = 50_000_000.0;

    /**
     * The steps of the game's own way to the level render of a frame, in their order: GameRenderer.render calls
     * GameRenderer.renderLevel, that method runs, it calls LevelRenderer.renderLevel, that method runs.
     */
    public static final int GAME_CALL = 1;
    public static final int GAME_LEVEL = 2;
    public static final int LEVEL_CALL = 3;
    public static final int LEVEL = 4;
    private static final int ELSEWHERE = 0;

    // What is followed: the level render the game itself makes, or the one that starts first in a frame.
    private static final int OWN = 1;
    private static final int FIRST = 2;

    private static final VelocityHistory.Things STACKS = VelocityContext::changed;

    private static int frame;
    private static VelocityHistoryHolder owner;
    private static Object renderer;
    private static Object thing;

    // How far the game's own way has been gone.
    private static int path;

    // The frame of the game that is being drawn: whether the game says that it draws the level in it and
    // whether its own level render is expected; whether a level render has begun in it, the game's own, and
    // one that was left alone because the game's own was expected.
    private static boolean levelWanted;
    private static boolean ownDue;
    private static boolean levelBegan;
    private static boolean ownBegan;
    private static boolean waived;
    // The game's own level render did not come the last time the game said it would draw the level and a level render began.
    private static boolean ownMissed;

    // Level renders of the frame of the game that is being drawn: whether the one that is followed has
    // started, how many are running inside each other, and whether the followed one is among them. Its draws
    // are followed while it is the innermost.
    private static boolean followed;
    private static int levelDepth;
    private static boolean followedRunning;
    private static boolean following;
    private static int followedKind;
    private static boolean toldOwn;
    private static boolean warnedOtherLevel;
    private static boolean warnedNoOwn;

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
    private static boolean toldCycles;
    private static boolean warnedLate;

    // The time of the game, counted from the first frame, and the real time at which the frame of the game
    // began; what the two were when the frame that is followed began, how long by the time of the game the
    // frame before that one took (0: not known), and the ratio of the two lengths.
    private static long gameNanos;
    private static long realNanos;
    private static long frameNanos;
    private static long frameRealNanos;
    private static boolean frameTimed;
    private static long frameDuration;
    private static float frameRatio = 1.0f;

    // Entity and block entity renders nest (spawner cages, entities drawn by other renderers), and so do
    // renderer calls, so the outer value is saved and restored instead of cleared. An owner starts without a
    // renderer and ends with the renderer state it started in, whatever happened inside it.
    private static final ArrayList<VelocityHistoryHolder> outerOwners = new ArrayList<>();
    private static final ArrayList<Object> outerOwnerRenderers = new ArrayList<>();
    private static final ArrayList<Object> outerOwnerThings = new ArrayList<>();
    private static final IntArrayList outerOwnerDepths = new IntArrayList();
    private static final ArrayList<Object> outerRenderers = new ArrayList<>();
    private static final ArrayList<Object> outerThings = new ArrayList<>();

    private static final Matrix4f modelToView = new Matrix4f();

    private VelocityContext() {
    }

    public static int frame() {
        return frame;
    }

    /** The length of the frame that is being drawn over that of the frame before it, as {@link VelocityHistory#pair} takes it. */
    public static float frameRatio() {
        return frameRatio;
    }

    /**
     * Starts a frame of the game, whether or not the level is drawn in it.
     *
     * @param gameTicks what the timer of the game says went by since the frame before, in ticks
     * @param nanos     the real time now, in nanoseconds that never run backwards
     * @param level     the game says that it draws the level in this frame
     */
    public static void beginGameFrame(float gameTicks, long nanos, boolean level) {
        // Only what is a time is counted: not what is not a number, negative or without end.
        if (gameTicks > 0.0f && gameTicks < Float.POSITIVE_INFINITY) {
            gameNanos += Math.round(gameTicks * TICK_NANOS);
        }
        realNanos = nanos;
        if (levelBegan) {
            if (!ownBegan && !warnedNoOwn) {
                warnedNoOwn = true;
                ArVelocity.LOGGER.warn("ar_velocity: a frame of the game had a level render, but none made by the game's own call (GameRenderer.render -> renderLevel "
                        + "-> LevelRenderer.renderLevel). In such a frame the level render that starts first gets the motion vectors; where the game's own call was "
                        + "expected, the first such frame gets none (reported once)");
            }
            if (ownBegan) {
                ownMissed = false;
            } else if (levelWanted) {
                ownMissed = true;
            }
        }
        VelocityAttribute.forgetAbandoned();
        VelocitySidecar.announce();
        // A level render that ended with an exception did not say that it ended.
        followed = false;
        levelDepth = 0;
        followedRunning = false;
        following = false;
        levelBegan = false;
        ownBegan = false;
        waived = false;
        levelWanted = level;
        ownDue = level && !ownMissed;
    }

    /**
     * A step of the game's own way to its level render begins. It is one only when the step before it is where
     * things are: whatever else leads here (a second view, drawn from wherever) is not on that way, and neither
     * is anything it leads to. Returns what {@link #leave} needs when the step ends.
     */
    public static int enter(int step) {
        int outside = path;
        path = outside == step - 1 ? step : ELSEWHERE;
        return outside;
    }

    public static void leave(int outside) {
        path = outside;
    }

    /**
     * A level render starts, with poses relative to the given camera position. The one that is followed starts
     * a frame here; the camera movement is the one since that of the frame before.
     */
    public static void beginLevel(double x, double y, double z) {
        boolean own = path == LEVEL;
        boolean outermost = levelDepth++ == 0;
        levelBegan = true;
        ownBegan |= own;
        if (!outermost || followed) {
            following = false;
            another();
            return;
        }
        if (!own && ownDue) {
            // The game's own is still to come in this frame, as far as anything says.
            following = false;
            waived = true;
            return;
        }
        if (waived) {
            another();
        }
        int kind = own ? OWN : FIRST;
        if (kind != followedKind) {
            if (followedKind != 0) {
                // What was followed until now is no history of this view.
                frame++;
            }
            followedKind = kind;
            if (own && !toldOwn) {
                toldOwn = true;
                ArVelocity.LOGGER.info("ar_velocity: the level render the game itself makes in a frame (GameRenderer.render -> renderLevel -> "
                        + "LevelRenderer.renderLevel) is the one whose draws get motion vectors, while a shader pack program reads them; two frames are compared by "
                        + "the timer of the game, and a pause is one second of real time");
            }
        }
        followed = true;
        followedRunning = true;
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
            followedRunning = false;
        }
        following = followedRunning && levelDepth == 1;
    }

    private static void another() {
        if (!warnedOtherLevel) {
            warnedOtherLevel = true;
            ArVelocity.LOGGER.warn("ar_velocity: the level is drawn more than once in a frame (another camera, a portal, a mirror or a panorama); only one view "
                    + "gets motion vectors, the one the game itself draws or, in a frame in which it draws none, the one that is drawn first; the others get "
                    + "none (reported once)");
        }
    }

    private static void advance() {
        frame++;
        long duration = frameTimed ? gameNanos - frameNanos : 0L;
        long real = frameTimed ? realNanos - frameRealNanos : 0L;
        frameRatio = VelocityHistory.ratio(duration, frameDuration, real);
        // What lies before a pause is not a frame whose length says anything about the next one.
        frameDuration = real > VelocityHistory.PAUSE ? 0L : duration;
        frameNanos = gameNanos;
        frameRealNanos = realNanos;
        frameTimed = true;
        // A render that ended with an exception leaves the stacks unbalanced; every frame starts clean.
        outerOwners.clear();
        outerOwnerRenderers.clear();
        outerOwnerThings.clear();
        outerOwnerDepths.clear();
        outerRenderers.clear();
        outerThings.clear();
        owner = null;
        renderer = null;
        thing = null;
        if (frame % SWEEP_INTERVAL == 0) {
            VelocityHistory.sweep(frame);
        }
    }

    public static void pushOwner(Object candidate) {
        outerOwners.add(owner);
        outerOwnerRenderers.add(renderer);
        outerOwnerThings.add(thing);
        outerOwnerDepths.add(outerRenderers.size());
        owner = candidate instanceof VelocityHistoryHolder holder ? holder : null;
        renderer = null;
        thing = null;
    }

    public static void popOwner() {
        int last = outerOwners.size() - 1;
        if (last < 0) {
            owner = null;
            renderer = null;
            thing = null;
            outerRenderers.clear();
            outerThings.clear();
            return;
        }
        owner = outerOwners.remove(last);
        renderer = outerOwnerRenderers.remove(last);
        thing = outerOwnerThings.remove(last);
        int depth = outerOwnerDepths.removeInt(last);
        while (outerRenderers.size() > depth) {
            outerRenderers.remove(outerRenderers.size() - 1);
            outerThings.remove(outerThings.size() - 1);
        }
    }

    /**
     * @param candidate the renderer object of the draws that follow
     * @param context   what it draws with
     */
    public static void pushRenderer(Object candidate, Object context) {
        outerRenderers.add(renderer);
        outerThings.add(thing);
        renderer = innermost(candidate);
        thing = thingOf(context);
    }

    public static void popRenderer() {
        int last = outerRenderers.size() - 1;
        renderer = last < 0 ? null : outerRenderers.remove(last);
        thing = last < 0 ? null : outerThings.remove(last);
    }

    public static Object currentOwner() {
        return owner;
    }

    public static Object currentRenderer() {
        return renderer;
    }

    /** What the draws that follow are made for, where their renderer was told and their owner carries it: the stack of an item. */
    public static Object currentThing() {
        return thing;
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
        // The blob shadow is not a thing that moves: its quads lie on the blocks under the entity and are made
        // anew from them in every frame, and only the picture on them goes along with the entity. No matrix
        // says how its vertices got where they are, so it gets no delta, which leaves it to the shader pack's
        // handling of what stands still.
        return inner instanceof AcceleratedEntityShadowRenderer ? null : inner;
    }

    // An item model is handed the colours of the stack it is drawn for, and with them the stack: the same
    // model draws every item of a kind, and nothing else that comes with a draw says which one. That holds for
    // what a block entity carries (a belt, a campfire): another stack in the place of one is another thing.
    // What an entity holds is in its hand or on it, and the game puts another object there whenever the server
    // sends the stack again, also an equal one (a count the client has changed itself already, a whole
    // inventory): there the stack says nothing, and the draw names none.
    private static Object thingOf(Object context) {
        if (owner instanceof BlockEntity && context instanceof AcceleratedModelRenderContext model && model.layerColors() instanceof ItemLayerColorsAccessor colors) {
            return colors.arvelocity$itemStack();
        }
        return null;
    }

    // The game makes a new object for a stack whenever it is sent the stack again: one of the same item with
    // other content is the stack that was there, with another durability or count. An equal one, or one of
    // another item, is another stack in its place.
    private static boolean changed(Object before, Object now) {
        return before instanceof ItemStack old && now instanceof ItemStack stack && ItemStack.isSameItem(old, stack) && !ItemStack.matches(old, stack);
    }

    /**
     * Fills the (zeroed) delta entry of the transform that was just begun: D' = M_now * P_now - M_prev * P_prev,
     * with P the transform of this draw and M the gbuffer model view matrix of Iris. The entry stays zero when
     * the draw has no owner or renderer, in the shadow and hand passes, outside shader pack level rendering,
     * in every level render but the game's own, and when the history of the owner has no draw of the frame
     * before to pair this one with.
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
        long cycles = VelocityHistory.cycles();
        long refused = VelocityHistory.refused();
        Matrix4f previous = holder.arvelocity$history().pair(key, frame, now, transform, cameraDx, cameraDy, cameraDz, frameRatio, ring, address, thing, STACKS);
        if (previous != null) {
            VelocityDeltas.write(address, now, previous);
        }
        if (VelocityHistory.cycles() != cycles && !toldCycles) {
            toldCycles = true;
            ArVelocity.LOGGER.info("ar_velocity: a renderer ({}, for {}) draws in more than one compute cycle of a frame (reported once)",
                    key.getClass().getName(), holder.getClass().getName());
        }
        if (VelocityHistory.refused() != refused && !warnedLate) {
            warnedLate = true;
            ArVelocity.LOGGER.warn("ar_velocity: the first draw of a renderer ({}, for {}) in a compute cycle was given the motion vector of the only draw of that "
                    + "cycle of the frame before, and a draw of a later cycle of the frame turned out to be that draw: the first was a new thing, drawn at an earlier "
                    + "point of the frame than the old one, and its vertices had gone to the compute programs. It has a false motion vector in this one frame; "
                    + "for this owner the renderer is held to the rules for several draws from now on (reported once)", key.getClass().getName(),
                    holder.getClass().getName());
        }
    }
}
