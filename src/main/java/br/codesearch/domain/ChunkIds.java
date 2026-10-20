package br.codesearch.domain;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.UUID;

/** Identificador determinístico de chunk: UUIDv5, igual ao do Python. */
public final class ChunkIds {

    private static final UUID NAMESPACE = UUID.fromString("6f1c9d52-0b0e-5a43-9d3e-2c7a1b8e4f10");

    private ChunkIds() {}

    /** Conteúdo inalterado mantém o mesmo ID. */
    public static String makeChunkId(
            String profileId, String repoId, String source, String symbolPath, String chunkHash, int startLine) {
        String name = String.join("\u001f", profileId, repoId, source, symbolPath, chunkHash, Integer.toString(startLine));
        return uuid5(NAMESPACE, name).toString();
    }

    static UUID uuid5(UUID namespace, String name) {
        try {
            MessageDigest sha1 = MessageDigest.getInstance("SHA-1");
            sha1.update(ByteBuffer.allocate(16)
                    .putLong(namespace.getMostSignificantBits())
                    .putLong(namespace.getLeastSignificantBits())
                    .array());
            byte[] hash = sha1.digest(name.getBytes(StandardCharsets.UTF_8));
            hash[6] = (byte) ((hash[6] & 0x0f) | 0x50);
            hash[8] = (byte) ((hash[8] & 0x3f) | 0x80);
            ByteBuffer buf = ByteBuffer.wrap(hash, 0, 16);
            return new UUID(buf.getLong(), buf.getLong());
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }
}
