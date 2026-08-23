package com.xscsiem.hsiem_platform.soar;

import com.xscsiem.hsiem_platform.control.ControlPlaneStore;
import jakarta.annotation.PreDestroy;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/**
 * 图 conductor：只决定节点何时可运行和下一条边；动作由独立 Runner 执行。
 * 每批节点落库后才推进 frontier，进程退出后由 Worker 租约恢复。
 */
@Component
public class SoarEngine {

    private static final Set<String> JOIN_TERMINAL = Set.of("succeeded", "skipped", "failed", "rejected");
    private final SoarExecutionStore store;
    private final SoarActionExecutor actions;
    private final SoarChildExecutionLauncher children;
    private final ControlPlaneStore control;
    private final ExecutorService actionPool = Executors.newVirtualThreadPerTaskExecutor();
    private final Duration leaseDuration;
    private final int maxNodes;
    private final int maxParallel;

    @Autowired
    public SoarEngine(SoarExecutionStore store, SoarActionExecutor actions,
                      SoarChildExecutionLauncher children, ControlPlaneStore control,
                      @Value("${app.soar.worker-lease:PT45S}") Duration leaseDuration,
                      @Value("${app.soar.max-node-executions:500}") int maxNodes,
                      @Value("${app.soar.max-parallel-actions:8}") int maxParallel) {
        this.store = store;
        this.actions = actions;
        this.children = children;
        this.control = control;
        this.leaseDuration = leaseDuration;
        this.maxNodes = Math.max(10, maxNodes);
        this.maxParallel = Math.max(1, Math.min(maxParallel, 32));
    }

    /** 单元测试兼容构造器；高级节点测试使用完整 Spring 构造器。 */
    public SoarEngine(SoarExecutionStore store, SoarActionExecutor actions, ControlPlaneStore control,
                      Duration leaseDuration, int maxNodes, int maxParallel) {
        this.store = store;
        this.actions = actions;
        this.children = null;
        this.control = control;
        this.leaseDuration = leaseDuration;
        this.maxNodes = Math.max(10, maxNodes);
        this.maxParallel = Math.max(1, Math.min(maxParallel, 32));
    }

