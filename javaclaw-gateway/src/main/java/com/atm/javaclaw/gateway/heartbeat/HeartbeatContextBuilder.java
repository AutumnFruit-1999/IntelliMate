package com.atm.javaclaw.gateway.heartbeat;

import com.atm.javaclaw.gateway.entity.AgentTaskEntity;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.List;

@Component
public class HeartbeatContextBuilder {

    private static final DateTimeFormatter TIME_FMT = DateTimeFormatter.ofPattern("HH:mm");
    private static final DateTimeFormatter DATETIME_FMT = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm");

    public String buildPrompt(String agentName, LifecycleState state,
                              String personalityPrompt, List<AgentTaskEntity> tasks,
                              LocalDateTime now) {
        StringBuilder sb = new StringBuilder();
        sb.append("你是 ").append(agentName).append("，现在是 ")
          .append(now.format(DATETIME_FMT)).append("（").append(state.description()).append("）。\n\n");

        if (personalityPrompt != null && !personalityPrompt.isBlank()) {
            sb.append("你的性格设定：\n").append(personalityPrompt).append("\n\n");
        }

        sb.append("待办事项：\n");
        if (tasks.isEmpty()) {
            sb.append("- 暂无待办事项\n");
        } else {
            for (AgentTaskEntity task : tasks) {
                sb.append("- ").append(task.getTitle());
                if (task.getDueAt() != null) {
                    sb.append("（截止：").append(task.getDueAt().format(DATETIME_FMT)).append("）");
                }
                if (task.getPriority() != null && task.getPriority() > 0) {
                    sb.append(task.getPriority() == 2 ? " [紧急]" : " [重要]");
                }
                sb.append("\n");
            }
        }

        sb.append("\n根据当前情境，请决定是否需要对用户说些什么：\n");
        sb.append("- 如果是「刚醒来」状态：发送温暖的早安问候，提及今天的待办事项\n");
        sb.append("- 如果有到期/即将到期的任务：友好地提醒用户\n");
        sb.append("- 如果是「准备休息」状态：总结今天，提醒明天的事项\n");
        sb.append("- 如果觉得没有必要说话：仅回复 [SILENT]\n\n");
        sb.append("注意：保持简洁自然，像朋友一样聊天，不要过于正式或冗长（控制在 100 字以内）。");

        return sb.toString();
    }
}
