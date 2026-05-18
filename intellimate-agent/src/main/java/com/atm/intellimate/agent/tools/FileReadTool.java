package com.atm.intellimate.agent.tools;

import com.atm.intellimate.core.exception.ToolExecutionException;
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

    @Tool(description = """
            读取指定路径的文件内容；未指定 startLine/lineCount 时返回全文。
            可通过 startLine 和 lineCount 参数读取特定区间。
            返回结果在部分读取时会标注行范围。""")
    public String readFile(
            @ToolParam(description = "文件的绝对或相对路径") String path,
            @ToolParam(description = "起始行号（从 1 开始，可选）", required = false) Integer startLine,
            @ToolParam(description = "读取的行数（可选）", required = false) Integer lineCount
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

            if (start > 0 || end < lines.size()) {
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
