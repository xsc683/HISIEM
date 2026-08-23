package com.xscsiem.hsiem_platform.soar;

import com.xscsiem.hsiem_platform.onboarding.ConflictException;
import com.xscsiem.hsiem_platform.onboarding.NotFoundException;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;

/** Durable execution kernel. Node handlers calculate outcomes; this class owns every state transition. */
@Service
public class SoarExecutionEngine {

    private static final Logger LOG = LoggerFactory.getLogger(SoarExecutionEngine.class);

    private final SoarStore store;
    private final SoarNodeHandlerRegistry handlers;
    private final SoarTemplateResolver templates;
    private final SoarGraphRouter router;
    private final Counter succeeded;
    private final Counter failed;
    private final Counter retried;

    public SoarExecutionEngine(SoarStore store, SoarNodeHandlerRegistry handlers,
                               SoarTemplateResolver templates, SoarGraphRouter router,
                               MeterRegistry registry) {
        this.store = store;
        this.handlers = handlers;
        this.templates = templates;
        this.router = router;
        this.succeeded = registry.counter("siem.soar.execution.succeeded");
        this.failed = registry.counter("siem.soar.execution.failed");
        this.retried = registry.counter("siem.soar.node.retried");
    }

    /** Executes one durable node attempt. A later worker claim advances the next node. */
    public void process(SoarExecution claimed) {
        try {
            processOwned(claimed);
        } catch (SoarLeaseLostException lost) {
            LOG.warn("SOAR stale worker result fenced execution={} owner={} token={}",
                    claimed.id(), claimed.leaseOwner(), claimed.fencingToken());
        }
    }

    private void processOwned(SoarExecution claimed) {
        store.requireLease(claimed);
        SoarExecution execution = store.getExecution(claimed.id());
        store.requireLease(execution);
        if (execution.cancelRequested() || "cancelled".equals(execution.status())) return;
        PlaybookGraph.Node node = execution.graphSnapshot().nodes().stream()
                .filter(candidate -> candidate.id().equals(execution.currentNodeId())).findFirst()
                .orElseThrow(() -> new IllegalStateException(
                        "执行快照缺少当前节点: " + execution.currentNodeId()));
        SoarNodeHandler handler = handlers.require(node.type());
        SoarRetryPolicy retryPolicy = SoarRetryPolicy.resolve(node, handler);
        Map<String, Map<String, Object>> outputs = store.nodeOutputs(execution.id());
        Map<String, Object> variables = variables(outputs);
        SoarExecution.NodeRun nodeRun = store.resumableNodeRun(execution.id(), node.id());
        SoarExecutionContext context = context(execution, node, nodeRun, outputs, variables);

        try {
            Map<String, Object> resolvedConfig = templates.resolveMap(node.config(), context.templateVariables());
            if (nodeRun == null) {
                SoarStore.StartAttempt started = store.startNode(execution, node,
                        context.persistedInput(resolvedConfig), retryPolicy.maxAttempts());
                nodeRun = started.run();
                if (started.exhausted()) {
                    terminalFailure(execution, node, nodeRun,
                            "节点执行次数已耗尽: " + retryPolicy.maxAttempts(), null);
                    return;
                }
                context = context(execution, node, nodeRun, outputs, variables);
                resolvedConfig = templates.resolveMap(node.config(), context.templateVariables());
                store.updateNodeInput(execution, nodeRun.id(), context.persistedInput(resolvedConfig));
            }
            SoarNodeResult result = handler.execute(context, resolvedConfig);
            commit(execution, node, nodeRun, handler, result);
        } catch (SoarLeaseLostException lost) {
            throw lost;
        } catch (RuntimeException e) {
            String message = message(e);
            if (nodeRun != null && retryable(e) && nodeRun.attempt() < retryPolicy.maxAttempts()) {
                Instant nextAttempt = Instant.now().plus(retryPolicy.delayAfter(nodeRun.attempt()));
                store.scheduleRetry(execution, nodeRun.id(), message, nextAttempt);
                retried.increment();
                LOG.warn("SOAR node retry scheduled execution={} node={} attempt={} next={}: {}",
                        execution.id(), node.id(), nodeRun.attempt(), nextAttempt, message);
            } else {
                terminalFailure(execution, node, nodeRun, message, e);
            }
        }
    }

    private void commit(SoarExecution execution, PlaybookGraph.Node node,
                        SoarExecution.NodeRun nodeRun, SoarNodeHandler handler,
                        SoarNodeResult result) {
        if (result == null || result.outcome() == null) {
            throw new IllegalStateException("NodeHandler 返回了空结果: " + handler.type());
        }
        switch (result.outcome()) {
            case ADVANCE -> {
                if (!handler.outgoingBranches().contains(result.branch())) {
                    throw new IllegalStateException("节点 " + node.id() + " 返回了非法分支: " + result.branch());
                }
                store.advance(execution, nodeRun.id(), result.output(),
                        router.next(execution.graphSnapshot(), node.id(), result.branch()));
            }
            case COMPLETE -> {
                if (!handler.outgoingBranches().isEmpty()) {
                    throw new IllegalStateException("只有终止节点可以结束执行: " + node.id());
                }
                store.succeed(execution, nodeRun.id(), result.output());
                succeeded.increment();
            }
            case WAIT -> {
                if (result.resumeAt() == null || !result.resumeAt().isAfter(Instant.now())) {
                    throw new IllegalStateException("等待节点必须返回未来的恢复时间");
                }
                store.waitUntil(execution, nodeRun.id(), result.resumeAt());
            }
            case WAIT_HUMAN -> {
                if (result.approvalPrompt() == null || result.approvalPrompt().isBlank()) {
                    throw new IllegalStateException("人工节点必须返回审批提示语");
                }
                store.createApproval(execution, node, nodeRun, result.approvalPrompt());
            }
        }
    }

    private SoarExecutionContext context(SoarExecution execution, PlaybookGraph.Node node,
                                         SoarExecution.NodeRun nodeRun,
                                         Map<String, Map<String, Object>> outputs,
                                         Map<String, Object> variables) {
        return new SoarExecutionContext(execution, execution.triggerEnvelope(), node, nodeRun,
                execution.payloadSnapshot(), outputs, variables);
    }

    private Map<String, Object> variables(Map<String, Map<String, Object>> outputs) {
        Map<String, Object> variables = new LinkedHashMap<>();
        for (Map<String, Object> output : outputs.values()) {
            Object candidate = output.get("variables");
            if (candidate instanceof Map<?, ?> values) {
                values.forEach((key, value) -> variables.put(String.valueOf(key), value));
            }
        }
        return variables;
    }

    private boolean retryable(RuntimeException error) {
        return !(error instanceof IllegalArgumentException
                || error instanceof ConflictException
                || error instanceof NotFoundException);
    }

    private void terminalFailure(SoarExecution execution, PlaybookGraph.Node node,
                                 SoarExecution.NodeRun run, String message, RuntimeException cause) {
        store.fail(execution, run == null ? null : run.id(), message);
        failed.increment();
        if (cause == null) {
            LOG.error("SOAR node failed execution={} node={} type={}: {}",
                    execution.id(), node.id(), node.type(), message);
        } else {
            LOG.error("SOAR node failed execution={} node={} type={}",
                    execution.id(), node.id(), node.type(), cause);
        }
    }

    private String message(RuntimeException error) {
        return error.getMessage() == null ? error.getClass().getSimpleName() : error.getMessage();
    }
}
