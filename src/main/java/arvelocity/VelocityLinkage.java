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

import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.FieldNode;
import org.objectweb.asm.tree.MethodNode;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;

/**
 * Every class, field and method of Iris and of Accelerated Rendering that the code of this mod refers to. Most
 * of them are internals, which a new version of either mod may change without notice; the Java VM would notice
 * that in the middle of a frame. This list is checked once, before any mixin is applied, against the class
 * files that are installed, without loading a class.
 *
 * verify.py compares the list with the references in the compiled classes of the mod, both ways.
 */
public final class VelocityLinkage {
    public enum Kind {
        /** The class has to exist. */
        TYPE,
        FIELD,
        STATIC_FIELD,
        /** A static final field with this value: it was compiled into this mod as a number. */
        CONSTANT,
        /** A method of a class, found in it or in what it extends. */
        METHOD,
        STATIC_METHOD,
        /** A method of an interface. */
        INTERFACE_METHOD,
        STATIC_INTERFACE_METHOD,
        CONSTRUCTOR
    }

    public record Member(Kind kind, String owner, String name, String descriptor, Object value) {
        public String mod() {
            return owner.startsWith(IRIS) ? "Iris" : "Accelerated Rendering";
        }

        @Override
        public String toString() {
            String type = owner.replace('/', '.');
            return switch (kind) {
                case TYPE -> "class " + type;
                case FIELD, STATIC_FIELD -> type + "." + name + " : " + descriptor;
                case CONSTANT -> type + "." + name + " : " + descriptor + " = " + value;
                default -> type + "." + name + descriptor;
            };
        }
    }

    /** Hands out the class file of a class by its internal name, without loading the class; null when there is none. */
    public interface Classes {
        ClassNode get(String internalName) throws Exception;
    }

    private static final String IRIS = "net/irisshaders/iris/";
    private static final String AR = "com/github/argon4w/acceleratedrendering/";

    private static final String HAND_RENDERER = IRIS + "pathways/HandRenderer";
    private static final String CAPTURED_STATE = IRIS + "uniforms/CapturedRenderingState";
    private static final String IRIS_API = IRIS + "api/v0/IrisApi";
    private static final String BUILDER = AR + "core/buffers/accelerated/builders/AcceleratedBufferBuilder";
    private static final String BUFFERS = AR + "core/buffers/accelerated/AcceleratedRingBuffers$Buffers";
    private static final String MUTABLE_BUFFER = AR + "core/backends/buffers/MutableBuffer";
    private static final String MAPPED_BUFFER = AR + "core/backends/buffers/MappedBuffer";
    private static final String LAYOUT = AR + "core/buffers/memory/VertexLayout";
    private static final String OVERRIDE = AR + "core/programs/overrides/ProgramOverride";
    private static final String TRANSFORM_OVERRIDE = AR + "core/programs/overrides/ITransformOverride";
    private static final String UPLOADING_OVERRIDE = AR + "core/programs/overrides/IUploadingOverride";
    private static final String TRANSFORM_PROGRAM = AR + "core/programs/dispatchers/TransformProgramDispatcher$Default";
    private static final String UPLOADING_PROGRAM = AR + "core/programs/dispatchers/meshes/MeshUploadingProgramDispatcher$Default";
    private static final String BARRIER_FLAGS = AR + "core/backends/programs/BarrierFlags";
    private static final String ITEM_CONTEXT = AR + "features/items/contexts/AcceleratedModelRenderContext";
    private static final String KEY_AND_SIZE = "(Lnet/minecraft/resources/ResourceLocation;J)V";
    private static final String ELEMENT = "Lcom/mojang/blaze3d/vertex/VertexFormatElement;";

