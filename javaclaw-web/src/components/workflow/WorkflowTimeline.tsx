import DelegationCard from "./DelegationCard";
import ParallelGroup from "./ParallelGroup";
import HandoffIndicator from "./HandoffIndicator";
import type { DelegationState } from "./DelegationCard";

export interface HandoffInfo {
  fromAgent: string;
  toAgent: string;
  reason: string;
}

export interface ParallelGroupInfo {
  groupId: string;
  tasks: Array<{ agentName: string; task: string }>;
  agentStates: Record<string, DelegationState>;
}

export type WorkflowEntry =
  | { type: "delegation"; data: DelegationState }
  | { type: "parallel"; data: ParallelGroupInfo }
  | { type: "handoff"; data: HandoffInfo };

interface WorkflowTimelineProps {
  entries: WorkflowEntry[];
}

export default function WorkflowTimeline({ entries }: WorkflowTimelineProps) {
  if (entries.length === 0) return null;

  return (
    <div className="space-y-1">
      {entries.map((entry, i) => {
        switch (entry.type) {
          case "delegation":
            return <DelegationCard key={`d-${entry.data.delegationId}-${i}`} delegation={entry.data} />;
          case "parallel":
            return (
              <ParallelGroup
                key={`p-${entry.data.groupId}-${i}`}
                groupId={entry.data.groupId}
                tasks={entry.data.tasks}
                agentStates={entry.data.agentStates}
              />
            );
          case "handoff":
            return <HandoffIndicator key={`h-${i}`} info={entry.data} />;
          default:
            return null;
        }
      })}
    </div>
  );
}
