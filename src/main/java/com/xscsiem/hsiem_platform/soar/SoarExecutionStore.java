package com.xscsiem.hsiem_platform.soar;

import java.util.List;
import java.util.Map;
import java.time.Instant;

/** SOAR 执行持久化边界；条件更新保证同一次执行只有一个推进者。 */
public interface SoarExecutionStore {

    void create(SoarExecution execution);

    SoarExecution find(String id);

    List<SoarExecution> list(int size);

    boolean claimQueued(String id);

    SoarStepExecution findStep(String executionId, String stepId);

    List<SoarStepExecution> listSteps(String executionId);

    void startStep(String executionId, int index, SoarPlaybook.Step step, Map<String, Object> input);

    void finishStep(String executionId, String stepId, String status,
                    Map<String, Object> output, String error);

    void advance(String executionId, int nextStep);

    void waitForApproval(String executionId, int stepIndex, String stepId, String message);

    boolean resolveApproval(String executionId, String stepId, boolean approved, String actor);

    void finishExecution(String executionId, String status, String error);

    boolean prepareRetry(String executionId);

    int recoverStale(Instant cutoff);
}
