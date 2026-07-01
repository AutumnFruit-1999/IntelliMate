package com.atm.intellimate.agent.tools;

import com.atm.intellimate.core.exception.ToolExecutionException;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.stereotype.Component;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.TimeUnit;

@Component
public class ExecTool {

    private static final int DEFAULT_TIMEOUT_SECONDS = 30;

    @Tool(description = "在宿主机上执行 Shell 命令并返回完整输出（含退出码）。")
    public String exec(
            @ToolParam(description = "要执行的 Shell 命令") String command,
            @ToolParam(description = "工作目录（可选）", required = false) String workingDirectory,
            @ToolParam(description = "超时秒数（默认 30）", required = false) Integer timeoutSeconds
    ) {
        int timeout = (timeoutSeconds != null && timeoutSeconds > 0) ? timeoutSeconds : DEFAULT_TIMEOUT_SECONDS;

        try {
            ProcessBuilder pb = new ProcessBuilder("sh", "-c", command);
            if (workingDirectory != null && !workingDirectory.isBlank()) {
                pb.directory(new java.io.File(workingDirectory));
            }
            pb.redirectErrorStream(true);

            Process process = pb.start();
            StringBuilder output = new StringBuilder();
            try (BufferedReader reader = new BufferedReader(
                    new InputStreamReader(process.getInputStream(), StandardCharsets.UTF_8))) {
                String line;
                while ((line = reader.readLine()) != null) {
                    output.append(line).append("\n");
                }
            }

            boolean finished = process.waitFor(timeout, TimeUnit.SECONDS);
            if (!finished) {
                process.destroyForcibly();
                String out = output.toString();
                return "Command timed out after " + timeout + " seconds.\nPartial output:\n"
                        + out;
            }

            int exitCode = process.exitValue();
            return "Exit code: " + exitCode + "\n" + output.toString();
        } catch (ToolExecutionException e) {
            throw e;
        } catch (Exception e) {
            throw new ToolExecutionException("exec", "Failed to execute command: " + command, e);
        }
    }
}
