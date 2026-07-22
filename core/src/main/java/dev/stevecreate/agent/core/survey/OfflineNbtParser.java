package dev.stevecreate.agent.core.survey;

import java.io.ByteArrayInputStream;
import java.io.DataInputStream;
import java.io.EOFException;
import java.io.IOException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/** Package-internal bounded NBT tree. No NBT node type crosses the public survey model. */
final class OfflineNbtParser {
    private static final int END = 0;
    private static final int BYTE = 1;
    private static final int SHORT = 2;
    private static final int INT = 3;
    private static final int LONG = 4;
    private static final int FLOAT = 5;
    private static final int DOUBLE = 6;
    private static final int BYTE_ARRAY = 7;
    private static final int STRING = 8;
    private static final int LIST = 9;
    private static final int COMPOUND = 10;
    private static final int INT_ARRAY = 11;
    private static final int LONG_ARRAY = 12;
    private static final Set<String> PRIVATE_OR_UNUSED_LARGE_TAGS = Set.of(
            "items", "item", "inventory", "enderitems", "entities", "player");
    private static final Set<String> BLOCK_ENTITY_IDENTITY_TAGS = Set.of("id", "x", "y", "z");

    CompoundNode parse(byte[] bytes, NbtReadLimits limits) throws FormalNbtException {
        if (bytes.length > limits.maxUncompressedNbtBytes()) {
            throw new FormalNbtException(FormalSurveyFailureCode.CHUNK_NBT_TOO_LARGE,
                    "uncompressed NBT exceeds configured maximum");
        }
        try (DataInputStream input = new DataInputStream(new ByteArrayInputStream(bytes))) {
            int rootType = input.readUnsignedByte();
            if (rootType != COMPOUND) {
                throw corrupt("NBT root is not a compound");
            }
            readUtf(input);
            CompoundNode root = (CompoundNode) readPayload(input, COMPOUND, 0, limits, true, "root");
            if (input.read() != -1) throw corrupt("NBT has trailing bytes");
            return root;
        } catch (FormalNbtException exception) {
            throw exception;
        } catch (EOFException exception) {
            throw new FormalNbtException(FormalSurveyFailureCode.CHUNK_NBT_CORRUPT,
                    "NBT is truncated", exception);
        } catch (IOException | RuntimeException exception) {
            throw new FormalNbtException(FormalSurveyFailureCode.CHUNK_NBT_CORRUPT,
                    "NBT could not be decoded", exception);
        }
    }

    private Node readPayload(
            DataInputStream input,
            int type,
            int depth,
            NbtReadLimits limits,
            boolean retain,
            String context) throws IOException {
        if (depth > limits.maxDepth()) {
            throw new FormalNbtException(FormalSurveyFailureCode.CHUNK_NBT_TOO_LARGE,
                    "NBT depth exceeds configured maximum");
        }
        return switch (type) {
            case BYTE -> number(retain, input.readByte());
            case SHORT -> number(retain, input.readShort());
            case INT -> number(retain, input.readInt());
            case LONG -> number(retain, input.readLong());
            case FLOAT -> decimal(retain, input.readFloat());
            case DOUBLE -> decimal(retain, input.readDouble());
            case BYTE_ARRAY -> readByteArray(input, limits, retain);
            case STRING -> {
                String value = readUtf(input);
                yield retain ? new StringNode(value) : DiscardedNode.INSTANCE;
            }
            case LIST -> readList(input, depth, limits, retain, context);
            case COMPOUND -> readCompound(input, depth, limits, retain, context);
            case INT_ARRAY -> readIntArray(input, limits, retain);
            case LONG_ARRAY -> readLongArray(input, limits, retain);
            default -> throw corrupt("unknown NBT type " + type);
        };
    }

    private Node readByteArray(DataInputStream input, NbtReadLimits limits, boolean retain) throws IOException {
        int length = boundedLength(input.readInt(), limits.maxArrayLength(), "byte array");
        input.skipNBytes(length);
        return retain ? new ArrayLengthNode(length) : DiscardedNode.INSTANCE;
    }

    private Node readIntArray(DataInputStream input, NbtReadLimits limits, boolean retain) throws IOException {
        int length = boundedLength(input.readInt(), limits.maxArrayLength(), "int array");
        for (int index = 0; index < length; index++) input.readInt();
        return retain ? new ArrayLengthNode(length) : DiscardedNode.INSTANCE;
    }

    private Node readLongArray(DataInputStream input, NbtReadLimits limits, boolean retain) throws IOException {
        int length = boundedLength(input.readInt(), limits.maxArrayLength(), "long array");
        if (!retain) {
            for (int index = 0; index < length; index++) input.readLong();
            return DiscardedNode.INSTANCE;
        }
        long[] values = new long[length];
        for (int index = 0; index < length; index++) values[index] = input.readLong();
        return new LongArrayNode(values);
    }

