package org.example.core;

import com.google.gson.*;
import java.io.*;
import java.util.*;

public final class WorkspacePolicy {
    private final List<File> roots = new java.util.concurrent.CopyOnWriteArrayList<>();
    private final boolean allowHomeRoot;
    private final boolean allowDynamicRoots;

    public WorkspacePolicy(Collection<String> configuredRoots, String initialWorkspace) throws IOException {
        this(configuredRoots, initialWorkspace, false, false);
    }

    public WorkspacePolicy(Collection<String> configuredRoots, String initialWorkspace,
                           boolean allowHomeRoot, boolean allowDynamicRoots) throws IOException {
        this.allowHomeRoot = allowHomeRoot;
        this.allowDynamicRoots = allowDynamicRoots;
        LinkedHashMap<String, File> accepted = new LinkedHashMap<>();
        if (configuredRoots != null) {
            for (String value : configuredRoots) addRoot(accepted, value, allowHomeRoot);
        }
        // With no explicit allow-list, use the directory that contains the
        // startup project as the picker root. Restricting the root to the
        // project itself made the "up" button unusable and prevented choosing
        // sibling repositories. Home and filesystem roots are still rejected
        // by addRoot below.
        if (accepted.isEmpty() && initialWorkspace != null) {
            File initial = new File(initialWorkspace).getCanonicalFile();
            File parent = initial.getParentFile();
            if (parent != null) addRoot(accepted, parent.getPath(), allowHomeRoot);
        }
        addRoot(accepted, initialWorkspace, allowHomeRoot);
        if (accepted.isEmpty()) throw new IOException("没有可用的工作区根目录");
        roots.addAll(accepted.values());
    }

    public static WorkspacePolicy fromConfig(JsonObject config, String initialWorkspace) throws IOException {
        return fromConfig(config, initialWorkspace, false);
    }

    public static WorkspacePolicy fromConfig(JsonObject config, String initialWorkspace,
                                             boolean localOnly) throws IOException {
        List<String> values = new ArrayList<>();
        String env = System.getenv("FISH_ALLOWED_WORKSPACE_ROOTS");
        if (env != null && !env.trim().isEmpty()) {
            values.addAll(Arrays.asList(env.split(java.util.regex.Pattern.quote(File.pathSeparator))));
        } else if (config != null && config.has("allowed_workspace_roots")
                && config.get("allowed_workspace_roots").isJsonArray()) {
            for (JsonElement element : config.getAsJsonArray("allowed_workspace_roots")) {
                if (element.isJsonPrimitive()) values.add(element.getAsString());
            }
        }
        if (values.isEmpty() && localOnly) values.add(System.getProperty("user.home"));
        if (values.isEmpty() && !localOnly) {
            throw new IOException("非本机监听必须配置 allowed_workspace_roots");
        }
        return new WorkspacePolicy(values, initialWorkspace, localOnly, localOnly);
    }

    public boolean isAllowed(File candidate) {
        try {
            File canonical = candidate.getCanonicalFile();
            for (File root : roots) {
                String rootPath = root.getPath();
                String candidatePath = canonical.getPath();
                if (candidatePath.equals(rootPath) || candidatePath.startsWith(rootPath + File.separator)) return true;
            }
        } catch (IOException ignored) {}
        return false;
    }

    public File requireAllowed(File candidate) throws IOException {
        File canonical = candidate.getCanonicalFile();
        if (!isAllowed(canonical)) throw new IOException("目录超出允许的工作区范围: " + canonical.getPath());
        return canonical;
    }

    public File authorizeExplicit(File candidate) throws IOException {
        if (!allowDynamicRoots) throw new IOException("当前服务要求通过 allowed_workspace_roots 配置工作区");
        File canonical = candidate.getCanonicalFile();
        if (!canonical.exists() || !canonical.isDirectory()) {
            throw new IOException("目录不存在或不是文件夹: " + canonical.getPath());
        }
        for (File systemRoot : File.listRoots()) {
            if (canonical.equals(systemRoot.getCanonicalFile())) {
                throw new IOException("不能把文件系统根目录设为工作区");
            }
        }
        synchronized (roots) {
            addRuntimeRoot(canonical);
        }
        return canonical;
    }

    public List<File> getRoots() { return Collections.unmodifiableList(new ArrayList<>(roots)); }

    private static void addRoot(Map<String, File> accepted, String value, boolean allowHomeRoot) throws IOException {
        if (value == null || value.trim().isEmpty()) return;
        File root = new File(value.trim()).getCanonicalFile();
        if (!root.exists() || !root.isDirectory()) return;
        for (File systemRoot : File.listRoots()) {
            if (root.equals(systemRoot.getCanonicalFile())) return;
        }
        File home = new File(System.getProperty("user.home")).getCanonicalFile();
        if (!allowHomeRoot && root.equals(home)) return;
        for (File existing : new ArrayList<>(accepted.values())) {
            if (isWithin(root, existing)) return;
            if (isWithin(existing, root)) accepted.remove(existing.getPath());
        }
        accepted.put(root.getPath(), root);
    }

    private void addRuntimeRoot(File root) {
        for (File existing : new ArrayList<>(roots)) {
            if (isWithin(root, existing)) return;
            if (isWithin(existing, root)) roots.remove(existing);
        }
        roots.add(root);
    }

    private static boolean isWithin(File candidate, File root) {
        String candidatePath = candidate.getPath();
        String rootPath = root.getPath();
        return candidatePath.equals(rootPath) || candidatePath.startsWith(rootPath + File.separator);
    }
}
