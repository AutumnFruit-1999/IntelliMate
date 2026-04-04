package com.atm.javaclaw.agent.tools;

import org.springframework.ai.tool.ToolCallbackProvider;
import org.springframework.ai.tool.method.MethodToolCallbackProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.ArrayList;
import java.util.List;

@Configuration
public class ToolAutoConfiguration {

    @Bean
    public ToolCallbackProvider toolCallbackProvider(
            ExecTool execTool,
            FileReadTool fileReadTool,
            FileWriteTool fileWriteTool,
            FileEditTool fileEditTool,
            WebSearchTool webSearchTool,
            WebFetchTool webFetchTool,
            @Autowired(required = false) GetSkillContentTool getSkillContentTool,
            @Autowired(required = false) UpdatePlanTool updatePlanTool
    ) {
        List<Object> tools = new ArrayList<>(List.of(
                execTool, fileReadTool, fileWriteTool, fileEditTool, webSearchTool, webFetchTool));
        if (getSkillContentTool != null) {
            tools.add(getSkillContentTool);
        }
        if (updatePlanTool != null) {
            tools.add(updatePlanTool);
        }
        return MethodToolCallbackProvider.builder()
                .toolObjects(tools.toArray())
                .build();
    }
}