    public void process(SoarExecution execution, String owner) {
        SoarGraph graph = SoarGraph.compile(execution.playbookSnapshot());
        LinkedHashSet<String> frontier = new LinkedHashSet<>(execution.frontier());
        if (frontier.isEmpty() && execution.nodesExecuted() == 0) frontier.add(graph.entrypoint());
        Map<String, Object> context = mutableContext(execution.context());
        int nodesExecuted = execution.nodesExecuted();
        try {
            while (true) {
                SoarExecution fresh = store.find(execution.id());
                if (fresh == null || !"running".equals(fresh.status()) || !owner.equals(fresh.leaseOwner())) return;
                if (fresh.cancelRequested()) {
                    store.finishExecution(execution.id(), owner, "cancelled", "用户取消", context, nodesExecuted);
                    event(execution, "execution.cancelled", null, Map.of());
                    control.audit(execution.actor(), "soar.cancelled", execution.id());
                    return;
                }
                if (fresh.pauseRequested()) {
                    release(execution, owner, frontier, context, nodesExecuted, Instant.now(), null);
                    store.requestPause(execution.id());
                    event(execution, "execution.paused", null, Map.of());
                    return;
                }
                if (nodesExecuted >= maxNodes) {
                    fail(execution, owner, context, nodesExecuted,
                            "节点执行次数超过上限 " + maxNodes + "，可能存在未收敛循环", null);
                    return;
                }
                if (frontier.isEmpty()) {
                    store.finishExecution(execution.id(), owner, "succeeded", null, context, nodesExecuted);
                    event(execution, "execution.succeeded", null, Map.of("nodesExecuted", nodesExecuted));
                    control.audit(execution.actor(), "soar.succeeded", execution.id());
                    return;
                }

                Map<String, SoarStepExecution> completed = stepMap(execution.id());
                List<SoarPlaybook.Node> ready = frontier.stream().map(graph::node)
                        .filter(node -> node != null && ready(node, graph, completed)).toList();
                if (ready.isEmpty()) {
                    fail(execution, owner, context, nodesExecuted,
                            "图无法推进：join 依赖未满足或 frontier 包含未知节点 " + frontier, null);
                    return;
                }

                // 崩溃可能发生在节点结果落库之后、frontier 落库之前；成功节点只做路由，不重复动作。
                boolean recoveredResult = false;
                for (SoarPlaybook.Node node : ready) {
                    SoarStepExecution previous = completed.get(node.id());
                    if (previous != null && Set.of("succeeded", "skipped").contains(previous.status())) {
                        applyNodeResult(context, node, previous.status(), previous.output(), previous.error(), previous.attempt());
                        route(frontier, node, "success", context);
                        recoveredResult = true;
                    } else if (previous != null && "failed".equals(previous.status())) {
                        applyNodeResult(context, node, "failed", previous.output(),
                                previous.error(), previous.attempt());
                        List<String> targets = transitionTargets(node, "failure", context);
                        if (targets.isEmpty()) {
                            fail(execution, owner, context, nodesExecuted,
                                    previous.error() == null ? "节点失败且没有失败路由" : previous.error(), node.id());
                            return;
                        }
                        replace(frontier, node.id(), targets);
                        event(execution, "node.failure_route_recovered", node.id(),
                                Map.of("targets", targets, "error",
                                        previous.error() == null ? "" : previous.error()));
                        recoveredResult = true;
                    }
                }
                if (recoveredResult) {
                    store.saveProgress(execution.id(), owner, List.copyOf(frontier), first(frontier),
                            context, nodesExecuted);
                    continue;
                }

                List<SoarPlaybook.Node> actionBatch = ready.stream()
                        .filter(node -> "action".equals(node.type()))
                        .limit(maxParallel).toList();
                if (!actionBatch.isEmpty()) {
                    BatchOutcome outcome = runActions(execution, owner, actionBatch, frontier,
                            context, nodesExecuted);
                    nodesExecuted = outcome.nodesExecuted();
                    if (outcome.terminal()) return;
                    if (outcome.nextRunAt() != null) {
                        release(execution, owner, frontier, context, nodesExecuted,
                                outcome.nextRunAt(), outcome.error());
                        return;
                    }
                    store.saveProgress(execution.id(), owner, List.copyOf(frontier), first(frontier),
                            context, nodesExecuted);
                    continue;
                }

                SoarPlaybook.Node node = ready.get(0);
                if ("loop".equals(node.type())) {
                    int iteration = loopIteration(context, node.id());
                    int maxIterations = integer(SoarGraph.parameters(node).getOrDefault("maxIterations", 10), 10);
                    boolean repeat = iteration < maxIterations && SoarExpression.matches(node.when(), context);
                    nodesExecuted++;
                    if (repeat) {
                        setLoopIteration(context, node.id(), iteration + 1);
                        setVariable(context, String.valueOf(SoarGraph.parameters(node)
                                .getOrDefault("iterationVariable", "iteration")), iteration);
                        List<String> targets = transitionTargets(node, "success", context);
                        store.resetNodes(execution.id(), loopBodyNodes(graph, node.id(), targets));
                        replace(frontier, node.id(), targets);
                        event(execution, "node.loop_iteration", node.id(), Map.of(
                                "iteration", iteration, "targets", targets));
                    } else {
                        List<String> targets = transitionTargets(node, "complete", context);
                        replace(frontier, node.id(), targets);
                        event(execution, "node.loop_completed", node.id(), Map.of(
                                "iterations", iteration, "maxIterationsReached", iteration >= maxIterations,
                                "targets", targets));
                    }
                    store.saveProgress(execution.id(), owner, List.copyOf(frontier), first(frontier),
                            context, nodesExecuted);
                    continue;
                }
                if (!SoarExpression.matches(node.when(), context)) {
                    store.startNode(execution.id(), nodesExecuted, node, 1, Map.of());
                    store.finishNode(execution.id(), node.id(), "skipped", Map.of("reason", "guard_false"), null);
                    nodesExecuted++;
                    applyNodeResult(context, node, "skipped", Map.of("reason", "guard_false"), null, 1);
                    route(frontier, node, "success", context);
                    event(execution, "node.skipped", node.id(), Map.of());
                    store.saveProgress(execution.id(), owner, List.copyOf(frontier), first(frontier),
                            context, nodesExecuted);
                    continue;
                }
                if ("decision".equals(node.type())) {
                    store.startNode(execution.id(), nodesExecuted, node, 1, Map.of());
                    List<String> targets = transitionTargets(node, "success", context);
                    Map<String, Object> output = Map.of("selectedTargets", targets);
                    store.finishNode(execution.id(), node.id(), "succeeded", output, null);
                    nodesExecuted++;
                    applyNodeResult(context, node, "succeeded", output, null, 1);
                    replace(frontier, node.id(), targets);
                    event(execution, "node.decision", node.id(), output);
                    store.saveProgress(execution.id(), owner, List.copyOf(frontier), first(frontier),
                            context, nodesExecuted);
                    continue;
                }
                if ("subplaybook".equals(node.type())) {
                    SoarStepExecution previous = completed.get(node.id());
                    if (previous == null) {
                        if (children == null) throw new IllegalStateException("子 Playbook launcher 未配置");
                        Map<String, Object> input = SoarExpression.resolveMap(SoarGraph.parameters(node), context);
                        SoarExecution child = children.launch(execution, node, input);
                        store.startNode(execution.id(), nodesExecuted, node, 1, input);
                        Map<String, Object> output = Map.of("childExecutionId", child.id(),
                                "childPlaybookId", child.playbookId(), "status", child.status());
                        store.waitForChild(execution.id(), node.id(), output);
                        nodesExecuted++;
                        event(execution, "node.child_started", node.id(), output);
                        release(execution, owner, frontier, context, nodesExecuted,
                                Instant.now().plusSeconds(1), null);
                        return;
                    }
                    String childId = String.valueOf(previous.output().get("childExecutionId"));
                    SoarExecution child = store.find(childId);
                    if (child == null) throw new IllegalStateException("子 Playbook 执行丢失: " + childId);
                    if (!Set.of("succeeded", "failed", "rejected", "cancelled").contains(child.status())) {
                        release(execution, owner, frontier, context, nodesExecuted,
                                Instant.now().plusSeconds(1), null);
                        return;
                    }
                    boolean success = "succeeded".equals(child.status());
                    Map<String, Object> output = Map.of("childExecutionId", child.id(),
                            "childPlaybookId", child.playbookId(), "status", child.status());
                    store.finishWaitingNode(execution.id(), node.id(), success ? "succeeded" : "failed",
                            output, success ? null : child.error());
                    applyNodeResult(context, node, success ? "succeeded" : "failed", output,
                            success ? null : child.error(), 1);
                    List<String> targets = transitionTargets(node, success ? "success" : "failure", context);
                    if (!success && targets.isEmpty()) {
                        fail(execution, owner, context, nodesExecuted,
                                "子 Playbook 失败: " + child.status() + " " + child.error(), node.id());
                        return;
                    }
                    replace(frontier, node.id(), targets);
                    event(execution, "node.child_completed", node.id(), output);
                    store.saveProgress(execution.id(), owner, List.copyOf(frontier), first(frontier),
                            context, nodesExecuted);
                    continue;
                }
                if ("map".equals(node.type())) {
                    MapOutcome outcome = runMap(execution, owner, node, context, nodesExecuted);
                    nodesExecuted = outcome.nodesExecuted();
                    applyNodeResult(context, node, outcome.success() ? "succeeded" : "failed",
                            outcome.output(), outcome.error(), 1);
                    List<String> targets = transitionTargets(node, outcome.success() ? "success" : "failure", context);
                    if (!outcome.success() && targets.isEmpty()) {
                        fail(execution, owner, context, nodesExecuted, outcome.error(), node.id());
                        return;
                    }
                    replace(frontier, node.id(), targets);
                    store.saveProgress(execution.id(), owner, List.copyOf(frontier), first(frontier),
                            context, nodesExecuted);
                    continue;
                }
                if ("delay".equals(node.type())) {
                    store.startNode(execution.id(), nodesExecuted, node, 1, Map.of());
                    Instant resumeAt = Instant.now().plusSeconds(node.delaySeconds());
                    Map<String, Object> output = Map.of("resumeAt", resumeAt.toString());
                    store.finishNode(execution.id(), node.id(), "succeeded", output, null);
                    nodesExecuted++;
                    applyNodeResult(context, node, "succeeded", output, null, 1);
                    route(frontier, node, "success", context);
                    event(execution, "node.delayed", node.id(), output);
                    release(execution, owner, frontier, context, nodesExecuted, resumeAt, null);
                    return;
                }
                if ("approval".equals(node.type())) {
                    Map<String, Object> input = SoarExpression.resolveMap(SoarGraph.parameters(node), context);
                    store.startNode(execution.id(), nodesExecuted, node, 1, input);
                    String message = String.valueOf(input.getOrDefault("message", node.name()));
                    nodesExecuted++;
                    store.waitForApproval(execution.id(), owner, node.id(), message,
                            List.copyOf(frontier), context, nodesExecuted);
                    event(execution, "approval.requested", node.id(), Map.of(
                            "requiredRole", input.getOrDefault("requiredRole", "analyst"),
                            "message", message));
                    control.audit(execution.actor(), "soar.waiting_approval",
                            execution.id() + ":" + node.id());
                    return;
                }
                if ("end".equals(node.type())) {
                    store.startNode(execution.id(), nodesExecuted, node, 1, Map.of());
                    String result = node.result() == null ? "succeeded" : node.result();
                    store.finishNode(execution.id(), node.id(), "succeeded", Map.of("result", result), null);
                    nodesExecuted++;
                    frontier.remove(node.id());
                    if (!"succeeded".equals(result)) {
                        store.finishExecution(execution.id(), owner, result,
                                "Playbook 进入 " + node.id(), context, nodesExecuted);
                        event(execution, "execution." + result, node.id(), Map.of());
                        control.audit(execution.actor(), "soar." + result, execution.id());
                        return;
                    }
                    store.saveProgress(execution.id(), owner, List.copyOf(frontier), first(frontier),
                            context, nodesExecuted);
                    continue;
                }
                fail(execution, owner, context, nodesExecuted, "未知节点类型: " + node.type(), node.id());
                return;
            }
        } catch (Exception e) {
            fail(execution, owner, context, nodesExecuted, safeError(e), first(frontier));
        }
    }

