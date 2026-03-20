package com.atm.javaclaw.agent.tools;

import com.atm.javaclaw.core.exception.ToolExecutionException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.stereotype.Component;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.TimeUnit;

@Component
public class ExecTool {

    private static final Logger log = LoggerFactory.getLogger(ExecTool.class);
    private static final int DEFAULT_TIMEOUT_SECONDS = 30;
    private static final int MAX_OUTPUT_CHARS = 8_000;

    @Tool(description = "Execute a shell command on the host machine and return the output. Output exceeding 8000 chars will be truncated (head+tail preserved).")
    public String exec(
            @ToolParam(description = "Shell command to execute") String command,
            @ToolParam(description = "Working directory (optional)", required = false) String workingDirectory,
            @ToolParam(description = "Timeout in seconds (default 30)", required = false) Integer timeoutSeconds
    ) {
        int timeout = (timeoutSeconds != null && timeoutSeconds > 0) ? timeoutSeconds : DEFAULT_TIMEOUT_SECONDS;
        log.info("Executing command: {} (timeout={}s)", command, timeout);

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
                return "Command timed out after " + timeout + " seconds.\nPartial output:\n"
                        + truncateOutput(output.toString());
            }

            int exitCode = process.exitValue();
            return "Exit code: " + exitCode + "\n" + truncateOutput(output.toString());
        } catch (ToolExecutionException e) {
            throw e;
        } catch (Exception e) {
            throw new ToolExecutionException("exec", "Failed to execute command: " + command, e);
        }
    }

    private static String truncateOutput(String output) {
        if (output.length() <= MAX_OUTPUT_CHARS) {
            return output;
        }
        int half = MAX_OUTPUT_CHARS / 2;
        return output.substring(0, half)
                + "\n... [" + output.length() + " chars total, truncated] ...\n"
                + output.substring(output.length() - half);
    }
}
