package org.example.core;

import junit.framework.TestCase;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermission;
import java.util.EnumSet;
import java.util.Set;

public class FileSecurityTest extends TestCase {
    public void testSecureTreeRestrictsPosixPermissions() throws Exception {
        Path directory = Files.createTempDirectory("fish-code-security-");
        Path file = Files.write(directory.resolve("config.json"), "secret".getBytes("UTF-8"));
        try {
            if (!Files.getFileStore(directory).supportsFileAttributeView("posix")) {
                return;
            }

            Files.setPosixFilePermissions(directory, EnumSet.allOf(PosixFilePermission.class));
            Files.setPosixFilePermissions(file, EnumSet.allOf(PosixFilePermission.class));
            FileSecurity.secureTree(directory);

            Set<PosixFilePermission> expectedDirectory = EnumSet.of(
                    PosixFilePermission.OWNER_READ,
                    PosixFilePermission.OWNER_WRITE,
                    PosixFilePermission.OWNER_EXECUTE
            );
            Set<PosixFilePermission> expectedFile = EnumSet.of(
                    PosixFilePermission.OWNER_READ,
                    PosixFilePermission.OWNER_WRITE
            );
            assertEquals(expectedDirectory, Files.getPosixFilePermissions(directory));
            assertEquals(expectedFile, Files.getPosixFilePermissions(file));
        } finally {
            Files.deleteIfExists(file);
            Files.deleteIfExists(directory);
        }
    }

    public void testEnsurePrivateSubdirectoryRestrictsEveryLevel() throws Exception {
        Path root = Files.createTempDirectory("fish-code-security-root-");
        Path year = root.resolve("2026");
        Path month = year.resolve("07");
        try {
            if (!Files.getFileStore(root).supportsFileAttributeView("posix")) {
                return;
            }
            Files.setPosixFilePermissions(root, EnumSet.allOf(PosixFilePermission.class));

            Path created = FileSecurity.ensurePrivateSubdirectory(root, java.nio.file.Paths.get("2026", "07"));
            Set<PosixFilePermission> expected = EnumSet.of(
                    PosixFilePermission.OWNER_READ,
                    PosixFilePermission.OWNER_WRITE,
                    PosixFilePermission.OWNER_EXECUTE
            );
            assertEquals(month.toAbsolutePath().normalize(), created);
            assertEquals(expected, Files.getPosixFilePermissions(root));
            assertEquals(expected, Files.getPosixFilePermissions(year));
            assertEquals(expected, Files.getPosixFilePermissions(month));
        } finally {
            Files.deleteIfExists(month);
            Files.deleteIfExists(year);
            Files.deleteIfExists(root);
        }
    }
}