    private BatchOutcome runActions(SoarExecution execution, String owner,
                                    List<SoarPlaybook.Node> batch, LinkedHashSet<String> frontier,
                                    Map<String, Object> context, int nodesExecuted) throws Exception {
        List<PreparedAction> prepared = new ArrayList<>();
        for (SoarPlaybook.Node node : batch) {
            if (!SoarExpression.matches(node.when(), context)) {
                store.startNode(execution.id(), nodesExecuted, node, 1, Map.of());
                store.finishNode(execution.id(), node.id(), "skipped", Map.of("reason", "guard_false"), null);
                nodesExecuted++;
                applyNodeResult(context, node, "skipped", Map.of("reason", "guard_false"), null, 1);
                route(frontier, node, "success", context);
                event(execution, "node.skipped", node.id(), Map.of());
                continue;
            }
            Map<String, Object> input = SoarExpression.resolveMap(SoarGraph.parameters(node), context);
            int maxAttempts = maxAttempts(execution.playbookSnapshot(), node);
            int attempt = store.startNode(execution.id(), nodesExecuted, node, maxAttempts, input);
            nodesExecuted++;
            event(execution, "node.started", node.id(), Map.of("attempt", attempt, "action", node.action()));
            Future<Map<String, Object>> future = actionPool.submit(
                    () -> actions.execute(node.action(), input, execution, context));
            prepared.add(new PreparedAction(node, input, attempt, maxAttempts, future,
                    timeoutSeconds(execution.playbookSnapshot(), node)));
        }
        if (!store.heartbeat(execution.id(), owner, Instant.now().plus(leaseDuration))) {
            throw new IllegalStateException("SOAR Worker 已失去租约");
        }
        Instant nextRunAt = null;
        String retryError = null;
        String fatalError = null;
        String fatalNode = null;
        for (PreparedAction item : prepared) {
            SoarPlaybook.Node node = item.node();
            try {
                Map<String, Object> output = awaitWithHeartbeat(item.future(), item.timeoutSeconds(),
                        execution.id(), owner);
                store.finishNode(execution.id(), node.id(), "succeeded", output, null);
                applyNodeResult(context, node, "succeeded", output, null, item.attempt());
                route(frontier, node, "success", context);
                event(execution, "node.succeeded", node.id(), Map.of("attempt", item.attempt()));
            } catch (Exception e) {
                item.future().cancel(true);
                String error = e instanceof TimeoutException
                        ? "节点执行超时（" + item.timeoutSeconds() + "s）" : safeError(e);
                if (item.attempt() < item.maxAttempts()) {
                    store.finishNode(execution.id(), node.id(), "retrying", Map.of(), error);
                    long delay = retryDelay(execution.playbookSnapshot(), node, item.attempt());
                    Instant candidate = Instant.now().plusSeconds(delay);
                    if (nextRunAt == null || candidate.isBefore(nextRunAt)) nextRunAt = candidate;
                    retryError = error;
                    event(execution, "node.retry_scheduled", node.id(), Map.of(
                            "attempt", item.attempt(), "nextAttempt", item.attempt() + 1,
                            "delaySeconds", delay, "error", error));
                } else {
                    store.finishNode(execution.id(), node.id(), "failed", Map.of(), error);
                    applyNodeResult(context, node, "failed", Map.of(), error, item.attempt());
                    List<String> targets = transitionTargets(node, "failure", context);
                    if (targets.isEmpty()) {
                        fatalError = error;
                        fatalNode = node.id();
                    } else {
                        replace(frontier, node.id(), targets);
                        event(execution, "node.failure_routed", node.id(), Map.of(
                                "targets", targets, "error", error));
                    }
                }
            }
        }
        if (fatalError != null) {
            fail(execution, owner, context, nodesExecuted, fatalError, fatalNode);
            return new BatchOutcome(nodesExecuted, null, fatalError, true);
        }
        return new BatchOutcome(nodesExecuted, nextRunAt, retryError, false);
    }