    private static final List<Member> MEMBERS = List.of(
            // VelocityContext: what Iris is drawing at the moment, and its model view matrix
            new Member(Kind.STATIC_FIELD, IRIS + "vertices/ImmediateState", "isRenderingLevel", "Z", null),
            new Member(Kind.STATIC_METHOD, IRIS + "shadows/ShadowRenderingState", "areShadowsCurrentlyBeingRendered", "()Z", null),
            new Member(Kind.STATIC_FIELD, HAND_RENDERER, "INSTANCE", "L" + HAND_RENDERER + ";", null),
            new Member(Kind.METHOD, HAND_RENDERER, "isActive", "()Z", null),
            new Member(Kind.STATIC_INTERFACE_METHOD, IRIS_API, "getInstance", "()L" + IRIS_API + ";", null),
            new Member(Kind.INTERFACE_METHOD, IRIS_API, "isShaderPackInUse", "()Z", null),
            new Member(Kind.STATIC_FIELD, CAPTURED_STATE, "INSTANCE", "L" + CAPTURED_STATE + ";", null),
            new Member(Kind.METHOD, CAPTURED_STATE, "getGbufferModelView", "()Lorg/joml/Matrix4fc;", null),
            new Member(Kind.TYPE, AR + "features/entities/AcceleratedEntityShadowRenderer", null, null, null),
            // and which stack an item model is drawn for
            new Member(Kind.TYPE, ITEM_CONTEXT, null, null, null),
            new Member(Kind.METHOD, ITEM_CONTEXT, "layerColors", "()L" + AR + "features/items/colors/ILayerColors;", null),
            // VelocitySidecar: the entity format and the vertex layout Accelerated Rendering derives from it
            new Member(Kind.STATIC_FIELD, IRIS + "vertices/IrisVertexFormats", "ENTITY", "Lcom/mojang/blaze3d/vertex/VertexFormat;", null),
            new Member(Kind.METHOD, LAYOUT, "getSize", "()J", null),
            new Member(Kind.METHOD, LAYOUT, "containsElement", "(" + ELEMENT + ")Z", null),
            new Member(Kind.METHOD, LAYOUT, "getElementOffset", "(" + ELEMENT + ")I", null),
            // VelocityDeltas: the size of a sharing entry
            new Member(Kind.CONSTANT, BUILDER, "SHARING_SIZE", "J", 112L),
            // the mixins: the delta entry behind a transform, and the velocity buffer of a ring buffer set
            new Member(Kind.METHOD, BUFFERS, "reserveSharing", "()J", null),
            new Member(Kind.METHOD, BUFFERS, "getSharing", "()I", null),
            new Member(Kind.TYPE, BUILDER, null, null, null),
            new Member(Kind.METHOD, BUILDER, "getProgramOverride", "()L" + OVERRIDE + ";", null),
            // the delta entry of a draw found again in the mapped transform buffer, to take it back
            new Member(Kind.METHOD, MAPPED_BUFFER, "getAddress", "()J", null),
            new Member(Kind.METHOD, MAPPED_BUFFER, "getPosition", "()J", null),
            new Member(Kind.METHOD, MAPPED_BUFFER, "addressAt", "(J)J", null),
            new Member(Kind.METHOD, OVERRIDE, "overrideId", "()I", null),
            // VelocityPrograms and the two dispatcher mixins: the pair of programs that writes velocities, held
            // the way Accelerated Rendering holds its own pair, and the two programs added to its list
            new Member(Kind.TYPE, OVERRIDE, null, null, null),
            new Member(Kind.CONSTRUCTOR, OVERRIDE, "<init>", "(IL" + TRANSFORM_OVERRIDE + ";L" + UPLOADING_OVERRIDE + ";)V", null),
            new Member(Kind.METHOD, OVERRIDE, "getVaryingSize", "()J", null),
            new Member(Kind.METHOD, OVERRIDE, "getMeshInfoSize", "()J", null),
            new Member(Kind.METHOD, OVERRIDE, "uploading", "()L" + UPLOADING_OVERRIDE + ";", null),
            new Member(Kind.TYPE, UPLOADING_OVERRIDE, null, null, null),
            new Member(Kind.TYPE, TRANSFORM_PROGRAM, null, null, null),
            new Member(Kind.CONSTRUCTOR, TRANSFORM_PROGRAM, "<init>", KEY_AND_SIZE, null),
            new Member(Kind.TYPE, UPLOADING_PROGRAM, null, null, null),
            new Member(Kind.CONSTRUCTOR, UPLOADING_PROGRAM, "<init>", KEY_AND_SIZE, null),
            new Member(Kind.TYPE, BARRIER_FLAGS, null, null, null),
            new Member(Kind.STATIC_FIELD, BARRIER_FLAGS, "SHADER_STORAGE", "L" + BARRIER_FLAGS + ";", null),
            new Member(Kind.INTERFACE_METHOD, AR + "core/buffers/environments/IBufferEnvironment", "getLayout", "()L" + LAYOUT + ";", null),
            new Member(Kind.METHOD, AR + "core/buffers/accelerated/pools/StagingBufferPool", "getBuffer", "()L" + MUTABLE_BUFFER + ";", null),
            new Member(Kind.TYPE, MUTABLE_BUFFER, null, null, null),
            new Member(Kind.CONSTRUCTOR, MUTABLE_BUFFER, "<init>", "(JI)V", null),
            new Member(Kind.METHOD, MUTABLE_BUFFER, "getSize", "()J", null),
            new Member(Kind.METHOD, MUTABLE_BUFFER, "getBufferHandle", "()I", null),
            new Member(Kind.METHOD, MUTABLE_BUFFER, "resizeTo", "(J)V", null),
            new Member(Kind.METHOD, MUTABLE_BUFFER, "isResized", "()Z", null),
            new Member(Kind.METHOD, MUTABLE_BUFFER, "mark", "()V", null),
            new Member(Kind.METHOD, MUTABLE_BUFFER, "delete", "()V", null));

