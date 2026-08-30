package com.xscsiem.hsiem_platform.detection.runtime.process;

import com.xscsiem.hsiem_platform.detection.runtime.DetectionArtifact;
import com.xscsiem.hsiem_platform.detection.runtime.DetectionArtifactBuilder;
import com.xscsiem.hsiem_platform.detection.runtime.DetectionJobNameCodec;
import com.xscsiem.hsiem_platform.detection.runtime.DetectionRuntimeTarget;
import com.xscsiem.hsiem_platform.detection.runtime.FlinkRuntimePort;
import com.xscsiem.hsiem_platform.detection.runtime.RuntimeObservation;
import com.xscsiem.hsiem_platform.rules.runtime.RuntimeManifest;
import com.xscsiem.hsiem_platform.rules.runtime.RuntimeManifestCodec;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Flink CLI adapter for one explicitly configured cluster.  Every external command is passed as a
 * vector to {@link CommandRunner}; no shell, shell expansion, or user-controlled command fragment
 * is used here.
 */
public final class ProcessFlinkRuntimeAdapter implements FlinkRuntimePort {

    private static final Pattern STRUCTURED_NAME = Pattern.compile(
            "(?<!\\S)(SIEM-DETECTION-dg-[0-9a-f]{24}-g[0-9]+-m[0-9a-f]{64})(?!\\S)");
    private static final Pattern JOB_ID = Pattern.compile("\\b[0-9a-fA-F]{32}\\b");
    private static final Pattern STATE = Pattern.compile("\\(([^()]*)\\)");
    private static final Pattern SAVEPOINT = Pattern.compile(
            "(?:(?:Path|path|stored in|at)\\s*[:=]?\\s*)?((?:file|hdfs|s3|oss)://[^\\s,;]+|/[^\\s,;]+)");
    private static final Set<String> TERMINAL_STATES = Set.of(
            "CANCELED", "CANCELLED", "FINISHED", "COMPLETED", "FAILED");

    private final CommandRunner commands;
    private final DetectionArtifactBuilder artifacts;
    private final DetectionJobNameCodec names;
    private final RuntimeManifestCodec codec;
    private final String clusterId;
    private final String containerName;
    private final String jarPath;
    private final String savepointRoot;
    private final Duration commandTimeout;
    private final Duration verifyPoll;
    private final Set<String> allowedClusters;

    public ProcessFlinkRuntimeAdapter(CommandRunner commands,
                                      DetectionArtifactBuilder artifacts,
                                      DetectionJobNameCodec names,
                                      RuntimeManifestCodec codec,
                                      String clusterId,
                                      String containerName,
                                      String jarPath,
                                      String savepointRoot,
                                      Duration commandTimeout,
                                      Duration verifyPoll,
                                      Set<String> allowedClusters) {
        this.commands = Objects.requireNonNull(commands, "commands must not be null");
        this.artifacts = Objects.requireNonNull(artifacts, "artifacts must not be null");
        this.names = Objects.requireNonNull(names, "names must not be null");
        this.codec = Objects.requireNonNull(codec, "codec must not be null");
        this.clusterId = required(clusterId, "clusterId");
        this.containerName = required(containerName, "containerName");
        this.jarPath = required(jarPath, "jarPath");
        this.savepointRoot = required(savepointRoot, "savepointRoot");
        this.commandTimeout = positive(commandTimeout, "commandTimeout");
        this.verifyPoll = nonNegative(verifyPoll, "verifyPoll");
        Set<String> configured = allowedClusters == null ? Set.of() : new HashSet<>(allowedClusters);
        configured.removeIf(value -> value == null || value.isBlank());
        this.allowedClusters = Set.copyOf(configured.isEmpty() ? Set.of(this.clusterId) : configured);
        if (!this.allowedClusters.contains(this.clusterId)) {
            throw new IllegalArgumentException("configured clusterId is not in allowed-clusters");
        }
    }

    public ProcessFlinkRuntimeAdapter(CommandRunner commands, DetectionArtifactBuilder artifacts,
                                      DetectionJobNameCodec names, String clusterId,
                                      String containerName, String jarPath, String savepointRoot,
                                      Duration commandTimeout, Duration verifyPoll,
                                      Set<String> allowedClusters) {
        this(commands, artifacts, names, new RuntimeManifestCodec(), clusterId, containerName,
                jarPath, savepointRoot, commandTimeout, verifyPoll, allowedClusters);
    }

