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
public class FileEditTool {

    private static final Logger log = LoggerFactory.getLogger(FileEditTool.class);

    @Tool(description = "通过精确匹配并替换字符串来编辑文件")
    public String editFile(
            @ToolParam(description = "文件的绝对或相对路径") String path,
            @ToolParam(description = "要查找并替换的精确字符串") String oldString,
            @ToolParam(description = "替换后的新字符串") String newString
    ) {
        log.info("Editing file: {}", path);

        try {
            Path filePath = Path.of(path);
            if (!Files.exists(filePath)) {
                return "Error: File not found: " + path;
            }

            String content = Files.readString(filePath);
            if (!content.contains(oldString)) {
                return "Error: Could not find the specified string in " + path;
            }

            long occurrences = countOccurrences(content, oldString);
            if (occurrences > 1) {
                return "Error: Found " + occurrences + " occurrences of the string. " +
                        "Please provide a more specific string to ensure a unique match.";
            }

            String newContent = content.replace(oldString, newString);
            Files.writeString(filePath, newContent);
            return "Successfully edited " + path;
        } catch (ToolExecutionException e) {
            throw e;
        } catch (Exception e) {
            throw new ToolExecutionException("file_edit", "Failed to edit file: " + path, e);
        }
    }

    private long countOccurrences(String text, String target) {
        long count = 0;
        int idx = 0;
        while ((idx = text.indexOf(target, idx)) != -1) {
            count++;
            idx += target.length();
        }
        return count;
    }
}
