package com.atm.javaclaw.agent.tools;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

/**
 * One step in a {@link WritePlanTool#writePlan} call; deserialized from model tool arguments.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record PlanStepArg(String title, String description) {}