    @Override
    public RuntimeObservation inspect(DetectionRuntimeTarget target) {
        validateTarget(target);
        try {
            return inspectInternal(target).observation();
        } catch (RuntimeException failure) {
            return unknown(target, "RUNTIME_INSPECT_FAILED", safeMessage(failure));
        }
    }

    @Override
    public RuntimeObservation apply(DetectionRuntimeTarget target, RuntimeObservation current) {
        RuntimeManifest expected = validateTarget(target);
        if (expected.members().isEmpty()) {
            throw new IllegalArgumentException("cannot apply an empty expected manifest");
        }
        validateTarget(target);
        Inspection before;
        if (isExactRunning(target, current)) {
            return current;
        }
        before = inspectInternal(target);
        if (before.activeJobs().size() > 1) {
            throw new IllegalStateException("cannot apply while duplicate active jobs exist for target job key");
        }
        if (isExactRunning(target, before.observation())) {
            return before.observation();
        }

        DetectionArtifact next = artifacts.build(target);
        JobEntry old = before.activeJobs().stream().findFirst().orElse(null);
        OldRuntime oldRuntime = old == null ? null : oldRuntime(old);
        String savepoint = null;
        if (old != null) {
            savepoint = cancel(old);
        }
        try {
            // Keep copy, submit, and verification in the post-cancel scope.  Any replacement
            // failure must therefore take the same rollback path as a failed flink run.
            copyArtifact(next);
            submit(next, target, savepoint);
            return pollExact(target, commandTimeout);
        } catch (RuntimeException failure) {
            // A failed replacement must not leave a previously healthy group down.  Rollback is
            // limited to the same stable job key and uses the exact savepoint returned by cancel.
            if (oldRuntime != null && savepoint != null) {
                try {
                    restoreOld(target, oldRuntime, savepoint);
                } catch (RuntimeException rollback) {
                    rollback.addSuppressed(failure);
                    throw rollback;
                }
                throw new IllegalStateException("replacement failed; previous runtime restored", failure);
            }
            throw failure;
        }
    }

    @Override
    public RuntimeObservation stop(DetectionRuntimeTarget target, RuntimeObservation current) {
        validateTarget(target);
        Inspection before = inspectInternal(target);
        if (before.activeJobs().isEmpty()) {
            return RuntimeObservation.stopped(target, null,
                    names.jobKey(target.tenantId(), target.targetCluster(), target.groupKey()));
        }
        // Do not use the expected manifest or a global flink cancel.  Every cancellation is tied
        // to a parsed job entry carrying this target's stable job key.
        for (JobEntry job : before.activeJobs()) {
            cancel(job);
        }
        Instant deadline = Instant.now().plus(commandTimeout);
        while (true) {
            Inspection now = inspectInternal(target);
            if (now.activeJobs().isEmpty()) {
                return RuntimeObservation.stopped(target, null,
                        names.jobKey(target.tenantId(), target.targetCluster(), target.groupKey()));
            }
            if (!Instant.now().isBefore(deadline)) {
                throw new IllegalStateException("timed out waiting for detection job to stop");
            }
            pause();
        }
    }

    private RuntimeManifest validateTarget(DetectionRuntimeTarget target) {
        if (target == null) throw new IllegalArgumentException("target must not be null");
        if (!clusterId.equals(target.targetCluster()) || !allowedClusters.contains(target.targetCluster())) {
            throw new IllegalArgumentException("cluster is not allowed: " + target.targetCluster());
        }
        RuntimeManifest expected = codec.decode(target.expectedManifestJson());
        codec.validateSchemaVersion(expected);
        if (!target.tenantId().equals(expected.tenantId())
                || !target.groupKey().equals(expected.jobGroupKey())
                || !target.targetCluster().equals(expected.targetCluster())
                || target.desiredGeneration() != expected.generation()) {
            throw new IllegalArgumentException("expected manifest scope or generation does not match target");
        }
        if (!codec.specHash(expected).equalsIgnoreCase(target.expectedManifestHash())) {
            throw new IllegalArgumentException("expected manifest hash does not match target");
        }
        return expected;
    }

