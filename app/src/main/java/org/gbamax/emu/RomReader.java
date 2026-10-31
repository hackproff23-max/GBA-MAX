package org.gbamax.emu;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

public class RomReader {
    // Se for ZIP, extrai o primeiro .gba/.agb/.bin; senão devolve os bytes como estão
    static byte[] extract(byte[] d) throws IOException {
        if (d.length < 4 || d[0] != 'P' || d[1] != 'K') return d;
        try (ZipInputStream z = new ZipInputStream(new ByteArrayInputStream(d))) {
            ZipEntry e;
            while ((e = z.getNextEntry()) != null) {
                String n = e.getName().toLowerCase();
                if (!e.isDirectory() && (n.endsWith(".gba") || n.endsWith(".agb") || n.endsWith(".bin"))) {
                    ByteArrayOutputStream o = new ByteArrayOutputStream(1 << 22);
                    byte[] t = new byte[65536];
                    int c;
                    while ((c = z.read(t)) != -1) o.write(t, 0, c);
                    return o.toByteArray();
                }
            }
        }
        throw new IOException("Nenhum .gba dentro do zip");
    }
}
