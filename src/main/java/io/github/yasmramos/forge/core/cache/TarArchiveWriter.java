package io.github.yasmramos.forge.core.cache;

import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;

/**
 * Minimal ustar writer (512-byte headers, zero mtime) used by {@link TaskCache}.
 * Fixed timestamps keep archive bytes deterministic for reproducible caching
 * (AGENT.md decision 17). No external dependencies.
 */
class TarArchiveWriter implements AutoCloseable {

    private final OutputStream out;

    TarArchiveWriter(OutputStream out) {
        this.out = out;
    }

    void write(String name, byte[] content) throws IOException {
        byte[] header = new byte[512];
        byte[] nb = name.getBytes(StandardCharsets.UTF_8);
        System.arraycopy(nb, 0, header, 0, Math.min(nb.length, 100));
        octal(header, 100, 8, 0644);                 // mode
        octal(header, 108, 8, 0);                    // uid
        octal(header, 116, 8, 0);                    // gid
        octal(header, 124, 12, content.length);      // size
        octal(header, 136, 12, 0);                   // mtime = epoch (deterministic)
        header[156] = '0';                           // typeflag: regular file
        System.arraycopy("ustar\0".getBytes(StandardCharsets.UTF_8), 0, header, 257, 6);
        System.arraycopy("00".getBytes(StandardCharsets.UTF_8), 0, header, 263, 2);
        octal(header, 148, 8, checksum(header));
        out.write(header);
        out.write(content);
        int pad = (512 - (content.length % 512)) % 512;
        out.write(new byte[pad]);
    }

    @Override
    public void close() throws IOException {
        out.write(new byte[1024]); // end-of-archive marker
        out.flush();
        out.close();
    }

    private static int checksum(byte[] h) {
        int sum = 0;
        for (int i = 0; i < 512; i++) {
            sum += (i >= 148 && i < 156) ? ' ' : (h[i] & 0xFF);
        }
        return sum;
    }

    private static void octal(byte[] h, int off, int len, long value) {
        String s = Long.toString(value, 8);
        while (s.length() < len - 1) s = "0" + s;
        byte[] b = s.getBytes(StandardCharsets.UTF_8);
        System.arraycopy(b, 0, h, off, Math.min(b.length, len - 1));
    }
}