    private Inspection inspectInternal(DetectionRuntimeTarget target) {
        String expectedJobKey = names.jobKey(target.tenantId(), target.targetCluster(), target.groupKey());
        List<JobEntry> matches = listJobs().stream()
                .filter(job -> expectedJobKey.equals(job.identity().jobKey()))
                .toList();
        List<JobEntry> active = matches.stream().filter(JobEntry::active).toList();
        if (active.size() > 1) {
            return new Inspection(new RuntimeObservation("FAILED", target,
                    active.getFirst().jobId(), expectedJobKey,
                    active.getFirst().identity().generation(), List.of(), "DUPLICATE_JOBS",
                    "more than one active Flink job has jobKey " + expectedJobKey), active);
        }
        if (active.isEmpty()) {
            // FAILED is terminal: expose that terminal observation, but do not include it in
            // activeJobs so a subsequent apply can submit a replacement without cancelling it.
            JobEntry failed = matches.stream()
                    .filter(job -> "FAILED".equals(job.state()))
                    .findFirst().orElse(null);
            if (failed != null) {
                return observeJob(target, failed, "FAILED", List.of());
            }
            return new Inspection(RuntimeObservation.stopped(target, null, expectedJobKey), List.of());
        }
        JobEntry job = active.getFirst();
        return observeJob(target, job, state(job.state()), active);
    }

    private Inspection observeJob(DetectionRuntimeTarget target, JobEntry job,
                                  String mappedState, List<JobEntry> active) {
        try {
            RuntimeManifest observed = readObservedManifest(target, job);
            List<RuntimeObservation.Member> members = observed.members().stream()
                    .map(member -> new RuntimeObservation.Member(member.ruleKey(), member.revision(), member.planHash()))
                    .toList();
            return new Inspection(new RuntimeObservation(mappedState, target, job.jobId(),
                    job.identity().jobKey(), job.identity().generation(), members, null, null), active);
        } catch (RuntimeException failure) {
            if ("FAILED".equals(mappedState)) {
                return new Inspection(new RuntimeObservation("FAILED", target, job.jobId(),
                        job.identity().jobKey(), job.identity().generation(), List.of(),
                        "ARTIFACT_INVALID", safeMessage(failure)), active);
            }
            return new Inspection(unknown(target, "ARTIFACT_INVALID", safeMessage(failure)), active);
        }
    }

    private RuntimeManifest readObservedManifest(DetectionRuntimeTarget target, JobEntry job) {
        Path path = artifacts.artifactPath(job.identity().jobKey(), job.identity().generation(),
                job.identity().manifestHash());
        Path manifest = path.resolve("runtime-manifest.json");
        byte[] raw;
        try {
            raw = Files.readAllBytes(manifest);
        } catch (IOException e) {
            throw new IllegalStateException("artifact manifest is missing: " + manifest, e);
        }
        String rawHash = sha256(raw);
        if (!rawHash.equalsIgnoreCase(job.identity().manifestHash())) {
            throw new IllegalStateException("artifact manifest hash does not match structured job name");
        }
        RuntimeManifest observed = codec.decode(decodeUtf8(raw));
        if (!target.tenantId().equals(observed.tenantId())
                || !target.groupKey().equals(observed.jobGroupKey())
                || !target.targetCluster().equals(observed.targetCluster())
                || observed.generation() != job.identity().generation()
                || !codec.specHash(observed).equalsIgnoreCase(job.identity().manifestHash())) {
            throw new IllegalStateException("artifact manifest scope or hash is invalid");
        }
        if (observed.jobKey() != null && !job.identity().jobKey().equals(observed.jobKey())) {
            throw new IllegalStateException("artifact manifest jobKey is invalid");
        }
        return observed;
    }

    private List<JobEntry> listJobs() {
        CommandRunner.CommandResult result = run(List.of(
                "docker", "exec", containerName, "flink", "list", "-a"));
        if (!result.successful()) {
            throw new IllegalStateException("flink list failed: " + output(result));
        }
        return parseJobs(result.stdout());
    }

