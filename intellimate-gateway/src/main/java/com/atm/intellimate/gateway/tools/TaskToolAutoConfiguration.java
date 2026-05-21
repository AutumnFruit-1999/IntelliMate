package com.atm.intellimate.gateway.tools;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.tool.ToolCallbackProvider;
import org.springframework.ai.tool.method.MethodToolCallbackProvider;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class TaskToolAutoConfiguration {

    private static final Logger log = LoggerFactory.getLogger(TaskToolAutoConfiguration.class);

    @Bean
    public ToolCallbackProvider taskToolCallbackProvider(
            TaskManagementTool taskManagementTool,
            ScheduledJobManagementTool scheduledJobManagementTool
    ) {
        log.info("TaskToolAutoConfiguration: registering task management tools (todo CRUD + scheduled job CRUD)");
        return MethodToolCallbackProvider.builder()
                .toolObjects(taskManagementTool, scheduledJobManagementTool)
                .build();
    }
}