    @SuppressWarnings("unchecked")
    private MapOutcome runMap(SoarExecution execution, String owner, SoarPlaybook.Node node,
                              Map<String, Object> context, int nodesExecuted) throws Exception {
        Map<String, Object> parameters = SoarGraph.parameters(node);
        Object resolvedItems = SoarExpression.resolve(parameters.get("items"), context);
        if (!(resolvedItems instanceof List<?> source)) throw new IllegalArgumentException("map.items 必须解析为数组");
        int maxItems = integer(parameters.getOrDefault("maxItems", 100), 100);
        if (source.size() > maxItems) throw new IllegalArgumentException("map.items 超过 maxItems=" + maxItems);
        int concurrency = Math.min(maxParallel, integer(parameters.getOrDefault("concurrency", 4), 4));
        String action = String.valueOf(parameters.get("action"));
        String itemVariable = String.valueOf(parameters.getOrDefault("itemVariable", "item"));
        boolean continueOnError = Boolean.TRUE.equals(parameters.get("continueOnError"));
        Map<String, Object> argumentTemplate = parameters.get("arguments") instanceof Map<?, ?> map
                ? (Map<String, Object>) map : Map.of();
        Map<String, Object> input = new LinkedHashMap<>();
        input.put("itemCount", source.size());
        input.put("action", action);
        input.put("concurrency", concurrency);
        store.startNode(execution.id(), nodesExecuted, node, 1, input);
        event(execution, "node.map_started", node.id(), input);
        List<Map<String, Object>> results = new ArrayList<>();
        int failures = 0;
        for (int offset = 0; offset < source.size(); offset += concurrency) {
            List<Future<Map<String, Object>>> futures = new ArrayList<>();
            List<Integer> indexes = new ArrayList<>();
            for (int index = offset; index < Math.min(source.size(), offset + concurrency); index++) {
                Object item = source.get(index);
                Map<String, Object> itemContext = new LinkedHashMap<>(context);
                itemContext.put(itemVariable, item);
                itemContext.put("mapIndex", index);
                Map<String, Object> variables = context.get("variables") instanceof Map<?, ?> current
                        ? new LinkedHashMap<>((Map<String, Object>) current) : new LinkedHashMap<>();
                variables.put(itemVariable, item);
                variables.put("mapIndex", index);
                itemContext.put("variables", variables);
                Map<String, Object> arguments = SoarExpression.resolveMap(argumentTemplate, itemContext);
                futures.add(actionPool.submit(() -> actions.execute(action, arguments, execution, itemContext)));
                indexes.add(index);
            }
            for (int position = 0; position < futures.size(); position++) {
                int index = indexes.get(position);
                try {
                    Map<String, Object> output = awaitWithHeartbeat(futures.get(position),
                            timeoutSeconds(execution.playbookSnapshot(), node), execution.id(), owner);
                    results.add(Map.of("index", index, "status", "succeeded", "output", output));
                } catch (Exception e) {
                    futures.get(position).cancel(true);
                    failures++;
                    results.add(Map.of("index", index, "status", "failed", "error", safeError(e)));
                }
            }
        }
        Map<String, Object> output = new LinkedHashMap<>();
        output.put("count", source.size());
        output.put("succeeded", source.size() - failures);
        output.put("failed", failures);
        output.put("results", results);
        boolean success = failures == 0 || continueOnError;
        String error = success ? null : failures + " 个 map item 执行失败";
        store.finishNode(execution.id(), node.id(), success ? "succeeded" : "failed", output, error);
        event(execution, success ? "node.map_succeeded" : "node.map_failed", node.id(), Map.of(
                "count", source.size(), "failed", failures));
        return new MapOutcome(nodesExecuted + 1, success, output, error);
    }

