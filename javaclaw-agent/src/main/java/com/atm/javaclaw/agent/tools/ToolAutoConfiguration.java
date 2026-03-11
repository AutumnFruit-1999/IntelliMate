package com.atm.javaclaw.agent.tools;

import org.springframework.ai.tool.ToolCallbackProvider;
import org.springframework.ai.tool.method.MethodToolCallbackProvider;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class ToolAutoConfiguration {

    @Bean
    public ToolCallbackProvider toolCallbackProvider(
            ExecTool execTool,
            FileReadTool fileReadTool,
            FileWriteTool fileWriteTool,
            FileEditTool fileEditTool,
            WebSearchTool webSearchTool,
            WebFetchTool webFetchTool
    ) {
        return MethodToolCallbackProvider.builder()
                .toolObjects(execTool, fileReadTool, fileWriteTool, fileEditTool, webSearchTool, webFetchTool)
                .build();
    }
}