    /** Parse only structured names; user-provided group/tenant strings never enter this parser. */
    public List<JobEntry> parseJobs(String output) {
        if (output == null || output.isBlank()) return List.of();
        List<JobEntry> jobs = new ArrayList<>();
        for (String line : output.split("\\R")) {
            Matcher nameMatcher = STRUCTURED_NAME.matcher(line);
            if (!nameMatcher.find()) {
                if (line.contains("SIEM-DETECTION-")) {
                    throw new IllegalStateException("unparseable structured detection job name: " + line);
                }
                continue;
            }
            String name = nameMatcher.group(1);
            DetectionJobNameCodec.JobIdentity identity;
            try {
                identity = names.decode(name);
            } catch (IllegalArgumentException ignored) {
                continue;
            }
            Matcher idMatcher = JOB_ID.matcher(line);
            String jobId = idMatcher.find() ? idMatcher.group() : null;
            Matcher stateMatcher = STATE.matcher(line);
            String jobState = stateMatcher.find() ? stateMatcher.group(1).trim().toUpperCase(Locale.ROOT)
                    : "UNKNOWN";
            jobs.add(new JobEntry(jobId, name, jobState, identity));
        }
        return List.copyOf(jobs);
    }

    private String cancel(JobEntry job) {
        if (job.jobId() == null || job.jobId().isBlank()) {
            throw new IllegalStateException("cannot cancel a job without a Flink job ID");
        }
        CommandRunner.CommandResult result = run(List.of("docker", "exec", containerName, "flink",
                "cancel", "-s", savepointPath(job.identity().jobKey()), job.jobId()));
        if (!result.successful()) throw new IllegalStateException("flink cancel failed: " + output(result));
        return parseSavepoint(output(result));
    }

    private void copyArtifact(DetectionArtifact artifact) {
        requireSuccess(run(List.of("docker", "exec", containerName, "mkdir", "-p",
                artifact.containerPath())));
        // The trailing /. is an argument, not shell syntax; it copies the directory contents into
        // the already-created immutable path rather than creating a nested directory.
        requireSuccess(run(List.of("docker", "cp", artifact.localPath().toString() + "/.",
                containerName + ":" + artifact.containerPath())));
    }

    private String submit(DetectionArtifact artifact, DetectionRuntimeTarget target, String savepoint) {
        RuntimeManifest expected = codec.decode(target.expectedManifestJson());
        List<String> command = new ArrayList<>(List.of("docker", "exec", containerName, "flink",
                "run", "-d"));
        if (savepoint != null) command.addAll(List.of("-s", savepoint));
        command.add(jarPath);
        command.add(artifact.containerPath());
        command.add(artifact.jobKey());
        command.add(Long.toString(expected.generation()));
        command.add(artifact.manifestHash());
        CommandRunner.CommandResult result = run(command);
        if (!result.successful()) throw new IllegalStateException("flink run failed: " + output(result));
        Matcher matcher = JOB_ID.matcher(output(result));
        if (!matcher.find()) throw new IllegalStateException("flink run returned no JobID: " + output(result));
        return matcher.group();
    }

    private RuntimeObservation pollExact(DetectionRuntimeTarget target, Duration timeout) {
        Instant deadline = Instant.now().plus(timeout);
        RuntimeObservation last = RuntimeObservation.unknown(target);
        while (true) {
            Inspection inspection = inspectInternal(target);
            last = inspection.observation();
            if (isExactRunning(target, last)) return last;
            if ("FAILED".equalsIgnoreCase(last.runtimeState())) {
                throw new IllegalStateException("replacement Flink job failed: " + safeMessage(last));
            }
            if (!Instant.now().isBefore(deadline)) {
                throw new IllegalStateException("timed out waiting for exact RUNNING Flink job: " + safeMessage(last));
            }
            pause();
        }
    }

    private void restoreOld(DetectionRuntimeTarget target, OldRuntime old, String savepoint) {
        RuntimeManifest verified = artifacts.verifyArtifact(old.artifact());
        if (!old.manifest().equals(verified)) {
            throw new IllegalStateException("old artifact changed during rollback");
        }
        copyArtifact(old.artifact());
        submitOld(old.artifact(), savepoint);
        Instant deadline = Instant.now().plus(commandTimeout);
        while (true) {
            Inspection inspection = inspectInternal(target);
            RuntimeObservation observation = inspection.observation();
            if (isExactOld(old, observation)) return;
            if (!Instant.now().isBefore(deadline)) {
                throw new IllegalStateException("timed out waiting for rollback Flink job");
            }
            pause();
        }
    }