    /** 长动作按租约的三分之一分片等待，避免合法执行被恢复器误判为宕机。 */
    private Map<String, Object> awaitWithHeartbeat(Future<Map<String, Object>> future,
                                                    int timeoutSeconds,
                                                    String executionId, String owner) throws Exception {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(timeoutSeconds);
        long heartbeatSlice = Math.max(1000, leaseDuration.toMillis() / 3);
        while (true) {
            long remaining = deadline - System.nanoTime();
            if (remaining <= 0) throw new TimeoutException();
            long waitMillis = Math.max(1, Math.min(TimeUnit.NANOSECONDS.toMillis(remaining), heartbeatSlice));
            try {
                return future.get(waitMillis, TimeUnit.MILLISECONDS);
            } catch (TimeoutException timeout) {
                if (System.nanoTime() >= deadline) throw timeout;
                if (!store.heartbeat(executionId, owner, Instant.now().plus(leaseDuration))) {
                    throw new IllegalStateException("SOAR Worker 已失去租约");
                }
            }
        }
    }

    public static List<String> transitionTargets(SoarPlaybook.Node node, String event,
                                                  Map<String, Object> context) {
        List<String> targets = new ArrayList<>();
        boolean exclusive = Boolean.TRUE.equals(node.exclusive());
        for (SoarPlaybook.Transition edge : SoarGraph.transitions(node)) {
            String edgeEvent = edge.event() == null ? "success" : edge.event();
            if (!(edgeEvent.equals(event) || "always".equals(edgeEvent))
                    || !SoarExpression.matches(edge.when(), context)) continue;
            targets.add(edge.target());
            if (exclusive) break;
        }
        return targets;
    }

