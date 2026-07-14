package org.example.core;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermission;
import java.util.EnumSet;
import java.util.Iterator;
import java.util.Set;
import java.util.stream.Stream;

/** Applies owner-only permissions where the filesystem supports POSIX modes. */
public final class FileSecurity {
    private static final Set<PosixFilePermission> PRIVATE_DIRECTORY = EnumSet.of(
            PosixFilePermission.OWNER_READ,
            PosixFilePermission.OWNER_WRITE,
            PosixFilePermission.OWNER_EXECUTE);
    private static final Set<PosixFilePermission> PRIVATE_FILE = EnumSet.of(
            PosixFilePermission.OWNER_READ,
            PosixFilePermission.OWNER_WRITE);

    private FileSecurity() {}

    public static void ensurePrivateDirectory(Path directory) throws IOException {
        Files.createDirectories(directory);
        setPermissions(directory, PRIVATE_DIRECTORY);
    }

    public static Path ensurePrivateSubdirectory(Path root, Path relative) throws IOException {
        if (root == null || relative == null || relative.isAbsolute()) {
            throw new IOException("私有目录路径无效");
        }
        Path normalizedRoot = root.toAbsolutePath().normalize();
        Path target = normalizedRoot.resolve(relative).normalize();
        if (!target.startsWith(normalizedRoot)) {
            throw new IOException("私有目录不能超出根目录");
        }

        Path current = normalizedRoot;
        ensurePrivateDirectory(current);
        for (Path segment : normalizedRoot.relativize(target)) {
            current = current.resolve(segment);
            ensurePrivateDirectory(current);
        }
        return target;
    }

    public static void restrictFile(Path file) throws IOException {
        if (file != null && Files.exists(file, LinkOption.NOFOLLOW_LINKS)
                && Files.isRegularFile(file, LinkOption.NOFOLLOW_LINKS)) {
            setPermissions(file, PRIVATE_FILE);
        }
    }

    public static void secureTree(Path root) throws IOException {
        if (root == null || !Files.exists(root, LinkOption.NOFOLLOW_LINKS)) return;
        try (Stream<Path> paths = Files.walk(root)) {
            Iterator<Path> iterator = paths.iterator();
            while (iterator.hasNext()) {
                Path path = iterator.next();
                if (Files.isDirectory(path, LinkOption.NOFOLLOW_LINKS)) {
                    setPermissions(path, PRIVATE_DIRECTORY);
                } else if (Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS)) {
                    setPermissions(path, PRIVATE_FILE);
                }
            }
        }
    }

    private static void setPermissions(Path path, Set<PosixFilePermission> permissions) throws IOException {
        try {
            Files.setPosixFilePermissions(path, permissions);
        } catch (UnsupportedOperationException ignored) {
            // Windows and some network filesystems use ACLs instead of POSIX modes.
        }
    }
}
