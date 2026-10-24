package io.github.yasmramos.forge.core.cache;

import java.io.ByteArrayOutputStream;
import java.io.EOFException;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;

/** Minimal ustar reader paired with {@link TarArchiveWriter}. */
class TarArchiveReader implements AutoCloseable {

    static class Entry {
        Entry(String name, byte[] content) {
            this.name = name;
            this.content = content;
        }
        final String name;
        final byte[] content;
    }

    private final InputStream in;

    TarArchiveReader(InputStream in) {
        this.in = in;
    }

    Entry nextEntry() throws IOException {
        byte[] header = readExactly(512);
        if (header == null || isEmptyBlock(header)) {
            return null;
        }
        String name = new String(header, 0, 100, StandardCharsets.UTF_8).trim();
        int nul = name.indexOf('\0');
        if (nul >= 0) name = name.substring(0, nul);
        long size = parseOctal(header, 124, 12);
        byte[] content = readExactly((int) ((size / 512) * 512 + (size % 512 == 0 ? 0 : 512)));
        if (content == null) throw new EOFException("Truncated tar entry " + name);
        byte[] exact = new byte[(int) size];
        System.arraycopy(content, 0, exact, 0, (int) size);
        return new Entry(name, exact);
    }

    @Override
    public void close() throws IOException {
        in.close();
    }

    private byte[] readExactly(int n) throws IOException {
        byte[] buf = new byte[n];
        int off = 0;
        while (off < n) {
            int r = in.read(buf, off, n - off);
            if (r < 0) {
                return off == 0 ? null : new byte[0];
            }
            off += r;
        }
        return buf;
    }

    private static boolean isEmptyBlock(byte[] b) {
        for (byte x : b) if (x != 0) return false;
        return true;
    }

    private static long parseOctal(byte[] h, int off, int len) {
        String s = new String(h, off, len, StandardCharsets.UTF_8).trim().replace("\0", "");
        return s.isEmpty() ? 0 : Long.parseLong(s, 8);
    }
}
