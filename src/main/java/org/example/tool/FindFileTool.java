package org.example.tool;

import com.google.gson.*;
import java.util.List;

public class FindFileTool extends Tool {

    public FindFileTool() {
        super("find_file", "在当前目录递归搜索匹配的文件名，支持通配符(如 *.java, pom.*)",
                new Param("name", "string", "文件名或通配符模式，如 App.java、*.xml", true));
    }

    @Override
    protected String doExecute(JsonObject args) throws Exception {
        String name = args.get("name").getAsString();
        List<String> matches = ToolUtils.searchFiles(name, true);
        if (matches.isEmpty()) {
            return "未找到匹配 \"" + name + "\" 的文件";
        }
        StringBuilder sb = new StringBuilder("找到 " + matches.size() + " 个匹配:\n");
        for (String m : matches) {
            sb.append(m).append("\n");
        }
        return sb.toString().trim();
    }
}