    private VelocityLinkage() {
    }

    public static List<Member> members() {
        return MEMBERS;
    }

    /** True for the classes this list is about. */
    public static boolean covers(String internalName) {
        return internalName.startsWith(IRIS) || internalName.startsWith(AR);
    }

    /** The first member of the list that the given class files do not have as this mod uses it, or null when all are there. */
    public static Member firstMissing(Classes classes) throws Exception {
        for (Member member : MEMBERS) {
            if (!present(member, classes)) {
                return member;
            }
        }
        return null;
    }

    private static boolean present(Member member, Classes classes) throws Exception {
        ClassNode owner = classes.get(member.owner);
        if (owner == null) {
            return false;
        }
        boolean isInterface = (owner.access & Opcodes.ACC_INTERFACE) != 0;
        switch (member.kind) {
            case TYPE:
                return true;
            case FIELD:
            case STATIC_FIELD:
            case CONSTANT: {
                FieldNode field = field(owner, member, classes, new HashSet<>());
                if (field == null || isStatic(field.access) == (member.kind == Kind.FIELD)) {
                    return false;
                }
                return member.kind != Kind.CONSTANT || ((field.access & Opcodes.ACC_FINAL) != 0 && Objects.equals(field.value, member.value));
            }
            case CONSTRUCTOR: {
                MethodNode constructor = declared(owner, member);
                return constructor != null && !isInterface;
            }
            default: {
                boolean wantInterface = member.kind == Kind.INTERFACE_METHOD || member.kind == Kind.STATIC_INTERFACE_METHOD;
                boolean wantStatic = member.kind == Kind.STATIC_METHOD || member.kind == Kind.STATIC_INTERFACE_METHOD;
                if (isInterface != wantInterface) {
                    return false;
                }
                MethodNode method = wantStatic && wantInterface ? declared(owner, member) : method(owner, member, classes, new HashSet<>());
                return method != null && isStatic(method.access) == wantStatic;
            }
        }
    }

    private static boolean isStatic(int access) {
        return (access & Opcodes.ACC_STATIC) != 0;
    }

    private static MethodNode declared(ClassNode node, Member member) {
        for (MethodNode method : node.methods) {
            if (method.name.equals(member.name) && method.desc.equals(member.descriptor)) {
                return method;
            }
        }
        return null;
    }

    // The way the Java VM looks a member up: in the class, then in what it extends and implements.
    private static MethodNode method(ClassNode node, Member member, Classes classes, Set<String> seen) throws Exception {
        MethodNode found = declared(node, member);
        if (found != null) {
            return found;
        }
        for (ClassNode parent : parents(node, classes, seen)) {
            found = method(parent, member, classes, seen);
            if (found != null) {
                return found;
            }
        }
        return null;
    }

    private static FieldNode field(ClassNode node, Member member, Classes classes, Set<String> seen) throws Exception {
        for (FieldNode field : node.fields) {
            if (field.name.equals(member.name) && field.desc.equals(member.descriptor)) {
                return field;
            }
        }
        for (ClassNode parent : parents(node, classes, seen)) {
            FieldNode found = field(parent, member, classes, seen);
            if (found != null) {
                return found;
            }
        }
        return null;
    }

    private static List<ClassNode> parents(ClassNode node, Classes classes, Set<String> seen) throws Exception {
        List<ClassNode> parents = new ArrayList<>();
        List<String> names = new ArrayList<>();
        if (node.superName != null) {
            names.add(node.superName);
        }
        if (node.interfaces != null) {
            names.addAll(node.interfaces);
        }
        for (String name : names) {
            // Nothing of the two mods is declared in a class of the Java runtime.
            if (name.startsWith("java/") || !seen.add(name)) {
                continue;
            }
            ClassNode parent = classes.get(name);
            if (parent != null) {
                parents.add(parent);
            }
        }
        return parents;
    }
}
