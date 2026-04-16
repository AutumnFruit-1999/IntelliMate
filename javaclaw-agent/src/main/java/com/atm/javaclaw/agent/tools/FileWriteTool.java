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

    @Tool(description = "将内容写入文件，文件不存在时自动创建")
    public String writeFile(
            @ToolParam(description = "文件的绝对或相对路径") String path,
            @ToolParam(description = "要写入的文件内容") String content
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
