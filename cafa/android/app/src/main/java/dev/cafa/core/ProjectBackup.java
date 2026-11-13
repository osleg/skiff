/* SPDX-License-Identifier: GPL-3.0-only */
package dev.cafa.core;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.Arrays;

/** Never overwrite an original or trust an incomplete migration backup. */
public final class ProjectBackup {
    private ProjectBackup() {}
    public static byte[] read(InputStream in) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream(); byte[] buffer = new byte[4096]; int count;
        while ((count = in.read(buffer)) != -1) {
            if (out.size() + count > 1024 * 1024) throw new IOException("Project exceeds safety limit");
            out.write(buffer, 0, count);
        }
        return out.toByteArray();
    }
    private static boolean matches(File file, byte[] original) {
        try (FileInputStream in = new FileInputStream(file)) { return Arrays.equals(read(in), original); }
        catch (IOException e) { return false; }
    }
    /** A partial/stale backup is kept, and a distinct file is made instead. */
    public static File preserve(File directory, byte[] original) throws IOException {
        if (original == null || original.length == 0) throw new IOException("Missing original project");
        for (int index = 0; index < 10000; index++) {
            File file = new File(directory, "project-v1-before-adsr" + (index == 0 ? "" : "-" + index) + ".cafa");
            if (file.exists()) { if (matches(file, original)) return file; continue; }
            if (!file.createNewFile()) continue;
            try (FileOutputStream out = new FileOutputStream(file)) { out.write(original); out.getFD().sync(); }
            if (!matches(file, original)) throw new IOException("Original project backup could not be verified");
            return file;
        }
        throw new IOException("No unused project backup name");
    }
}
