package br.codesearch;

import java.io.FileDescriptor;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.PrintStream;
import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.Charset;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * Terminal sem locale UTF-8 (LANG vazio ou POSIX, comum em contêiner e CI): a JVM lê os argumentos e escreve no
 * stdout/stderr em ASCII, então "híbrida" chega como "h��brida" e os avisos saem com "?". Aqui os argumentos são relidos em
 * UTF-8 de {@code /proc/self/cmdline} (Linux) e o stdout/stderr passam a escrever UTF-8. Com locale UTF-8, ou fora do
 * Linux, nada muda.
 */
final class ConsoleEncoding {

    private static final Path CMDLINE = Path.of("/proc/self/cmdline");

    private ConsoleEncoding() {}

    /** Ajusta o stdout/stderr e devolve os argumentos relidos em UTF-8 quando a JVM os decodificou em ASCII. */
    static String[] fix(String[] args) {
        if (isAscii(System.out.charset())) {
            System.setOut(new PrintStream(new FileOutputStream(FileDescriptor.out), true, StandardCharsets.UTF_8));
        }
        if (isAscii(System.err.charset())) {
            System.setErr(new PrintStream(new FileOutputStream(FileDescriptor.err), true, StandardCharsets.UTF_8));
        }
        Charset argCharset;
        try {
            argCharset = Charset.forName(System.getProperty("sun.jnu.encoding", "UTF-8"));
        } catch (IllegalArgumentException e) {
            return args;
        }
        if (!isAscii(argCharset)) {
            return args;
        }
        try {
            return recoverArgs(args, argCharset, Files.readAllBytes(CMDLINE));
        } catch (IOException | SecurityException e) {
            return args;
        }
    }

    private static boolean isAscii(Charset charset) {
        return charset.equals(StandardCharsets.US_ASCII);
    }

    /**
     * {@code cmdline}: bytes de {@code /proc/self/cmdline} (cada argumento termina em NUL; os do programa são os últimos).
     * Só troca os argumentos se os últimos {@code args.length} itens, decodificados como a JVM fez ({@code argCharset}),
     * derem exatamente {@code args}; senão devolve {@code args} como vieram. Item que não é UTF-8 válido fica como veio.
     */
    static String[] recoverArgs(String[] args, Charset argCharset, byte[] cmdline) {
        List<byte[]> items = split(cmdline);
        if (args.length == 0 || items.size() < args.length) {
            return args;
        }
        List<byte[]> tail = items.subList(items.size() - args.length, items.size());
        String[] out = new String[args.length];
        for (int i = 0; i < args.length; i++) {
            byte[] raw = tail.get(i);
            if (!new String(raw, argCharset).equals(args[i])) {
                return args;
            }
            out[i] = utf8(raw, args[i]);
        }
        return out;
    }

    private static List<byte[]> split(byte[] cmdline) {
        List<byte[]> items = new ArrayList<>();
        int start = 0;
        for (int i = 0; i < cmdline.length; i++) {
            if (cmdline[i] == 0) {
                byte[] item = new byte[i - start];
                System.arraycopy(cmdline, start, item, 0, item.length);
                items.add(item);
                start = i + 1;
            }
        }
        return items;
    }

    private static String utf8(byte[] raw, String fallback) {
        try {
            return StandardCharsets.UTF_8.newDecoder()
                    .onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT)
                    .decode(ByteBuffer.wrap(raw))
                    .toString();
        } catch (CharacterCodingException e) {
            return fallback;
        }
    }
}
