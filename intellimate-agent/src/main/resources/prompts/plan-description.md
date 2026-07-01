管理任务计划。支持三种操作：
- create：创建计划，需要 title 和 steps 数组（每个步骤包含 title、description、verification）
- step_done：标记步骤完成，需要 stepIndex 和可选的 resultSummary
- complete：标记整个计划完成，可选 summary
