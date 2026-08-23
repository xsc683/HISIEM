package com.xscsiem.hsiem_platform.soar;

import java.util.List;
import java.util.Map;
import java.time.Instant;

/** SOAR 持久化边界；所有状态迁移都由条件更新和租约保护。 */
public interface SoarExecutionStore {

    boolean create(SoarExecution execution);

    SoarExecution find(String id);

    List<SoarExecution> list(String tenantId, int size);

    default List<SoarExecution> list(int size) {
        return list("default", size);
    }

    SoarExecution claimNext(String owner, Instant now, Instant leaseUntil);

    boolean heartbeat(String executionId, String owner, Instant leaseUntil);

    SoarStepExecution findStep(String executionId, String stepId);

    List<SoarStepExecution> listSteps(String executionId);

    int startNode(String executionId, int index, SoarPlaybook.Node node,
                  int maxAttempts, Map<String, Object> input);

    void finishNode(String executionId, String stepId, String status,
                    Map<String, Object> output, String error);

    void finishWaitingNode(String executionId, String stepId, String status,
                           Map<String, Object> output, String error);

    void waitForChild(String executionId, String stepId, Map<String, Object> output);

    void resetNodes(String executionId, List<String> stepIds);

    void saveProgress(String executionId, String owner, List<String> frontier,
                      String currentNode, Map<String, Object> context, int nodesExecuted);

    void release(String executionId, String owner, List<String> frontier, String currentNode,
                 Map<String, Object> context, int nodesExecuted, Instant nextRunAt, String error);

    void waitForApproval(String executionId, String owner, String stepId, String message,
                         List<String> frontier, Map<String, Object> context, int nodesExecuted);

    boolean resolveApproval(String executionId, String stepId, boolean approved, String actor,
                            List<String> frontier, Map<String, Object> context, boolean continueExecution);

    void finishExecution(String executionId, String owner, String status, String error,
                         Map<String, Object> context, int nodesExecuted);

    boolean prepareRetry(String executionId);

    boolean requestCancel(String executionId);

    boolean requestPause(String executionId);

    boolean resume(String executionId);

    int recoverExpiredLeases(Instant now);

    void appendEvent(String executionId, String eventType, String nodeId,
                     String actor, Map<String, Object> details);

    List<SoarExecutionEvent> listEvents(String executionId);
}
