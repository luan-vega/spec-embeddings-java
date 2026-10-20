package br.codesearch.adapter.out.file;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.file.Files;
import java.nio.file.Path;

/** Vetores binários big-endian: o formato dos arrays dos segmentos. */
final class BinIo {

    private BinIo() {}

    static void write(Path file, int[] values) {
        ByteBuffer b = ByteBuffer.allocate(values.length * Integer.BYTES);
        b.asIntBuffer().put(values);
        FileSupport.atomicWrite(file, b.array());
    }

    static void write(Path file, long[] values) {
        ByteBuffer b = ByteBuffer.allocate(values.length * Long.BYTES);
        b.asLongBuffer().put(values);
        FileSupport.atomicWrite(file, b.array());
    }

    static void write(Path file, float[] values) {
        ByteBuffer b = ByteBuffer.allocate(values.length * Float.BYTES);
        b.asFloatBuffer().put(values);
        FileSupport.atomicWrite(file, b.array());
    }

    static int[] ints(Path file) throws IOException {
        int[] out = new int[(int) (Files.size(file) / Integer.BYTES)];
        ByteBuffer.wrap(Files.readAllBytes(file)).asIntBuffer().get(out);
        return out;
    }

    static long[] longs(Path file) throws IOException {
        long[] out = new long[(int) (Files.size(file) / Long.BYTES)];
        ByteBuffer.wrap(Files.readAllBytes(file)).asLongBuffer().get(out);
        return out;
    }

    static float[] floats(Path file) throws IOException {
        float[] out = new float[(int) (Files.size(file) / Float.BYTES)];
        ByteBuffer.wrap(Files.readAllBytes(file)).asFloatBuffer().get(out);
        return out;
    }
}