    private static boolean ready(SoarPlaybook.Node node, SoarGraph graph,
                                 Map<String, SoarStepExecution> steps) {
        if (!"all".equals(node.join())) return true;
        Set<String> inbound = graph.inbound(node.id());
        return !inbound.isEmpty() && inbound.stream().allMatch(id -> {
            SoarStepExecution step = steps.get(id);
            return step != null && JOIN_TERMINAL.contains(step.status());
        });
    }

    private void route(LinkedHashSet<String> frontier, SoarPlaybook.Node node,
                       String event, Map<String, Object> context) {
        replace(frontier, node.id(), transitionTargets(node, event, context));
    }

    private static void replace(LinkedHashSet<String> frontier, String nodeId, List<String> targets) {
        frontier.remove(nodeId);
        frontier.addAll(targets);
    }

    @SuppressWarnings("unchecked")
    private static void applyNodeResult(Map<String, Object> context, SoarPlaybook.Node node,
                                        String status, Map<String, Object> output,
                                        String error, int attempt) {
        Map<String, Object> nodes = context.get("nodes") instanceof Map<?, ?> current
                ? new LinkedHashMap<>((Map<String, Object>) current) : new LinkedHashMap<>();
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("status", status);
        result.put("attempt", attempt);
        result.put("output", output == null ? Map.of() : output);
        if (error != null) result.put("error", error);
        nodes.put(node.id(), result);
        context.put("nodes", nodes);
        if ("context.set".equals(node.action()) && output != null
                && output.get("values") instanceof Map<?, ?> values) {
            Map<String, Object> variables = context.get("variables") instanceof Map<?, ?> current
                    ? new LinkedHashMap<>((Map<String, Object>) current) : new LinkedHashMap<>();
            values.forEach((key, value) -> variables.put(String.valueOf(key), value));
            context.put("variables", variables);
        }
    }