    private boolean isExactOld(OldRuntime old, RuntimeObservation observation) {
        if (observation == null || !"RUNNING".equalsIgnoreCase(observation.runtimeState())
                || !old.jobKey().equals(observation.jobKey())
                || old.generation() != observation.generation()) {
            return false;
        }
        List<RuntimeManifest.Member> members = observation.members().stream()
                .map(member -> new RuntimeManifest.Member(member.ruleKey(), member.revision(), member.planHash()))
                .toList();
        if (!old.manifest().members().equals(members)) return false;
        RuntimeManifest observed = new RuntimeManifest(old.manifest().schemaVersion(),
                old.manifest().tenantId(), old.manifest().targetCluster(), old.manifest().jobGroupKey(),
                observation.generation(), observation.jobId(), observation.jobKey(), members);
        return old.manifestHash().equalsIgnoreCase(codec.specHash(observed));
    }

    private void submitOld(DetectionArtifact artifact, String savepoint) {
        List<String> command = new ArrayList<>(List.of("docker", "exec", containerName, "flink",
                "run", "-d", "-s", savepoint, jarPath, artifact.containerPath(), artifact.jobKey(),
                Long.toString(artifact.generation()), artifact.manifestHash()));
        CommandRunner.CommandResult result = run(command);
        if (!result.successful()) throw new IllegalStateException("rollback flink run failed: " + output(result));
        if (!JOB_ID.matcher(output(result)).find()) {
            throw new IllegalStateException("rollback flink run returned no JobID: " + output(result));
        }
    }

    private OldRuntime oldRuntime(JobEntry old) {
        Path local = artifacts.artifactPath(old.identity().jobKey(), old.identity().generation(),
                old.identity().manifestHash());
        DetectionArtifact artifact = new DetectionArtifact(local,
                artifacts.containerArtifactPath(old.identity().jobKey(), old.identity().generation(),
                        old.identity().manifestHash()), old.identity().jobKey(),
                old.identity().generation(), old.identity().manifestHash());
        try {
            RuntimeManifest manifest = artifacts.verifyArtifact(artifact);
            return new OldRuntime(artifact, manifest);
        } catch (RuntimeException failure) {
            throw new IllegalStateException("old artifact is unavailable or invalid for replacement: "
                    + local, failure);
        }
    }

    private boolean isExactRunning(DetectionRuntimeTarget target, RuntimeObservation observation) {
        if (observation == null || !"RUNNING".equalsIgnoreCase(observation.runtimeState())
                || !target.tenantId().equals(observation.tenantId())
                || !target.groupKey().equals(observation.groupKey())
                || !target.targetCluster().equals(observation.targetCluster())
                || target.desiredGeneration() != observation.generation()) return false;
        String expectedJobKey = names.jobKey(target.tenantId(), target.targetCluster(), target.groupKey());
        if (!expectedJobKey.equals(observation.jobKey())) return false;
        List<RuntimeManifest.Member> members = observation.members().stream()
                .map(member -> new RuntimeManifest.Member(member.ruleKey(), member.revision(), member.planHash()))
                .toList();
        RuntimeManifest actual = new RuntimeManifest(RuntimeManifest.SCHEMA_VERSION,
                observation.tenantId(), observation.targetCluster(), observation.groupKey(),
                observation.generation(), observation.jobId(), observation.jobKey(), members);
        return codec.specHash(actual).equalsIgnoreCase(target.expectedManifestHash());
    }

    private String savepointPath(String jobKey) {
        return savepointRoot.replaceAll("/+$", "") + "/" + jobKey;
    }

