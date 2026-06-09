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

    public static File resolveFileSafe(String path) throws SecurityException {
        File file = resolveFile(path);
        try {
            String canonicalPath = file.getCanonicalPath();
            String projectRoot = new File(System.getProperty("user.dir")).getCanonicalPath();
            String normalizedProject = projectRoot.endsWith(File.separator)
                    ? projectRoot : projectRoot + File.separator;
            String normalizedFile = canonicalPath.endsWith(File.separator)
                    ? canonicalPath : canonicalPath + File.separator;

            if (!normalizedFile.startsWith(normalizedProject)) {
                throw new SecurityException("路径遍历被拒绝: " + path);
            }
            return file;
        } catch (IOException e) {
            throw new SecurityException("无法解析路径: " + path, e);
        }
    }

    public static List<String> searchFiles(String name, boolean useGlob) {
        List<String> results = new ArrayList<>();
        String lowerName = name.toLowerCase();
        File root = new File(System.getProperty("user.dir"));
        java.util.Set<String> seenDirs = new java.util.HashSet<>();
        searchDir(root, root.getAbsolutePath(), lowerName, useGlob, results, 0, seenDirs);
        return results;
    }

    private static void searchDir(File dir, String basePath, String pattern, boolean useGlob,
                                   List<String> results, int depth, java.util.Set<String> seenDirs) {
        if (depth > ToolConstants.MAX_SEARCH_DEPTH) return;
        if (results.size() >= ToolConstants.MAX_SEARCH_RESULTS) return;

        String canonical;
        try {
            canonical = dir.getCanonicalPath();
        } catch (IOException e) {
            return;
        }
        if (!seenDirs.add(canonical)) return;

        File[] files = dir.listFiles();
        if (files == null) return;

        for (File f : files) {
            if (results.size() >= ToolConstants.MAX_SEARCH_RESULTS) return;

            if (f.isFile()) {
                String relPath = f.getAbsolutePath().substring(basePath.length());
                if (relPath.startsWith(File.separator)) {
                    relPath = relPath.substring(1);
                }
                String fileName = f.getName().toLowerCase();
                if (useGlob) {
                    if (globMatch(fileName, pattern)) {
                        results.add(relPath);
                    }
                } else {
                    if (fileName.contains(pattern)) {
                        results.add(relPath);
                    }
                }
            } else if (f.isDirectory()) {
                String dirName = f.getName();
                if (dirName.equals(".git") || dirName.equals("target")
                        || dirName.equals(".idea") || dirName.equals("node_modules")
                        || dirName.equals(".svn") || dirName.equals("__pycache__")
                        || dirName.startsWith(".")) {
                    continue;
                }
                searchDir(f, basePath, pattern, useGlob, results, depth + 1, seenDirs);
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