    private Map<String, SoarStepExecution> stepMap(String executionId) {
        Map<String, SoarStepExecution> out = new LinkedHashMap<>();
        store.listSteps(executionId).forEach(step -> out.put(step.stepId(), step));
        return out;
    }

    private void release(SoarExecution execution, String owner, Set<String> frontier,
                         Map<String, Object> context, int nodesExecuted,
                         Instant nextRunAt, String error) {
        store.release(execution.id(), owner, List.copyOf(frontier), first(frontier),
                context, nodesExecuted, nextRunAt, error);
    }

    private void fail(SoarExecution execution, String owner, Map<String, Object> context,
                      int nodesExecuted, String error, String nodeId) {
        try {
            store.finishExecution(execution.id(), owner, "failed", error, context, nodesExecuted);
            event(execution, "execution.failed", nodeId, Map.of("error", error));
            control.audit(execution.actor(), "soar.failed",
                    execution.id() + ":" + (nodeId == null ? "engine" : nodeId) + ":" + error);
        } catch (Exception ignored) {
            // 已失去租约时不能覆盖新 Worker 的结果。
        }
    }

    private void event(SoarExecution execution, String type, String nodeId, Map<String, Object> details) {
        store.appendEvent(execution.id(), type, nodeId, "soar-worker", details);
    }

    private static int maxAttempts(SoarPlaybook playbook, SoarPlaybook.Node node) {
        Integer value = node.retry() == null ? null : node.retry().maxAttempts();
        if (value == null && playbook.defaults() != null && playbook.defaults().retry() != null) {
            value = playbook.defaults().retry().maxAttempts();
        }
        return value == null ? 1 : value;
    }

