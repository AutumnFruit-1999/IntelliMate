package com.atm.intellimate.memory.consolidation;

import com.atm.intellimate.memory.model.MemoryChunk;

import java.util.List;

/**
 * Builds the consolidation prompt for the LLM.
 * The prompt asks the LLM to produce both a summary and extracted facts.
 */
public class ConsolidationPromptBuilder {

    private static final String TEMPLATE = """
            你是一个专业的记忆巩固助手。请对以下对话执行三步处理：

            ## 第一步：主题识别与聚类
            从对话中识别出不同的讨论主题。注意：
            - 同一主题可能出现在对话的不同位置（如开头和结尾都在讨论吃饭），请将其聚合
            - 每个主题应该是一个独立的、完整的概念单元

            ## 第二步：冲突解决
            如果同一主题内出现矛盾信息（如先说"叫张三"后说"改叫李四"）：
            - content 字段只保留最终结论（当前有效值，如"用户将助手名称设定为李四"）
            - enriched 字段保留完整的变更历史（如"用户最初命名助手为'张三'，后改为'李四'。当前有效名称为'李四'。"），便于后续追溯
            - keywords 同时包含新旧值（如 ["李四", "张三", "助手名称", "改名"]），确保用新旧值均能检索到

            ## 第三步：每个主题生成一条记忆
            每条记忆包含以下字段：

            ### 字段要求
            - topic：主题标签（2-8字，简洁概括）
            - keywords：关键词数组（3-10个，包含关键实体、名称、具体值）
            - content：原始事实描述（简洁精确，20-50字，用于关键词检索）
            - enriched：语义增强描述（丰富详细，50-150字，包含完整上下文和场景信息，用于语义检索）
            - importance：重要度（0-1，核心身份/偏好>0.7，一般事件0.4-0.6，临时信息<0.4）

            ### 质量要求
            - keywords 必须包含具体实体（人名、项目名、技术名、具体值）
            - enriched 必须比 content 更丰富，包含场景、原因、上下文
            - 避免纯概括性描述，要有具体可区分的信息

            ### 输出格式
            请严格按以下 JSON 格式输出，不要包含任何其他文字：
            {
              "summary": "对话整体摘要（不超过 %d tokens）",
              "memories": [
                {
                  "topic": "助手身份设定",
                  "keywords": ["李四", "张三", "助手名称", "改名", "中文", "身份设定"],
                  "content": "用户将助手名称设定为李四，偏好使用中文交互",
                  "enriched": "用户在对话中先将助手命名为'张三'，后改为'李四'。当前有效名称为'李四'。用户偏好使用中文进行对话和回复，这是一个持久性的身份和语言偏好设定。",
                  "importance": 0.8
                }
              ]
            }

            ## 对话内容
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
