package org.example.tool;

import java.util.*;

public final class DiffUtils {

    private static final String RED    = "\033[31m";
    private static final String GREEN  = "\033[32m";
    private static final String CYAN   = "\033[36m";
    private static final String BOLD   = "\033[1m";
    private static final String RESET  = "\033[0m";
    private static final int MAX_PREVIEW = ToolConstants.DIFF_MAX_PREVIEW_LINES;
    private static final int CTX = ToolConstants.DIFF_CONTEXT_LINES;
    private static final int MAX_DIFF_LINES = 5000;

    private DiffUtils() {}

    public static void printEditDiff(String filePath, String oldContent, String newContent,
                                      String oldString, String newString) {
        String displayPath = shortenPath(filePath);
        String[] oldLines = oldContent.split("\n", -1);
        String[] oldChunk = oldString.split("\n", -1);
        String[] newChunk = newString.split("\n", -1);

        int oldStartLine = findLineIndex(oldContent, oldString);
        if (oldStartLine < 0) oldStartLine = 0;

        int ctxBefore = Math.min(CTX, oldStartLine);
        int ctxAfter = Math.min(CTX, oldLines.length - (oldStartLine + oldChunk.length));

        List<Edit> edits = diffEdits(oldChunk, newChunk);
        int oldCount = 0, newCount = 0;
        for (Edit e : edits) {
            if (e.type != EditType.INSERT) oldCount++;
            if (e.type != EditType.DELETE) newCount++;
        }

        System.out.println(BOLD + "\n--- a/" + displayPath + RESET);
        System.out.println(BOLD + "+++ b/" + displayPath + RESET);
        System.out.println(CYAN + "@@ -" + (oldStartLine + 1) + "," + oldCount
                + " +" + (oldStartLine + 1) + "," + newCount + " @@" + RESET);

        for (int i = oldStartLine - ctxBefore; i < oldStartLine; i++) {
            if (i >= 0 && i < oldLines.length) {
                System.out.println(" " + padNum(i + 1) + " " + oldLines[i]);
            }
        }

        int oPos = oldStartLine, nPos = oldStartLine, oi = 0, ni = 0;
        for (Edit e : edits) {
            switch (e.type) {
                case KEEP:
                    System.out.println(" " + padNum(oPos + 1) + " " + oldChunk[oi]);
                    oPos++; nPos++; oi++; ni++; break;
                case DELETE:
                    System.out.println(RED + "-" + padNum(oPos + 1) + " " + oldChunk[oi] + RESET);
                    oPos++; oi++; break;
                case INSERT:
                    System.out.println(GREEN + "+" + padNum(nPos + 1) + " " + newChunk[ni] + RESET);
                    nPos++; ni++; break;
            }
        }

        int afterStart = oldStartLine + oldChunk.length;
        for (int i = afterStart; i < afterStart + ctxAfter; i++) {
            if (i < oldLines.length) {
                System.out.println(" " + padNum(i + 1) + " " + oldLines[i]);
            }
        }
        System.out.println();
    }

    public static void printWriteDiff(String filePath, String oldContent, String newContent) {
        String displayPath = shortenPath(filePath);
        String[] oldLines = oldContent.split("\n", -1);
        String[] newLines = newContent.split("\n", -1);

        if (oldLines.length > MAX_DIFF_LINES || newLines.length > MAX_DIFF_LINES) {
            System.out.println(BOLD + "\n--- a/" + displayPath + RESET);
            System.out.println(BOLD + "+++ b/" + displayPath + RESET);
            System.out.println(CYAN + "  (文件较大，跳过差异预览: " + oldLines.length + " → " + newLines.length + " 行)" + RESET);
            System.out.println();
            return;
        }

        List<Edit> raw = diffEdits(oldLines, newLines);
        List<Hunk> hunks = groupHunks(raw, CTX);
        if (hunks.isEmpty()) {
            System.out.println("  (文件内容无变化)");
            return;
        }

        System.out.println(BOLD + "\n--- a/" + displayPath + RESET);
        System.out.println(BOLD + "+++ b/" + displayPath + RESET);

        for (Hunk h : hunks) {
            System.out.println(CYAN + "@@ -" + (h.oldStart + 1) + "," + h.oldCount
                    + " +" + (h.newStart + 1) + "," + h.newCount + " @@" + RESET);

            int oi = h.oldStart, ni = h.newStart;
            for (Edit e : h.edits) {
                switch (e.type) {
                    case KEEP:
                        System.out.println(" " + padNum(oi + 1) + " " + oldLines[oi]);
                        oi++; ni++; break;
                    case DELETE:
                        System.out.println(RED + "-" + padNum(oi + 1) + " " + oldLines[oi] + RESET);
                        oi++; break;
                    case INSERT:
                        System.out.println(GREEN + "+" + padNum(ni + 1) + " " + newLines[ni] + RESET);
                        ni++; break;
                }
            }
        }
        System.out.println();
    }

