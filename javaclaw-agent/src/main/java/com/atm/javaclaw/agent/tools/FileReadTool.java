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
    private static final int MAX_LINES_PER_READ = 500;

    @Tool(description = """
            Read the contents of a file at the specified path.
            For large files (>500 lines), results are automatically paginated.
            Use startLine and lineCount parameters to read specific sections.
            The response includes totalLines so you know the file size.""")
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

            if (startLine == null && lineCount == null && lines.size() > MAX_LINES_PER_READ) {
                StringBuilder sb = new StringBuilder();
                for (int i = 0; i < MAX_LINES_PER_READ; i++) {
                    sb.append(i + 1).append("|").append(lines.get(i)).append("\n");
                }
                sb.append("\n--- [Showing lines 1-").append(MAX_LINES_PER_READ)
                  .append(" of ").append(lines.size()).append(" total lines. ")
                  .append("Use startLine=").append(MAX_LINES_PER_READ + 1)
                  .append(" to read more.] ---");
                return sb.toString();
            }

            int start = (startLine != null && startLine > 0) ? startLine - 1 : 0;
            int end = (lineCount != null && lineCount > 0) ? Math.min(start + lineCount, lines.size()) : lines.size();
            start = Math.min(start, lines.size());

            StringBuilder sb = new StringBuilder();
            for (int i = start; i < end; i++) {
                sb.append(i + 1).append("|").append(lines.get(i)).append("\n");
            }

            if (lines.size() > MAX_LINES_PER_READ) {
                sb.append("\n--- [Showing lines ").append(start + 1).append("-").append(end)
                  .append(" of ").append(lines.size()).append(" total lines.] ---");
            }

            return sb.toString();
        } catch (ToolExecutionException e) {
            throw e;
        } catch (Exception e) {
            throw new ToolExecutionException("file_read", "Failed to read file: " + path, e);
        }
    }
}
