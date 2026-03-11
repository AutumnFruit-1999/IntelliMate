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
public class FileReadTool {

    private static final Logger log = LoggerFactory.getLogger(FileReadTool.class);

    @Tool(description = "Read the contents of a file at the specified path")
    public String readFile(
            @ToolParam(description = "Absolute or relative path to the file") String path,
            @ToolParam(description = "Starting line number (1-based, optional)", required = false) Integer startLine,
            @ToolParam(description = "Number of lines to read (optional)", required = false) Integer lineCount
    ) {
        log.info("Reading file: {}", path);

        try {
            Path filePath = Path.of(path);
            if (!Files.exists(filePath)) {
                return "Error: File not found: " + path;
            }
            if (!Files.isReadable(filePath)) {
                return "Error: File is not readable: " + path;
            }

            var lines = Files.readAllLines(filePath);

            int start = (startLine != null && startLine > 0) ? startLine - 1 : 0;
            int end = (lineCount != null && lineCount > 0) ? Math.min(start + lineCount, lines.size()) : lines.size();
            start = Math.min(start, lines.size());

            StringBuilder sb = new StringBuilder();
            for (int i = start; i < end; i++) {
                sb.append(i + 1).append("|").append(lines.get(i)).append("\n");
            }
            return sb.toString();
        } catch (ToolExecutionException e) {
            throw e;
        } catch (Exception e) {
            throw new ToolExecutionException("file_read", "Failed to read file: " + path, e);
        }
    }
}
