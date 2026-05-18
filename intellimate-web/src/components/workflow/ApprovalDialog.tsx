interface ApprovalDialogProps {
  workerAgent: string;
  task: string;
  onApprove: () => void;
  onReject: () => void;
}

export default function ApprovalDialog({
  workerAgent,
  task,
  onApprove,
  onReject,
}: ApprovalDialogProps) {
  return (
    <div className="fixed inset-0 bg-black/30 flex items-center justify-center z-50">
      <div className="bg-white rounded-lg shadow-xl p-6 max-w-md w-full mx-4">
        <h3 className="text-lg font-semibold mb-2">委派审批</h3>
        <p className="text-sm text-gray-600 mb-1">
          即将委派任务给 <span className="font-mono font-semibold">{workerAgent}</span>
        </p>
        <div className="bg-gray-50 border rounded p-3 text-sm text-gray-700 mb-4 max-h-40 overflow-y-auto whitespace-pre-wrap">
          {task}
        </div>
        <div className="flex justify-end gap-3">
          <button
            onClick={onReject}
            className="px-4 py-2 text-sm text-gray-600 bg-gray-100 rounded-md hover:bg-gray-200 transition-colors"
          >
            拒绝
          </button>
          <button
            onClick={onApprove}
            className="px-4 py-2 text-sm text-white bg-blue-600 rounded-md hover:bg-blue-700 transition-colors"
          >
            批准
          </button>
        </div>
      </div>
    </div>
  );
}
