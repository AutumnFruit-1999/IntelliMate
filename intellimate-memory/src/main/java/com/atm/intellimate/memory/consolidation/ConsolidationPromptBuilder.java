package com.atm.intellimate.memory.consolidation;

import com.atm.intellimate.memory.model.MemoryChunk;

import java.util.List;

/**
 * Builds the consolidation prompt for the LLM.
 * The prompt asks the LLM to produce both a summary and extracted facts.
 */
public class ConsolidationPromptBuilder {

    private static final String TEMPLATE = """
            你是一个专业的记忆巩固助手。请对以下对话片段执行两项任务：

            ## 任务一：摘要（对应大脑的"要旨抽取"）
            将对话压缩为简洁摘要，保留：
            1. 用户的核心目标和当前状态
            2. 已完成的关键操作及其结果（特别是工具调用的关键输出）
            3. 尚未完成的任务和下一步计划
            4. 重要的文件路径、命令、错误信息等细节

            ## 任务二：事实提取（对应大脑的"知识提取"）
            从对话中提取可跨会话复用的事实，分为三类：
            - episodic: 这次会话中发生的重要事件
            - semantic: 关于项目、代码、用户偏好的知识
            - procedural: 成功的操作模式或工作流

            ## 输出格式
            请严格按以下 JSON 格式输出，不要包含任何其他文字：
            ```json
            {
              "summary": "简洁的对话摘要，不超过 %d tokens",
              "facts": [
                {"type": "semantic", "content": "描述", "importance": 0.8},
                {"type": "episodic", "content": "描述", "importance": 0.6}
              ]
            }
            ```

            ## 对话片段
            %s""";

    public String build(List<MemoryChunk> chunks, int maxSummaryTokens) {
        StringBuilder messages = new StringBuilder();
        for (int i = 0; i < chunks.size(); i++) {
            MemoryChunk chunk = chunks.get(i);
            messages.append(String.format("\n--- Chunk %d [%s / %s / importance=%.1f] ---\n",
                    i, chunk.type(), chunk.category(), chunk.importance()));
            String content = chunk.content();
            if (content.length() > 5000) {
                content = content.substring(0, 2000) + "\n... (truncated for consolidation) ...\n"
                        + content.substring(content.length() - 1000);
            }
            messages.append(content);
        }
        return String.format(TEMPLATE, maxSummaryTokens, messages);
    }
}
