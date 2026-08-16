export const operationErrorCodes = Object.freeze({
  forbiddenTool: "FORBIDDEN_TOOL",
  resultContextMismatch: "RESULT_CONTEXT_MISMATCH",
  staleAssessmentVersion: "STALE_ASSESSMENT_VERSION",
});

export class OperationContractError extends Error {
  constructor(code, message) {
    super(message);
    this.name = "OperationContractError";
    this.code = code;
  }
}

function assertEqual(actual, expected, field) {
  if (actual !== expected) {
    throw new OperationContractError(
      operationErrorCodes.resultContextMismatch,
      `Result ${field} does not match the originating operation.`,
    );
  }
}

export function assertResultMatchesOperation(request, result) {
  assertEqual(
    result.context.operationId,
    request.context.operationId,
    "operationId",
  );
  assertEqual(
    result.context.operationType,
    request.context.operationType,
    "operationType",
  );
  assertEqual(
    result.context.contractVersion,
    request.context.contractVersion,
    "contractVersion",
  );
  assertEqual(
    result.context.workspaceId,
    request.context.workspaceId,
    "workspaceId",
  );
  assertEqual(
    result.context.assessment.assessmentId,
    request.context.assessment.assessmentId,
    "assessmentId",
  );

  if (
    result.context.assessment.basedOnVersion !==
    request.context.assessment.expectedVersion
  ) {
    throw new OperationContractError(
      operationErrorCodes.staleAssessmentVersion,
      "Result assessment version is stale.",
    );
  }

  const allowedTools = new Set(request.context.allowedTools);
  const forbiddenInvocation = result.toolInvocations.find(
    ({ toolName }) => !allowedTools.has(toolName),
  );

  if (forbiddenInvocation) {
    throw new OperationContractError(
      operationErrorCodes.forbiddenTool,
      `Tool ${forbiddenInvocation.toolName} is not allowed for this operation.`,
    );
  }
}
