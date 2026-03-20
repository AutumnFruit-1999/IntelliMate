package com.atm.javaclaw.agent.tools;

import com.atm.javaclaw.core.exception.ToolExecutionException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.stereotype.Component;

import java.nio.file.Files;
import java.nio.file.Path;

@Component
public class FileWriteTool {

    private static final Logger log = LoggerFactory.getLogger(FileWriteTool.class);

    @Tool(description = "Write content to a file, creating it if it does not exist")
    public String writeFile(
            @ToolParam(description = "Absolute or relative path to the file") String path,
            @ToolParam(description = "Content to write to the file") String content
    ) {
        log.info("Writing file: {}", path);

        try {
            Path filePath = Path.of(path);
            Files.createDirectories(filePath.getParent());
            Files.writeString(filePath, content);
            return "Successfully wrote " + content.length() + " characters to " + path;
        } catch (Exception e) {
            throw new ToolExecutionException("file_write", "Failed to write file: " + path, e);
        }
    }
}
