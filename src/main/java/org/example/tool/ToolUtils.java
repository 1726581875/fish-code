package org.example.tool;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

public final class ToolUtils {

    private ToolUtils() {}

    public static File resolveFile(String path) {
        File file = new File(path);
        if (!file.isAbsolute()) {
            file = new File(System.getProperty("user.dir"), path);
        }
        return file;
    }

    public static List<String> searchFiles(String name, boolean useGlob) {
        List<String> results = new ArrayList<>();
        String lowerName = name.toLowerCase();
        File root = new File(System.getProperty("user.dir"));
        searchDir(root, root.getAbsolutePath(), lowerName, useGlob, results);
        return results;
    }

    private static void searchDir(File dir, String basePath, String pattern, boolean useGlob,
                           List<String> results) {
        File[] files = dir.listFiles();
        if (files == null) {
            return;
        }
        for (File f : files) {
            if (f.isFile()) {
                String relPath = f.getAbsolutePath().substring(basePath.length());
                if (relPath.startsWith(File.separator)) {
                    relPath = relPath.substring(1);
                }
                String name = f.getName().toLowerCase();
                if (useGlob) {
                    if (globMatch(name, pattern)) {
                        results.add(relPath);
                    }
                } else {
                    if (name.contains(pattern)) {
                        results.add(relPath);
                    }
                }
            } else if (f.isDirectory()) {
                String dirName = f.getName();
                if (dirName.equals(".git") || dirName.equals("target")
                        || dirName.equals(".idea") || dirName.equals("node_modules")) {
                    continue;
                }
                searchDir(f, basePath, pattern, useGlob, results);
            }
        }
    }

    public static boolean globMatch(String name, String pattern) {
        if (!pattern.contains("*") && !pattern.contains("?")) {
            return name.contains(pattern);
        }
        String regex = pattern
                .replace(".", "\\.")
                .replace("*", ".*")
                .replace("?", ".");
        return name.matches(regex);
    }
}