    private Node readList(
            DataInputStream input,
            int depth,
            NbtReadLimits limits,
            boolean retain,
            String context) throws IOException {
        int childType = input.readUnsignedByte();
        int length = boundedLength(input.readInt(), limits.maxListLength(), "list");
        if (childType == END && length != 0) throw corrupt("non-empty list has END element type");
        List<Node> values = retain ? new ArrayList<>(Math.min(length, 4_096)) : List.of();
        for (int index = 0; index < length; index++) {
            Node value = readPayload(input, childType, depth + 1, limits, retain, context);
            if (retain) values.add(value);
        }
        return retain ? new ListNode(childType, values) : DiscardedNode.INSTANCE;
    }

    private Node readCompound(
            DataInputStream input,
            int depth,
            NbtReadLimits limits,
            boolean retain,
            String context) throws IOException {
        Map<String, Node> values = retain ? new LinkedHashMap<>() : Map.of();
        int entries = 0;
        while (true) {
            int type = input.readUnsignedByte();
            if (type == END) break;
            if (++entries > limits.maxCompoundEntries()) {
                throw new FormalNbtException(FormalSurveyFailureCode.CHUNK_NBT_TOO_LARGE,
                        "compound entry count exceeds configured maximum");
            }
            String name = readUtf(input);
            String lowerName = name.toLowerCase(Locale.ROOT);
            String lowerContext = context.toLowerCase(Locale.ROOT);
            boolean blockEntityIdentityOnly = lowerContext.equals("block_entities")
                    || lowerContext.equals("tileentities");
            boolean keepChild = retain
                    && !PRIVATE_OR_UNUSED_LARGE_TAGS.contains(lowerName)
                    && (!blockEntityIdentityOnly || BLOCK_ENTITY_IDENTITY_TAGS.contains(lowerName));
            Node value = readPayload(input, type, depth + 1, limits, keepChild, name);
            if (keepChild) {
                if (values.putIfAbsent(name, value) != null) throw corrupt("duplicate compound tag " + name);
            }
        }
        return retain ? new CompoundNode(values) : DiscardedNode.INSTANCE;
    }

    private static String readUtf(DataInputStream input) throws IOException {
        String value = input.readUTF();
        if (value.length() > 65_535) throw corrupt("NBT string is too large");
        return value;
    }

    private static int boundedLength(int length, int maximum, String kind) throws FormalNbtException {
        if (length < 0 || length > maximum) {
            throw new FormalNbtException(FormalSurveyFailureCode.CHUNK_NBT_TOO_LARGE,
                    kind + " length is outside configured bounds");
        }
        return length;
    }

    private static Node number(boolean retain, long value) {
        return retain ? new NumberNode(value) : DiscardedNode.INSTANCE;
    }

    private static Node decimal(boolean retain, double value) {
        return retain ? new DecimalNode(value) : DiscardedNode.INSTANCE;
    }

    private static FormalNbtException corrupt(String message) {
        return new FormalNbtException(FormalSurveyFailureCode.CHUNK_NBT_CORRUPT, message);
    }

    sealed interface Node permits NumberNode, DecimalNode, StringNode, ArrayLengthNode,
            LongArrayNode, ListNode, CompoundNode, DiscardedNode {}

    record NumberNode(long value) implements Node {}

    record DecimalNode(double value) implements Node {}

    record StringNode(String value) implements Node {}

    record ArrayLengthNode(int length) implements Node {}

    record LongArrayNode(long[] value) implements Node {
        LongArrayNode {
            value = value.clone();
        }

        @Override
        public long[] value() {
            return value.clone();
        }
    }

    record ListNode(int elementType, List<Node> values) implements Node {
        ListNode {
            values = List.copyOf(values);
        }
    }

    record CompoundNode(Map<String, Node> values) implements Node {
        CompoundNode {
            values = Map.copyOf(values);
        }

        CompoundNode compound(String name) throws FormalNbtException {
            Node value = values.get(name);
            if (value instanceof CompoundNode compound) return compound;
            throw corrupt("missing compound " + name);
        }

        CompoundNode optionalCompound(String name) {
            Node value = values.get(name);
            return value instanceof CompoundNode compound ? compound : null;
        }

        ListNode optionalList(String name) {
            Node value = values.get(name);
            return value instanceof ListNode list ? list : null;
        }

        String string(String name) throws FormalNbtException {
            Node value = values.get(name);
            if (value instanceof StringNode string) return string.value();
            throw corrupt("missing string " + name);
        }

        String optionalString(String name) {
            Node value = values.get(name);
            return value instanceof StringNode string ? string.value() : null;
        }

        long number(String name) throws FormalNbtException {
            Node value = values.get(name);
            if (value instanceof NumberNode number) return number.value();
            throw corrupt("missing number " + name);
        }

        Long optionalNumber(String name) {
            Node value = values.get(name);
            return value instanceof NumberNode number ? number.value() : null;
        }

        LongArrayNode optionalLongArray(String name) {
            Node value = values.get(name);
            return value instanceof LongArrayNode array ? array : null;
        }
    }

    enum DiscardedNode implements Node { INSTANCE }
}
