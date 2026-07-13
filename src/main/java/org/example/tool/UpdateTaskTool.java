package org.example.tool;

import com.google.gson.JsonObject;
import org.example.TerminalStart;
import org.example.core.AgentRun;

public final class UpdateTaskTool extends Tool {
    public UpdateTaskTool() {
        super("update_task", "更新当前复杂任务的目标、阶段、步骤、下一步或阻塞原因",
                new Param("phase", "string", "DISCOVER、PLAN、EXECUTE、VERIFY或BLOCKED", false),
                new Param("objective", "string", "更准确的任务目标", false),
                new Param("step", "string", "要新增或更新的步骤标题", false),
                new Param("stepStatus", "string", "pending、in_progress、completed或blocked", false),
                new Param("nextAction", "string", "接下来要执行的动作", false),
                new Param("blockedReason", "string", "无法继续时的阻塞原因", false),
                new Param("remainingRisk", "string", "需要向用户说明的剩余风险", false),
                new Param("acceptanceCriterion", "string", "新增一条可验证的验收条件", false));
    }

    @Override
    protected String doExecute(JsonObject args) {
        AgentRun run = TerminalStart.getCurrentRun();
        if (run == null) return "当前没有可更新的任务";
        run.getTaskState().updateFromAgent(value(args, "phase"), value(args, "objective"),
                value(args, "step"), value(args, "stepStatus"), value(args, "nextAction"),
                value(args, "blockedReason"), value(args, "remainingRisk"));
        run.getTaskState().addAcceptanceCriterion(value(args, "acceptanceCriterion"));
        return "任务状态已更新: " + run.getTaskState().getPhase().name();
    }

    private static String value(JsonObject args, String key) {
        return args.has(key) && !args.get(key).isJsonNull() ? args.get(key).getAsString() : "";
    }
}
