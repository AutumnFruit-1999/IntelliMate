package com.atm.intellimate.gateway.tools;

import org.springframework.ai.tool.ToolCallbackProvider;
import org.springframework.ai.tool.method.MethodToolCallbackProvider;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class TaskToolAutoConfiguration {

    @Bean
    public ToolCallbackProvider taskToolCallbackProvider(
            TaskManagementTool taskManagementTool,
            ScheduledJobManagementTool scheduledJobManagementTool
    ) {
        return MethodToolCallbackProvider.builder()
                .toolObjects(taskManagementTool, scheduledJobManagementTool)
                .build();
    }
}