    public static void printCreateDiff(String filePath, String newContent) {
        String displayPath = shortenPath(filePath);
        String[] newLines = newContent.split("\n", -1);
        int showCount = Math.min(newLines.length, MAX_PREVIEW);

        System.out.println(BOLD + "\n--- /dev/null" + RESET);
        System.out.println(BOLD + "+++ b/" + displayPath + RESET);
        System.out.println(CYAN + "@@ -0,0 +1," + newLines.length + " @@" + RESET);
        for (int i = 0; i < showCount; i++) {
            System.out.println(GREEN + "+" + padNum(i + 1) + " " + newLines[i] + RESET);
        }
        if (newLines.length > showCount) {
            System.out.println(CYAN + "  ... +" + (newLines.length - showCount) + " 行省略" + RESET);
        }
        System.out.println();
    }

    static List<Edit> diffEdits(String[] a, String[] b) {
        int m = a.length, n = b.length;
        int[][] dp = new int[m + 1][n + 1];
        for (int i = 1; i <= m; i++) {
            for (int j = 1; j <= n; j++) {
                if (a[i - 1].equals(b[j - 1])) {
                    dp[i][j] = dp[i - 1][j - 1] + 1;
                } else {
                    dp[i][j] = Math.max(dp[i - 1][j], dp[i][j - 1]);
                }
            }
        }

        List<Edit> edits = new ArrayList<>();
        int i = m, j = n;
        while (i > 0 || j > 0) {
            if (i > 0 && j > 0 && a[i - 1].equals(b[j - 1])) {
                edits.add(new Edit(EditType.KEEP, i - 1, j - 1));
                i--; j--;
            } else if (j > 0 && (i == 0 || dp[i][j - 1] >= dp[i - 1][j])) {
                edits.add(new Edit(EditType.INSERT, i, j - 1));
                j--;
            } else {
                edits.add(new Edit(EditType.DELETE, i - 1, j));
                i--;
            }
        }
        Collections.reverse(edits);
        return edits;
    }

    private static List<Hunk> groupHunks(List<Edit> raw, int context) {
        List<Hunk> hunks = new ArrayList<>();
        int idx = 0;
        while (idx < raw.size()) {
            while (idx < raw.size() && raw.get(idx).type == EditType.KEEP) idx++;
            if (idx >= raw.size()) break;

            int start = Math.max(0, idx - context);
            int end = idx + 1;
            while (end < raw.size()) {
                boolean nearChange = false;
                int limit = Math.min(raw.size(), end + context);
                for (int k = end; k < limit; k++) {
                    if (raw.get(k).type != EditType.KEEP) { nearChange = true; break; }
                }
                if (nearChange) {
                    end = limit;
                } else {
                    end = Math.min(raw.size(), end + context);
                    break;
                }
            }

            int oldStart = raw.get(start).oldLine;
            int newStart = raw.get(start).newLine;
            int oldCount = 0, newCount = 0;
            List<Edit> edits = new ArrayList<>();
            for (int k = start; k < end; k++) {
                Edit e = raw.get(k);
                edits.add(e);
                if (e.type != EditType.INSERT) oldCount++;
                if (e.type != EditType.DELETE) newCount++;
            }
            hunks.add(new Hunk(oldStart, oldCount, newStart, newCount, edits));
            idx = end;
        }
        return hunks;
    }

    private static String padNum(int n) {
        if (n < 10) return "  " + n;
        if (n < 100) return " " + n;
        return String.valueOf(n);
    }

    enum EditType { KEEP, DELETE, INSERT }

    static class Edit {
        final EditType type;
        final int oldLine;
        final int newLine;
        Edit(EditType type, int oldLine, int newLine) {
            this.type = type;
            this.oldLine = oldLine;
            this.newLine = newLine;
        }
    }

    static class Hunk {
        final int oldStart, oldCount, newStart, newCount;
        final List<Edit> edits;
        Hunk(int oldStart, int oldCount, int newStart, int newCount, List<Edit> edits) {
            this.oldStart = oldStart;
            this.oldCount = oldCount;
            this.newStart = newStart;
            this.newCount = newCount;
            this.edits = edits;
        }
    }

    private static int findLineIndex(String content, String target) {
        int charIdx = content.indexOf(target);
        if (charIdx < 0) return -1;
        int lines = 0;
        for (int i = 0; i < charIdx; i++) {
            if (content.charAt(i) == '\n') lines++;
        }
        return lines;
    }

    private static String shortenPath(String path) {
        String userDir = System.getProperty("user.dir");
        if (path.startsWith(userDir)) {
            String rel = path.substring(userDir.length());
            if (rel.startsWith(java.io.File.separator)) {
                rel = rel.substring(1);
            }
            return rel;
        }
        return path;
    }
}