    private String parseSavepoint(String output) {
        if (output == null || output.isBlank()) {
            throw new IllegalStateException("flink cancel returned no savepoint path");
        }
        String selected = null;
        int selectedPriority = 0;
        for (String line : output.split("\\R")) {
            String lower = line.toLowerCase(Locale.ROOT);
            int priority = lower.contains("savepoint stored in") ? 3
                    : lower.contains("savepoint completed") || lower.contains("path:") ? 2 : 1;
            Matcher matcher = SAVEPOINT.matcher(line);
            while (matcher.find()) {
                String path = matcher.group(1).replaceAll("[\\])},.;\\\"]+$", "");
                if (path.isBlank()) continue;
                // Prefer the final Flink-reported path over a requested directory/root line.  Within
                // one line the last candidate is the completed child path in Flink's CLI output.
                if (priority > selectedPriority || priority == selectedPriority) {
                    selected = path;
                    selectedPriority = priority;
                }
            }
        }
        if (selected != null) return selected;
        throw new IllegalStateException("flink cancel returned no savepoint path: " + output);
    }

    private CommandRunner.CommandResult run(List<String> command) {
        return commands.run(command, commandTimeout);
    }

    private static void requireSuccess(CommandRunner.CommandResult result) {
        if (result == null || !result.successful()) {
            throw new IllegalStateException("external command failed: " + (result == null ? "null" : output(result)));
        }
    }

    private void pause() {
        if (verifyPoll.isZero()) return;
        try {
            Thread.sleep(verifyPoll.toMillis());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("runtime verification interrupted", e);
        }
    }

    private static RuntimeObservation unknown(DetectionRuntimeTarget target, String code, String message) {
        return new RuntimeObservation("UNKNOWN", target, null, null, 0L, List.of(), code, message);
    }

    private static String state(String value) {
        return switch (value) {
            case "RUNNING" -> "RUNNING";
            case "DEPLOYING", "CREATED", "RESTARTING", "FAILING", "CANCELING", "CANCELLING" -> "DEPLOYING";
            case "FAILED" -> "FAILED";
            default -> "UNKNOWN";
        };
    }

    private static String output(CommandRunner.CommandResult result) {
        String stdout = result.stdout() == null ? "" : result.stdout();
        String stderr = result.stderr() == null ? "" : result.stderr();
        return stdout + (stderr.isBlank() ? "" : "\n" + stderr);
    }

    private static String safeMessage(Throwable failure) {
        String message = failure == null ? null : failure.getMessage();
        return message == null || message.isBlank() ? failure.getClass().getSimpleName() : message;
    }

    private static String safeMessage(RuntimeObservation observation) {
        if (observation == null) return "no observation";
        return observation.errorMessage() == null || observation.errorMessage().isBlank()
                ? observation.runtimeState() : observation.errorMessage();
    }

    private static String decodeUtf8(byte[] raw) {
        try {
            return StandardCharsets.UTF_8.newDecoder()
                    .onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT)
                    .decode(ByteBuffer.wrap(raw))
                    .toString();
        } catch (CharacterCodingException e) {
            throw new IllegalStateException("artifact manifest is not valid UTF-8", e);
        }
    }

    private static String sha256(byte[] raw) {
        try {
            return java.util.HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256")
                    .digest(raw));
        } catch (java.security.NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 is unavailable", e);
        }
    }

    private static String required(String value, String field) {
        if (value == null || value.isBlank()) throw new IllegalArgumentException(field + " must not be blank");
        return value;
    }

    private static Duration positive(Duration value, String field) {
        Objects.requireNonNull(value, field + " must not be null");
        if (value.isZero() || value.isNegative()) throw new IllegalArgumentException(field + " must be positive");
        return value;
    }

    private static Duration nonNegative(Duration value, String field) {
        Objects.requireNonNull(value, field + " must not be null");
        if (value.isNegative()) throw new IllegalArgumentException(field + " must not be negative");
        return value;
    }

    public record JobEntry(String jobId, String name, String state,
                           DetectionJobNameCodec.JobIdentity identity) {
        public JobEntry {
            Objects.requireNonNull(identity, "identity must not be null");
            state = state == null || state.isBlank() ? "UNKNOWN" : state.toUpperCase(Locale.ROOT);
        }

        public boolean active() {
            return !TERMINAL_STATES.contains(state);
        }
    }

    private record Inspection(RuntimeObservation observation, List<JobEntry> activeJobs) { }

    private record OldRuntime(DetectionArtifact artifact, RuntimeManifest manifest) {
        String jobKey() { return artifact.jobKey(); }
        long generation() { return artifact.generation(); }
        String manifestHash() { return artifact.manifestHash(); }
    }}
