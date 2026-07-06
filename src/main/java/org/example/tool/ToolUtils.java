package org.example.tool;

import org.example.TerminalStart;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

public final class ToolUtils {

    private ToolUtils() {}

    public static File resolveFile(String path) {
        File file = new File(path);
        if (!file.isAbsolute()) {
            file = new File(TerminalStart.getCurrentCwd(), path);
        }
        return file;
    }

    public static File resolveFileSafe(String path) throws SecurityException {
        File file = resolveFile(path);
        try {
            String canonicalPath = file.getCanonicalPath();
            String projectRoot = new File(TerminalStart.getCurrentCwd()).getCanonicalPath();
            String normalizedProject = projectRoot.endsWith(File.separator)
                    ? projectRoot : projectRoot + File.separator;
            String normalizedFile = canonicalPath.endsWith(File.separator)
                    ? canonicalPath : canonicalPath + File.separator;

            if (!normalizedFile.startsWith(normalizedProject)) {
                throw new SecurityException("路径遍历被拒绝: " + path);
            }
            return new File(canonicalPath);
        } catch (IOException e) {
            throw new SecurityException("无法解析路径: " + path, e);
        }
    }

    public static List<String> searchFiles(String name, boolean useGlob) {
        List<String> results = new ArrayList<>();
        if (name == null || name.trim().isEmpty()) {
            return results;
        }
        String lowerName = name.toLowerCase();
        File root = new File(TerminalStart.getCurrentCwd());
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

        boolean matchPath = useGlob && (pattern.contains("/") || pattern.contains("**"));

        for (File f : files) {
            if (results.size() >= ToolConstants.MAX_SEARCH_RESULTS) return;

            if (f.isFile()) {
                String relPath = f.getAbsolutePath().substring(basePath.length());
                if (relPath.startsWith(File.separator)) {
                    relPath = relPath.substring(1);
                }
                String matchTarget;
                if (matchPath) {
                    matchTarget = relPath.replace('\\', '/').toLowerCase();
                } else {
                    matchTarget = f.getName().toLowerCase();
                }
                if (useGlob) {
                    if (globMatch(matchTarget, pattern)) {
                        results.add(relPath);
                    }
                } else {
                    if (matchTarget.contains(pattern)) {
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
        if (pattern.startsWith("**/") && globMatch(name, pattern.substring(3))) {
            return true;
        }
        if (!pattern.contains("*") && !pattern.contains("?")) {
            return name.equals(pattern);
        }

        StringBuilder regex = new StringBuilder("^");
        for (int i = 0; i < pattern.length(); i++) {
            char c = pattern.charAt(i);
            switch (c) {
                case '*':
                    if (i + 1 < pattern.length() && pattern.charAt(i + 1) == '*') {
                        regex.append(".*");
                        i++;
                    } else {
                        regex.append("[^/]*");
                    }
                    break;
                case '?':
                    regex.append("[^/]");
                    break;
                case '.': case '+': case '(': case ')': case '{': case '}':
                case '[': case ']': case '|': case '^': case '$': case '\\':
                    regex.append("\\").append(c);
                    break;
                default:
                    regex.append(c);
            }
        }
        regex.append("$");
        return name.matches(regex.toString());
    }
}
