package org.example.tool;

import org.example.TerminalStart;

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
        System.out.println(buildEditDiff(filePath, oldContent, newContent, oldString, newString));
    }

    public static String buildEditDiff(String filePath, String oldContent, String newContent,
                                       String oldString, String newString) {
        String displayPath = shortenPath(filePath);
        String[] oldLines = oldContent.split("\n", -1);
        String[] oldChunk = oldString.split("\n", -1);
        String[] newChunk = newString.split("\n", -1);
        StringBuilder out = new StringBuilder();

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

        out.append("--- a/").append(displayPath).append('\n');
        out.append("+++ b/").append(displayPath).append('\n');
        out.append("@@ -").append(oldStartLine + 1).append(',').append(oldCount)
                .append(" +").append(oldStartLine + 1).append(',').append(newCount).append(" @@\n");

        for (int i = oldStartLine - ctxBefore; i < oldStartLine; i++) {
            if (i >= 0 && i < oldLines.length) {
                out.append(" ").append(padNum(i + 1)).append(" ").append(oldLines[i]).append('\n');
            }
        }

        int oPos = oldStartLine, nPos = oldStartLine, oi = 0, ni = 0;
        for (Edit e : edits) {
            switch (e.type) {
                case KEEP:
                    out.append(" ").append(padNum(oPos + 1)).append(" ").append(oldChunk[oi]).append('\n');
                    oPos++; nPos++; oi++; ni++; break;
                case DELETE:
                    out.append("-").append(padNum(oPos + 1)).append(" ").append(oldChunk[oi]).append('\n');
                    oPos++; oi++; break;
                case INSERT:
                    out.append("+").append(padNum(nPos + 1)).append(" ").append(newChunk[ni]).append('\n');
                    nPos++; ni++; break;
            }
        }

        int afterStart = oldStartLine + oldChunk.length;
        for (int i = afterStart; i < afterStart + ctxAfter; i++) {
            if (i < oldLines.length) {
                out.append(" ").append(padNum(i + 1)).append(" ").append(oldLines[i]).append('\n');
            }
        }
        return out.toString();
    }

    public static void printWriteDiff(String filePath, String oldContent, String newContent) {
        System.out.println(buildWriteDiff(filePath, oldContent, newContent));
    }

    public static String buildWriteDiff(String filePath, String oldContent, String newContent) {
        String displayPath = shortenPath(filePath);
        String[] oldLines = oldContent.split("\n", -1);
        String[] newLines = newContent.split("\n", -1);
        StringBuilder out = new StringBuilder();

        if (oldLines.length > MAX_DIFF_LINES || newLines.length > MAX_DIFF_LINES) {
            out.append("--- a/").append(displayPath).append('\n');
            out.append("+++ b/").append(displayPath).append('\n');
            out.append("  (文件较大，跳过差异预览: ").append(oldLines.length)
                    .append(" -> ").append(newLines.length).append(" 行)\n");
            return out.toString();
        }

        List<Edit> raw = diffEdits(oldLines, newLines);
        List<Hunk> hunks = groupHunks(raw, CTX);
        if (hunks.isEmpty()) {
            return "  (文件内容无变化)";
        }

        out.append("--- a/").append(displayPath).append('\n');
        out.append("+++ b/").append(displayPath).append('\n');

        for (Hunk h : hunks) {
            out.append("@@ -").append(h.oldStart + 1).append(',').append(h.oldCount)
                    .append(" +").append(h.newStart + 1).append(',').append(h.newCount).append(" @@\n");

            int oi = h.oldStart, ni = h.newStart;
            for (Edit e : h.edits) {
                switch (e.type) {
                    case KEEP:
                        out.append(" ").append(padNum(oi + 1)).append(" ").append(oldLines[oi]).append('\n');
                        oi++; ni++; break;
                    case DELETE:
                        out.append("-").append(padNum(oi + 1)).append(" ").append(oldLines[oi]).append('\n');
                        oi++; break;
                    case INSERT:
                        out.append("+").append(padNum(ni + 1)).append(" ").append(newLines[ni]).append('\n');
                        ni++; break;
                }
            }
        }
        return out.toString();
    }

    public static void printCreateDiff(String filePath, String newContent) {
        System.out.println(buildCreateDiff(filePath, newContent));
    }

    public static String buildCreateDiff(String filePath, String newContent) {
        String displayPath = shortenPath(filePath);
        String[] newLines = newContent.split("\n", -1);
        int showCount = Math.min(newLines.length, MAX_PREVIEW);
        StringBuilder out = new StringBuilder();

        out.append("--- /dev/null\n");
        out.append("+++ b/").append(displayPath).append('\n');
        out.append("@@ -0,0 +1,").append(newLines.length).append(" @@\n");
        for (int i = 0; i < showCount; i++) {
            out.append("+").append(padNum(i + 1)).append(" ").append(newLines[i]).append('\n');
        }
        if (newLines.length > showCount) {
            out.append("  ... +").append(newLines.length - showCount).append(" 行省略\n");
        }
        return out.toString();
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
        String userDir = TerminalStart.getCurrentCwd();
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