    private static int timeoutSeconds(SoarPlaybook playbook, SoarPlaybook.Node node) {
        Integer value = node.timeoutSeconds();
        if (value == null && playbook.defaults() != null) value = playbook.defaults().timeoutSeconds();
        return value == null ? 30 : value;
    }

    private static long retryDelay(SoarPlaybook playbook, SoarPlaybook.Node node, int attempt) {
        SoarPlaybook.RetryPolicy policy = node.retry();
        if (policy == null && playbook.defaults() != null) policy = playbook.defaults().retry();
        if (policy == null) return 0;
        int base = policy.delaySeconds() == null ? 0 : policy.delaySeconds();
        double multiplier = policy.backoffMultiplier() == null ? 1.0 : policy.backoffMultiplier();
        return Math.min(3600, Math.round(base * Math.pow(multiplier, Math.max(0, attempt - 1))));
    }

    private static Map<String, Object> mutableContext(Map<String, Object> source) {
        return source == null ? new LinkedHashMap<>() : new LinkedHashMap<>(source);
    }

    @SuppressWarnings("unchecked")
    private static int loopIteration(Map<String, Object> context, String nodeId) {
        Object loops = context.get("loops");
        if (loops instanceof Map<?, ?> values && values.get(nodeId) instanceof Number number) {
            return number.intValue();
        }
        return 0;
    }

    @SuppressWarnings("unchecked")
    private static void setLoopIteration(Map<String, Object> context, String nodeId, int iteration) {
        Map<String, Object> loops = context.get("loops") instanceof Map<?, ?> current
                ? new LinkedHashMap<>((Map<String, Object>) current) : new LinkedHashMap<>();
        loops.put(nodeId, iteration);
        context.put("loops", loops);
    }

    @SuppressWarnings("unchecked")
    private static void setVariable(Map<String, Object> context, String name, Object value) {
        Map<String, Object> variables = context.get("variables") instanceof Map<?, ?> current
                ? new LinkedHashMap<>((Map<String, Object>) current) : new LinkedHashMap<>();
        variables.put(name, value);
        context.put("variables", variables);
    }

    private static int integer(Object value, int fallback) {
        if (value instanceof Number number) return number.intValue();
        try {
            return Integer.parseInt(String.valueOf(value));
        } catch (Exception ignored) {
            return fallback;
        }
    }

    private static List<String> loopBodyNodes(SoarGraph graph, String loopId, List<String> targets) {
        LinkedHashSet<String> body = new LinkedHashSet<>();
        ArrayList<String> pending = new ArrayList<>(targets);
        while (!pending.isEmpty() && body.size() < 100) {
            String id = pending.remove(0);
            if (loopId.equals(id) || !body.add(id)) continue;
            SoarPlaybook.Node node = graph.node(id);
            if (node != null) SoarGraph.transitions(node).forEach(edge -> pending.add(edge.target()));
        }
        return List.copyOf(body);
    }

    private static String first(Set<String> values) {
        return values.isEmpty() ? null : values.iterator().next();
    }

    private static String safeError(Exception e) {
        Throwable cause = e.getCause() == null ? e : e.getCause();
        String message = cause.getMessage();
        if (message == null || message.isBlank()) message = cause.getClass().getSimpleName();
        return message.length() > 1000 ? message.substring(0, 1000) : message;
    }

    @PreDestroy
    public void close() {
        actionPool.shutdownNow();
    }

    private record PreparedAction(SoarPlaybook.Node node, Map<String, Object> input,
                                  int attempt, int maxAttempts,
                                  Future<Map<String, Object>> future, int timeoutSeconds) {
    }

    private record BatchOutcome(int nodesExecuted, Instant nextRunAt, String error, boolean terminal) {
    }

    private record MapOutcome(int nodesExecuted, boolean success, Map<String, Object> output,
                              String error) {
    }
}
