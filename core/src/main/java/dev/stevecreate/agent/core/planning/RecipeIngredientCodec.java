package dev.stevecreate.agent.core.planning;

import dev.stevecreate.agent.core.model.ResourceId;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.EOFException;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.zip.CRC32;

/** Canonical bounded binary codec for one loader-neutral recipe ingredient. */
public final class RecipeIngredientCodec {
    private static final int MAGIC = 0x53495249; // SIRI
    private static final int VERSION = 1;
    private static final int MAX_PAYLOAD_BYTES = 1_048_576;
    private static final int MAX_STRING_BYTES = 65_536;

    public byte[] encode(RecipeIngredient ingredient) {
        Objects.requireNonNull(ingredient, "ingredient");
        try {
            ByteArrayOutputStream payloadBytes = new ByteArrayOutputStream();
            try (DataOutputStream payload = new DataOutputStream(payloadBytes)) {
                payload.writeByte(ingredient.kind().ordinal());
                payload.writeLong(ingredient.amount());
                if (ingredient instanceof RecipeIngredient.ExactResource exact) {
                    writeResource(payload, exact.resourceId());
                } else if (ingredient instanceof RecipeIngredient.AnyOfResources anyOf) {
                    writeResources(payload, anyOf.resources());
                } else if (ingredient instanceof RecipeIngredient.TagReference tag) {
                    writeResource(payload, tag.tagId());
                    writeResources(payload, tag.runtimeCandidates());
                    writeString(payload, tag.runtimeFingerprint());
                } else if (ingredient instanceof RecipeIngredient.UnsupportedComplexIngredient unsupported) {
                    writeString(payload, unsupported.identity());
                    writeString(payload, unsupported.detail());
                } else {
                    throw new IllegalArgumentException(
                            "Unknown recipe ingredient implementation: " + ingredient.getClass());
                }
            }
            byte[] body = payloadBytes.toByteArray();
            if (body.length == 0 || body.length > MAX_PAYLOAD_BYTES) {
                throw new IllegalArgumentException("Ingredient payload exceeds its byte bound");
            }
            CRC32 crc = new CRC32();
            crc.update(body);
            ByteArrayOutputStream encodedBytes = new ByteArrayOutputStream(body.length + 24);
            try (DataOutputStream encoded = new DataOutputStream(encodedBytes)) {
                encoded.writeInt(MAGIC);
                encoded.writeInt(VERSION);
                encoded.writeInt(body.length);
                encoded.write(body);
                encoded.writeLong(crc.getValue());
            }
            return encodedBytes.toByteArray();
        } catch (IOException impossible) {
            throw new IllegalStateException("In-memory ingredient encoding failed", impossible);
        }
    }

    public RecipeIngredient decode(byte[] encoded) {
        Objects.requireNonNull(encoded, "encoded");
        try (DataInputStream input = new DataInputStream(new ByteArrayInputStream(encoded))) {
            if (input.readInt() != MAGIC) {
                throw new IllegalArgumentException("Unknown ingredient codec magic");
            }
            if (input.readInt() != VERSION) {
                throw new IllegalArgumentException("Unsupported ingredient codec version");
            }
            int payloadLength = input.readInt();
            if (payloadLength <= 0 || payloadLength > MAX_PAYLOAD_BYTES) {
                throw new IllegalArgumentException("Ingredient payload length is outside its bound");
            }
            byte[] payload = input.readNBytes(payloadLength);
            if (payload.length != payloadLength) {
                throw new IllegalArgumentException("Ingredient payload is truncated");
            }
            long expectedCrc = input.readLong();
            if (input.read() != -1) {
                throw new IllegalArgumentException("Ingredient frame contains trailing bytes");
            }
            CRC32 crc = new CRC32();
            crc.update(payload);
            if (expectedCrc != crc.getValue()) {
                throw new IllegalArgumentException("Ingredient payload checksum does not match");
            }
            try (DataInputStream body = new DataInputStream(new ByteArrayInputStream(payload))) {
                int kindOrdinal = body.readUnsignedByte();
                RecipeIngredientKind[] kinds = RecipeIngredientKind.values();
                if (kindOrdinal >= kinds.length) {
                    throw new IllegalArgumentException("Unknown ingredient kind ordinal: " + kindOrdinal);
                }
                long amount = body.readLong();
                RecipeIngredient ingredient = switch (kinds[kindOrdinal]) {
                    case EXACT_RESOURCE -> new RecipeIngredient.ExactResource(
                            readResource(body), amount);
                    case ANY_OF_RESOURCES -> new RecipeIngredient.AnyOfResources(
                            readResources(body), amount);
                    case TAG_REFERENCE -> new RecipeIngredient.TagReference(
                            readResource(body), readResources(body), readString(body), amount);
                    case UNSUPPORTED_COMPLEX_INGREDIENT ->
                            new RecipeIngredient.UnsupportedComplexIngredient(
                                    readString(body), readString(body), amount);
                };
                if (body.read() != -1) {
                    throw new IllegalArgumentException("Ingredient payload contains trailing fields");
                }
                return ingredient;
            }
        } catch (EOFException failure) {
            throw new IllegalArgumentException("Ingredient frame is truncated", failure);
        } catch (IOException failure) {
            throw new IllegalArgumentException("Ingredient frame cannot be decoded", failure);
        }
    }

    private static void writeResources(DataOutputStream output, List<ResourceId> resources)
            throws IOException {
        if (resources.size() > RecipeIngredient.MAX_CANDIDATES) {
            throw new IllegalArgumentException("Ingredient candidate count exceeds its bound");
        }
        output.writeInt(resources.size());
        for (ResourceId resource : resources) {
            writeResource(output, resource);
        }
    }

    private static List<ResourceId> readResources(DataInputStream input) throws IOException {
        int count = input.readInt();
        if (count < 0 || count > RecipeIngredient.MAX_CANDIDATES) {
            throw new IllegalArgumentException("Ingredient candidate count is outside its bound");
        }
        List<ResourceId> resources = new ArrayList<>(count);
        for (int index = 0; index < count; index++) {
            resources.add(readResource(input));
        }
        return resources;
    }

    private static void writeResource(DataOutputStream output, ResourceId resource)
            throws IOException {
        writeString(output, Objects.requireNonNull(resource, "resource").toString());
    }

    private static ResourceId readResource(DataInputStream input) throws IOException {
        return ResourceId.parse(readString(input));
    }

    private static void writeString(DataOutputStream output, String value) throws IOException {
        byte[] bytes = Objects.requireNonNull(value, "value").getBytes(StandardCharsets.UTF_8);
        if (bytes.length == 0 || bytes.length > MAX_STRING_BYTES) {
            throw new IllegalArgumentException("Encoded ingredient string exceeds its bound");
        }
        output.writeInt(bytes.length);
        output.write(bytes);
    }

    private static String readString(DataInputStream input) throws IOException {
        int length = input.readInt();
        if (length <= 0 || length > MAX_STRING_BYTES) {
            throw new IllegalArgumentException("Ingredient string length is outside its bound");
        }
        byte[] bytes = input.readNBytes(length);
        if (bytes.length != length) {
            throw new EOFException("Ingredient string is truncated");
        }
        return new String(bytes, StandardCharsets.UTF_8);
    }
}
