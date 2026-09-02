package com.xscsiem.hsiem_platform.detection.runtime.process;

import com.xscsiem.hsiem_platform.detection.runtime.DetectionArtifactBuilder;
import com.xscsiem.hsiem_platform.detection.runtime.DetectionJobNameCodec;
import com.xscsiem.hsiem_platform.detection.runtime.DetectionRuntimeTarget;
import com.xscsiem.hsiem_platform.detection.runtime.RuntimeObservation;
import com.xscsiem.hsiem_platform.rules.DetectionPlanCompiler;
import com.xscsiem.hsiem_platform.rules.runtime.RuntimeManifest;
import com.xscsiem.hsiem_platform.rules.runtime.RuntimeManifestCodec;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.jdbc.core.JdbcTemplate;
import com.zaxxer.hikari.HikariDataSource;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ProcessFlinkRuntimeAdapterTest {

    private static final String PLAN_JSON = "{\"alert\":{\"description\":\"canonical fixture\",\"name\":\"rule-a detection\",\"risk_score\":75,\"severity\":\"high\",\"status\":\"stable\",\"tags\":[\"runtime\",\"contract\"],\"type\":\"test_detection\",\"version\":\"1.0\"},\"compiler_version\":\"hisiem-detection-plan-2\",\"detection\":{\"condition\":{\"field\":\"event.action\",\"operator\":\"eq\",\"value\":\"login\"},\"suppression\":{\"duration_minutes\":60,\"emission\":\"first_and_final_count\",\"fallback_entity\":\"unknown\",\"fallback_entity_fields\":[\"source.ip\",\"user.name\"],\"primary_entity_field\":null,\"time_basis\":\"processing_time\"},\"type\":\"single_event\"},\"input\":{\"source\":\"siem-events\"},\"rule_key\":\"rule-a\",\"schema_version\":\"2\"}";
    private static final String PLAN_HASH =
            "f9796d6e28d8cff1f3bdc06a00118a255e056c0eadb7c57436ba5df4aa2b0021";
    private static final String NEW_PLAN_JSON =
            PLAN_JSON.replace("\"value\":\"login\"", "\"value\":\"logout\"");
    private static final String NEW_PLAN_HASH = sha256(NEW_PLAN_JSON);

    @TempDir
    Path temp;

    private final List<HikariDataSource> dataSources = new ArrayList<>();

    @AfterEach
    void tearDown() {
        dataSources.forEach(HikariDataSource::close);
        dataSources.clear();
    }

    @Test
    void parsesStructuredJobAndReadsRealArtifactForObservation() throws Exception {
        RuntimeManifestCodec codec = new RuntimeManifestCodec();
        RuntimeManifest manifest = manifest("group-a", 3L);
        DetectionRuntimeTarget target = target(manifest, codec);
        DetectionArtifactBuilder artifacts = new DetectionArtifactBuilder(emptyJdbc(), codec, temp);
        String jobKey = new DetectionJobNameCodec().jobKey("tenant-a", "default", "group-a");
        Path local = artifacts.artifactPath(jobKey, 3L, codec.specHash(manifest));
        Files.createDirectories(local);
        Files.writeString(local.resolve("runtime-manifest.json"), codec.canonicalSpecJson(manifest),
                StandardCharsets.UTF_8);
        String id = "0123456789abcdef0123456789abcdef";
        String name = DetectionJobNameCodec.jobName(jobKey, 3L, codec.specHash(manifest));
        FakeRunner runner = new FakeRunner(List.of(
                new CommandRunner.CommandResult(0, id + " : " + name + " (RUNNING)\n", "")));
        ProcessFlinkRuntimeAdapter adapter = adapter(runner, artifacts, codec);

        RuntimeObservation observed = adapter.inspect(target);

        assertEquals("RUNNING", observed.runtimeState());
        assertEquals(jobKey, observed.jobKey());
        assertEquals(List.of("rule-a"), observed.members().stream()
                .map(RuntimeObservation.Member::ruleKey).toList());
        assertEquals(List.of("docker", "exec", "siem-flink-jobmanager", "flink", "list", "-a"),
                runner.commands.getFirst());
    }

    @Test
    void missingArtifactIsUnknownAndNeverUsesExpectedMembers() {
        RuntimeManifestCodec codec = new RuntimeManifestCodec();
        RuntimeManifest manifest = manifest("group-missing", 3L);
        DetectionRuntimeTarget target = target(manifest, codec);
        DetectionArtifactBuilder artifacts = new DetectionArtifactBuilder(emptyJdbc(), codec, temp);
        String jobKey = new DetectionJobNameCodec().jobKey("tenant-a", "default", "group-missing");
        String name = DetectionJobNameCodec.jobName(jobKey, 3L, codec.specHash(manifest));
        FakeRunner runner = new FakeRunner(List.of(new CommandRunner.CommandResult(0,
                "0123456789abcdef0123456789abcdef : " + name + " (RUNNING)\n", "")));

        RuntimeObservation observed = adapter(runner, artifacts, codec).inspect(target);

        assertEquals("UNKNOWN", observed.runtimeState());
        assertTrue(observed.members().isEmpty());
        assertEquals("ARTIFACT_INVALID", observed.errorCode());
    }

    @Test
    void rejectsUnconfiguredClusterBeforeRunningDocker() {
        RuntimeManifestCodec codec = new RuntimeManifestCodec();
        RuntimeManifest manifest = new RuntimeManifest(RuntimeManifest.SCHEMA_VERSION, "tenant-a", "other",
                "group-a", 3L, List.of(new RuntimeManifest.Member("rule-a", 1L, PLAN_HASH)));
        DetectionRuntimeTarget target = new DetectionRuntimeTarget("tenant-a", "group-a", "other", 3L,
                codec.canonicalSpecJson(manifest), codec.specHash(manifest));
        FakeRunner runner = new FakeRunner(List.of());
        ProcessFlinkRuntimeAdapter adapter = adapter(runner,
                new DetectionArtifactBuilder(emptyJdbc(), codec, temp), codec);

        assertThrows(IllegalArgumentException.class, () -> adapter.inspect(target));
        assertTrue(runner.commands.isEmpty());
    }

    @Test
    void stopCancelsOnlyTargetJobAndThenObservesItsDisappearance() {
        RuntimeManifestCodec codec = new RuntimeManifestCodec();
        RuntimeManifest manifest = manifest("group-stop", 3L);
        DetectionRuntimeTarget target = target(manifest, codec);
        DetectionArtifactBuilder artifacts = new DetectionArtifactBuilder(emptyJdbc(), codec, temp);
        String key = new DetectionJobNameCodec().jobKey("tenant-a", "default", "group-stop");
        String name = DetectionJobNameCodec.jobName(key, 3L, codec.specHash(manifest));
        String line = "0123456789abcdef0123456789abcdef : " + name + " (RUNNING)\n";
        FakeRunner runner = new FakeRunner(List.of(
                new CommandRunner.CommandResult(0, line, ""),
                new CommandRunner.CommandResult(0, "Savepoint completed. Path: file:///sp/group-stop/savepoint-1\n", ""),
                new CommandRunner.CommandResult(0, "", "")));

        RuntimeObservation stopped = adapter(runner, artifacts, codec).stop(target, null);

        assertEquals("STOPPED", stopped.runtimeState());
        assertEquals(3, runner.commands.size());
        assertEquals(List.of("docker", "exec", "siem-flink-jobmanager", "flink", "cancel", "-s",
                "file:///opt/flink/savepoints/" + key,
                "0123456789abcdef0123456789abcdef"), runner.commands.get(1));
    }

    @Test
    void firstApplyBuildsCopiesSubmitsAndVerifiesExactRunningJob() {
        RuntimeManifestCodec codec = new RuntimeManifestCodec();
        RuntimeManifest manifest = manifest("group-apply", 4L);
        DetectionRuntimeTarget target = target(manifest, codec);
        DetectionArtifactBuilder artifacts = new DetectionArtifactBuilder(populatedJdbc(manifest), codec, temp);
        String key = new DetectionJobNameCodec().jobKey("tenant-a", "default", "group-apply");
        String hash = codec.specHash(manifest);
        String id = "11111111111111111111111111111111";
        String name = DetectionJobNameCodec.jobName(key, 4L, hash);
        FakeRunner runner = new FakeRunner(List.of(
                new CommandRunner.CommandResult(0, "", ""),
                new CommandRunner.CommandResult(0, "", ""),
                new CommandRunner.CommandResult(0, "", ""),
                new CommandRunner.CommandResult(0, "submitted " + id + "\n", ""),
                new CommandRunner.CommandResult(0, id + " : " + name + " (RUNNING)\n", "")));

        RuntimeObservation observed = adapter(runner, artifacts, codec).apply(target, null);

        assertEquals("RUNNING", observed.runtimeState());
        assertEquals(5, runner.commands.size());
        assertEquals(List.of("docker", "exec", "siem-flink-jobmanager", "flink", "run", "-d",
                "/opt/flink/detection-job-1.0.jar", artifacts.containerArtifactPath(key, 4L, hash),
                key, "4", hash), runner.commands.get(3));
    }

    @Test
    void exactRunningCurrentIsIdempotentWithoutInspectOrCommands() {
        RuntimeManifestCodec codec = new RuntimeManifestCodec();
        RuntimeManifest manifest = manifest("group-idempotent", 5L);
        DetectionRuntimeTarget target = target(manifest, codec);
        DetectionArtifactBuilder artifacts = new DetectionArtifactBuilder(populatedJdbc(manifest), codec, temp);
        String id = "22222222222222222222222222222222";
        RuntimeObservation current = runningObservation(target, manifest, id);
        FakeRunner runner = new FakeRunner(List.of());

        RuntimeObservation result = adapter(runner, artifacts, codec).apply(target, current);

        assertEquals(current, result);
        assertTrue(runner.commands.isEmpty());
    }

    @Test
    void updateCancelsOldJobUsesReturnedSavepointAndSubmitsNewArtifact() {
        RuntimeManifestCodec codec = new RuntimeManifestCodec();
        RuntimeManifest oldManifest = manifest("group-update", 2L);
        RuntimeManifest newManifest = new RuntimeManifest(RuntimeManifest.SCHEMA_VERSION, "tenant-a", "default",
                "group-update", 3L, List.of(new RuntimeManifest.Member("rule-a", 2L, NEW_PLAN_HASH)));
        DetectionRuntimeTarget target = target(newManifest, codec);
        JdbcTemplate jdbc = populatedJdbc(oldManifest);
        DetectionArtifactBuilder artifacts = new DetectionArtifactBuilder(jdbc, codec, temp);
        String key = new DetectionJobNameCodec().jobKey("tenant-a", "default", "group-update");
        String oldHash = codec.specHash(oldManifest);
        String newHash = codec.specHash(newManifest);
        artifacts.build(new DetectionRuntimeTarget(oldManifest.tenantId(), oldManifest.jobGroupKey(),
                oldManifest.targetCluster(), oldManifest.generation(), codec.canonicalSpecJson(oldManifest),
                oldHash));
        String oldId = "33333333333333333333333333333333";
        String newId = "44444444444444444444444444444444";
        replaceRows(jdbc, newManifest);
        String oldName = DetectionJobNameCodec.jobName(key, 2L, oldHash);
        String newName = DetectionJobNameCodec.jobName(key, 3L, newHash);
        FakeRunner runner = new FakeRunner(List.of(
                new CommandRunner.CommandResult(0, oldId + " : " + oldName + " (RUNNING)\n", ""),
                new CommandRunner.CommandResult(0,
                        "Requested savepoint directory: s3://bucket/requested\n"
                                + "Savepoint completed. Path: s3://bucket/actual-sp\n", ""),
                new CommandRunner.CommandResult(0, "", ""),
                new CommandRunner.CommandResult(0, "", ""),
                new CommandRunner.CommandResult(0, "submitted " + newId + "\n", ""),
                new CommandRunner.CommandResult(0, newId + " : " + newName + " (RUNNING)\n", "")));

        RuntimeObservation observed = adapter(runner, artifacts, codec).apply(target, null);

        assertEquals("RUNNING", observed.runtimeState());
        assertEquals(6, runner.commands.size());
        assertEquals(List.of("docker", "exec", "siem-flink-jobmanager", "flink", "cancel", "-s",
                "file:///opt/flink/savepoints/" + key, oldId), runner.commands.get(1));
        assertEquals("s3://bucket/actual-sp", runner.commands.get(4).get(7));
        assertEquals(newHash, runner.commands.get(4).getLast());
    }

    @Test
    void missingOldArtifactRejectsReplacementBeforeCancellation() {
        RuntimeManifestCodec codec = new RuntimeManifestCodec();
        RuntimeManifest oldManifest = manifest("group-missing-old", 2L);
        RuntimeManifest newManifest = new RuntimeManifest(RuntimeManifest.SCHEMA_VERSION, "tenant-a", "default",
                "group-missing-old", 3L, List.of(new RuntimeManifest.Member("rule-a", 2L, NEW_PLAN_HASH)));
        DetectionRuntimeTarget target = target(newManifest, codec);
        JdbcTemplate jdbc = populatedJdbc(oldManifest);
        DetectionArtifactBuilder artifacts = new DetectionArtifactBuilder(jdbc, codec, temp);
        replaceRows(jdbc, newManifest);
        String key = new DetectionJobNameCodec().jobKey("tenant-a", "default", "group-missing-old");
        String oldName = DetectionJobNameCodec.jobName(key, 2L, codec.specHash(oldManifest));
        FakeRunner runner = new FakeRunner(List.of(new CommandRunner.CommandResult(0,
                "12121212121212121212121212121212 : " + oldName + " (RUNNING)\n", "")));

        IllegalStateException failure = assertThrows(IllegalStateException.class,
                () -> adapter(runner, artifacts, codec).apply(target, null));

        assertTrue(failure.getMessage().contains("old artifact"));
        assertEquals(1, runner.commands.size());
        assertTrue(runner.commands.getFirst().contains("list"));
    }

    @Test
    void copyFailureAfterCancellationRollsBackUsingOldArtifact() {
        RuntimeManifestCodec codec = new RuntimeManifestCodec();
        RuntimeManifest oldManifest = manifest("group-copy-failure", 2L);
        RuntimeManifest newManifest = new RuntimeManifest(RuntimeManifest.SCHEMA_VERSION, "tenant-a", "default",
                "group-copy-failure", 3L, List.of(new RuntimeManifest.Member("rule-a", 2L, NEW_PLAN_HASH)));
        DetectionRuntimeTarget oldTarget = target(oldManifest, codec);
        DetectionRuntimeTarget target = target(newManifest, codec);
        JdbcTemplate jdbc = populatedJdbc(oldManifest);
        DetectionArtifactBuilder artifacts = new DetectionArtifactBuilder(jdbc, codec, temp);
        artifacts.build(oldTarget);
        replaceRows(jdbc, newManifest);
        String key = new DetectionJobNameCodec().jobKey("tenant-a", "default", "group-copy-failure");
        String oldHash = codec.specHash(oldManifest);
        String oldName = DetectionJobNameCodec.jobName(key, 2L, oldHash);
        String rollbackId = "34343434343434343434343434343434";
        FakeRunner runner = new FakeRunner(List.of(
                new CommandRunner.CommandResult(0,
                        "23232323232323232323232323232323 : " + oldName + " (RUNNING)\n", ""),
                new CommandRunner.CommandResult(0, "Savepoint stored in file:///sp/copy-failure\n", ""),
                new CommandRunner.CommandResult(0, "", ""),
                new CommandRunner.CommandResult(1, "", "copy next failed"),
                new CommandRunner.CommandResult(0, "", ""),
                new CommandRunner.CommandResult(0, "", ""),
                new CommandRunner.CommandResult(0, rollbackId + "\n", ""),
                new CommandRunner.CommandResult(0,
                        rollbackId + " : " + oldName + " (RUNNING)\n", "")));

        IllegalStateException failure = assertThrows(IllegalStateException.class,
                () -> adapter(runner, artifacts, codec).apply(target, null));

        assertTrue(failure.getMessage().contains("previous runtime restored"));
        assertTrue(failure.getCause() != null && failure.getCause().getMessage() != null
                && failure.getCause().getMessage().contains("external command failed"));
        assertEquals(8, runner.commands.size());
        assertEquals(List.of("docker", "exec", "siem-flink-jobmanager", "flink", "cancel", "-s",
                "file:///opt/flink/savepoints/" + key,
                "23232323232323232323232323232323"), runner.commands.get(1));
        assertEquals(oldHash, runner.commands.get(6).getLast());
    }

    @Test
    void duplicateActiveJobsFailApplyBeforeAnyCancellation() {
        RuntimeManifestCodec codec = new RuntimeManifestCodec();
        RuntimeManifest manifest = manifest("group-duplicate", 1L);
        DetectionRuntimeTarget target = target(manifest, codec);
        DetectionArtifactBuilder builder = new DetectionArtifactBuilder(populatedJdbc(manifest), codec, temp);
        String key = new DetectionJobNameCodec().jobKey("tenant-a", "default", "group-duplicate");
        String name = DetectionJobNameCodec.jobName(key, 1L, codec.specHash(manifest));
        FakeRunner runner = new FakeRunner(List.of(new CommandRunner.CommandResult(0,
                "55555555555555555555555555555555 : " + name + " (RUNNING)\n"
                        + "66666666666666666666666666666666 : " + name + " (RUNNING)\n", "")));

        assertThrows(IllegalStateException.class,
                () -> adapter(runner, builder, codec).apply(target, null));
        assertEquals(1, runner.commands.size());
    }

    @Test
    void failedJobIsReportedAsTerminalAndNewApplyDoesNotCancelIt() {
        RuntimeManifestCodec codec = new RuntimeManifestCodec();
        RuntimeManifest manifest = manifest("group-failed-terminal", 1L);
        DetectionRuntimeTarget target = target(manifest, codec);
        JdbcTemplate jdbc = populatedJdbc(manifest);
        DetectionArtifactBuilder artifacts = new DetectionArtifactBuilder(jdbc, codec, temp);
        artifacts.build(target);
        String key = new DetectionJobNameCodec().jobKey("tenant-a", "default", "group-failed-terminal");
        String hash = codec.specHash(manifest);
        String failedId = "56565656565656565656565656565656";
        String runningId = "57575757575757575757575757575757";
        String failedName = DetectionJobNameCodec.jobName(key, 1L, hash);
        FakeRunner runner = new FakeRunner(List.of(
                new CommandRunner.CommandResult(0,
                        failedId + " : " + failedName + " (FAILED)\n", ""),
                new CommandRunner.CommandResult(0,
                        failedId + " : " + failedName + " (FAILED)\n", ""),
                new CommandRunner.CommandResult(0, "", ""),
                new CommandRunner.CommandResult(0, "", ""),
                new CommandRunner.CommandResult(0, runningId + "\n", ""),
                new CommandRunner.CommandResult(0,
                        runningId + " : " + failedName + " (RUNNING)\n", "")));

        RuntimeObservation failed = adapter(runner, artifacts, codec).inspect(target);
        RuntimeObservation applied = adapter(runner, artifacts, codec).apply(target, null);

        assertEquals("FAILED", failed.runtimeState());
        assertEquals(failedId, failed.jobId());
        assertEquals("RUNNING", applied.runtimeState());
        assertEquals(6, runner.commands.size());
        assertTrue(runner.commands.stream().noneMatch(command -> command.contains("cancel")));
    }

    @Test
    void cancellingSpellingMapsToDeploying() {
        RuntimeManifestCodec codec = new RuntimeManifestCodec();
        RuntimeManifest manifest = manifest("group-cancelling", 1L);
        DetectionRuntimeTarget target = target(manifest, codec);
        DetectionArtifactBuilder artifacts = new DetectionArtifactBuilder(populatedJdbc(manifest), codec, temp);
        artifacts.build(target);
        String key = new DetectionJobNameCodec().jobKey("tenant-a", "default", "group-cancelling");
        String name = DetectionJobNameCodec.jobName(key, 1L, codec.specHash(manifest));
        FakeRunner runner = new FakeRunner(List.of(new CommandRunner.CommandResult(0,
                "58585858585858585858585858585858 : " + name + " (CANCELLING)\n", "")));

        RuntimeObservation observed = adapter(runner, artifacts, codec).inspect(target);

        assertEquals("DEPLOYING", observed.runtimeState());
    }

    @Test
    void rejectsMalformedStructuredNameInsteadOfTreatingItAsStopped() {
        RuntimeManifestCodec codec = new RuntimeManifestCodec();
        ProcessFlinkRuntimeAdapter adapter = adapter(new FakeRunner(List.of()),
                new DetectionArtifactBuilder(emptyJdbc(), codec, temp), codec);

        assertThrows(IllegalStateException.class, () -> adapter.parseJobs(
                "0123456789abcdef0123456789abcdef : "
                        + "SIEM-DETECTION-dg-aaaaaaaaaaaaaaaaaaaaaaaa-g1-m"
                        + "bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb"
                        + "-tampered (RUNNING)\n"));
    }
    @Test
    void failedReplacementRollsBackUsingOriginalSavepointAndArtifact() {
        RuntimeManifestCodec codec = new RuntimeManifestCodec();
        RuntimeManifest oldManifest = manifest("group-rollback", 2L);
        RuntimeManifest newManifest = new RuntimeManifest(RuntimeManifest.SCHEMA_VERSION, "tenant-a", "default",
                "group-rollback", 3L, List.of(new RuntimeManifest.Member("rule-a", 2L, NEW_PLAN_HASH)));
        DetectionRuntimeTarget oldTarget = target(oldManifest, codec);
        DetectionRuntimeTarget target = target(newManifest, codec);
        JdbcTemplate jdbc = populatedJdbc(oldManifest);
        DetectionArtifactBuilder artifacts = new DetectionArtifactBuilder(jdbc, codec, temp);
        artifacts.build(oldTarget);
        replaceRows(jdbc, newManifest);
        String key = new DetectionJobNameCodec().jobKey("tenant-a", "default", "group-rollback");
        String oldHash = codec.specHash(oldManifest);
        String newHash = codec.specHash(newManifest);
        String oldId = "99999999999999999999999999999999";
        String rollbackId = "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa";
        String oldName = DetectionJobNameCodec.jobName(key, 2L, oldHash);
        FakeRunner runner = new FakeRunner(List.of(
                new CommandRunner.CommandResult(0, oldId + " : " + oldName + " (RUNNING)\n", ""),
                new CommandRunner.CommandResult(0, "Savepoint completed. Path: file:///sp/rollback\n", ""),
                new CommandRunner.CommandResult(0, "", ""),
                new CommandRunner.CommandResult(0, "", ""),
                new CommandRunner.CommandResult(1, "", "replacement failed"),
                new CommandRunner.CommandResult(0, "", ""),
                new CommandRunner.CommandResult(0, "", ""),
                new CommandRunner.CommandResult(0, rollbackId + "\n", ""),
                new CommandRunner.CommandResult(0, rollbackId + " : " + oldName + " (RUNNING)\n", ""),
                new CommandRunner.CommandResult(0, rollbackId + " : " + oldName + " (RUNNING)\n", "")));

        IllegalStateException failure = assertThrows(IllegalStateException.class,
                () -> adapter(runner, artifacts, codec).apply(target, null));

        assertTrue(failure.getMessage().contains("previous runtime restored"));
        assertTrue(failure.getCause() != null && failure.getCause().getMessage() != null
                && failure.getCause().getMessage().contains("flink run failed"));
        assertEquals(9, runner.commands.size());
        assertEquals("file:///sp/rollback", runner.commands.get(7).get(7));
        assertEquals(oldHash, runner.commands.get(7).getLast());
    }

    @Test
    void failedReplacementAndFailedRollbackRetainOriginalFailureAsSuppressed() {
        RuntimeManifestCodec codec = new RuntimeManifestCodec();
        RuntimeManifest oldManifest = manifest("group-rollback-fail", 2L);
        RuntimeManifest newManifest = new RuntimeManifest(RuntimeManifest.SCHEMA_VERSION, "tenant-a", "default",
                "group-rollback-fail", 3L, List.of(new RuntimeManifest.Member("rule-a", 2L, NEW_PLAN_HASH)));
        DetectionRuntimeTarget oldTarget = target(oldManifest, codec);
        DetectionRuntimeTarget target = target(newManifest, codec);
        JdbcTemplate jdbc = populatedJdbc(oldManifest);
        DetectionArtifactBuilder artifacts = new DetectionArtifactBuilder(jdbc, codec, temp);
        artifacts.build(oldTarget);
        replaceRows(jdbc, newManifest);
        String key = new DetectionJobNameCodec().jobKey("tenant-a", "default", "group-rollback-fail");
        String oldHash = codec.specHash(oldManifest);
        String newHash = codec.specHash(newManifest);
        String oldId = "bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb";
        String oldName = DetectionJobNameCodec.jobName(key, 2L, oldHash);
        FakeRunner runner = new FakeRunner(List.of(
                new CommandRunner.CommandResult(0, oldId + " : " + oldName + " (RUNNING)\n", ""),
                new CommandRunner.CommandResult(0, "Savepoint completed. Path: file:///sp/rollback-fail\n", ""),
                new CommandRunner.CommandResult(0, "", ""),
                new CommandRunner.CommandResult(0, "", ""),
                new CommandRunner.CommandResult(1, "", "replacement failed"),
                new CommandRunner.CommandResult(1, "", "rollback copy failed")));

        IllegalStateException failure = assertThrows(IllegalStateException.class,
                () -> adapter(runner, artifacts, codec).apply(target, null));

        assertTrue(java.util.Arrays.stream(failure.getSuppressed())
                .anyMatch(suppressed -> suppressed.getMessage() != null
                        && suppressed.getMessage().contains("flink run failed")));
        assertEquals(newHash, target.expectedManifestHash());
    }

    @Test
    void corruptArtifactHashIsUnknownAndDoesNotUseExpectedMembers() throws Exception {
        RuntimeManifestCodec codec = new RuntimeManifestCodec();
        RuntimeManifest manifest = manifest("group-corrupt", 2L);
        DetectionRuntimeTarget target = target(manifest, codec);
        DetectionArtifactBuilder artifacts = new DetectionArtifactBuilder(emptyJdbc(), codec, temp);
        String key = new DetectionJobNameCodec().jobKey("tenant-a", "default", "group-corrupt");
        String hash = codec.specHash(manifest);
        Path path = artifacts.artifactPath(key, 2L, hash);
        Files.createDirectories(path);
        Files.writeString(path.resolve("runtime-manifest.json"), "corrupt", StandardCharsets.UTF_8);
        String name = DetectionJobNameCodec.jobName(key, 2L, hash);
        FakeRunner runner = new FakeRunner(List.of(new CommandRunner.CommandResult(0,
                "77777777777777777777777777777777 : " + name + " (RUNNING)\n", "")));

        RuntimeObservation observed = adapter(runner, artifacts, codec).inspect(target);

        assertEquals("UNKNOWN", observed.runtimeState());
        assertEquals("ARTIFACT_INVALID", observed.errorCode());
        assertTrue(observed.members().isEmpty());
    }

    @Test
    void applyTimeoutIsReportedWhenJobNeverReachesExactRunning() {
        RuntimeManifestCodec codec = new RuntimeManifestCodec();
        RuntimeManifest manifest = manifest("group-timeout", 1L);
        DetectionRuntimeTarget target = target(manifest, codec);
        DetectionArtifactBuilder builder = new DetectionArtifactBuilder(populatedJdbc(manifest), codec, temp);
        FakeRunner runner = new FakeRunner(List.of(
                new CommandRunner.CommandResult(0, "", ""),
                new CommandRunner.CommandResult(0, "", ""),
                new CommandRunner.CommandResult(0, "", ""),
                new CommandRunner.CommandResult(0, "88888888888888888888888888888888\n", ""),
                new CommandRunner.CommandResult(0, "", "")));
        ProcessFlinkRuntimeAdapter adapter = adapter(runner, builder, codec,
                Duration.ofMillis(1));

        assertThrows(IllegalStateException.class, () -> adapter.apply(target, null));
    }

    private ProcessFlinkRuntimeAdapter adapter(FakeRunner runner,
                                                DetectionArtifactBuilder artifacts,
                                                RuntimeManifestCodec codec,
                                                Duration timeout) {
        return new ProcessFlinkRuntimeAdapter(runner, artifacts, new DetectionJobNameCodec(), codec,
                "default", "siem-flink-jobmanager", "/opt/flink/detection-job-1.0.jar",
                "file:///opt/flink/savepoints", timeout, Duration.ZERO, Set.of("default"));
    }

    private ProcessFlinkRuntimeAdapter adapter(FakeRunner runner,
                                                DetectionArtifactBuilder artifacts,
                                                RuntimeManifestCodec codec) {
        return new ProcessFlinkRuntimeAdapter(runner, artifacts, new DetectionJobNameCodec(), codec,
                "default", "siem-flink-jobmanager", "/opt/flink/detection-job-1.0.jar",
                "file:///opt/flink/savepoints", Duration.ofSeconds(2), Duration.ZERO, Set.of("default"));
    }

    private static RuntimeManifest manifest(String group, long generation) {
        return new RuntimeManifest(RuntimeManifest.SCHEMA_VERSION, "tenant-a", "default", group,
                generation, List.of(new RuntimeManifest.Member("rule-a", 1L, PLAN_HASH)));
    }

    private static DetectionRuntimeTarget target(RuntimeManifest manifest, RuntimeManifestCodec codec) {
        return new DetectionRuntimeTarget(manifest.tenantId(), manifest.jobGroupKey(), manifest.targetCluster(),
                manifest.generation(), codec.canonicalSpecJson(manifest), codec.specHash(manifest));
    }

    private static String sha256(String value) {
        try {
            return java.util.HexFormat.of().formatHex(
                    java.security.MessageDigest.getInstance("SHA-256")
                            .digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (java.security.NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 is unavailable", e);
        }
    }

    private JdbcTemplate emptyJdbc() {
        HikariDataSource dataSource = new HikariDataSource();
        dataSource.setJdbcUrl("jdbc:h2:mem:adapter-" + UUID.randomUUID()
                + ";MODE=PostgreSQL;DB_CLOSE_DELAY=-1");
        dataSource.setUsername("sa");
        dataSource.setPassword("");
        dataSources.add(dataSource);
        JdbcTemplate jdbc = new JdbcTemplate(dataSource);
        jdbc.execute("CREATE TABLE rule_revision (revision_id UUID, rule_key VARCHAR(128), revision BIGINT, definition_json TEXT)");
        jdbc.execute("CREATE TABLE detection_plan (plan_id UUID, revision_id UUID, "
                + "compiler_version VARCHAR(128), plan_json TEXT, plan_hash VARCHAR(128))");
        jdbc.execute("CREATE TABLE rule_job_assignment (tenant_id VARCHAR(64), rule_key VARCHAR(128), revision BIGINT, plan_id UUID, plan_hash VARCHAR(128), group_key VARCHAR(512), generation BIGINT)");
        return jdbc;
    }

    private JdbcTemplate populatedJdbc(RuntimeManifest manifest) {
        JdbcTemplate jdbc = emptyJdbc();
        replaceRows(jdbc, manifest);
        return jdbc;
    }

    private void replaceRows(JdbcTemplate jdbc, RuntimeManifest manifest) {
        jdbc.execute("DELETE FROM rule_job_assignment");
        jdbc.execute("DELETE FROM detection_plan");
        jdbc.execute("DELETE FROM rule_revision");
        for (RuntimeManifest.Member member : manifest.members()) {
            UUID revisionId = UUID.randomUUID();
            UUID planId = UUID.randomUUID();
            String definition = "{\"id\":\"" + member.ruleKey().replace("\\\"", "")
                    + "\",\"enabled\":true}";
            String planJson;
            if (PLAN_HASH.equals(member.planHash())) {
                planJson = PLAN_JSON;
            } else if (NEW_PLAN_HASH.equals(member.planHash())) {
                planJson = NEW_PLAN_JSON;
            } else {
                throw new IllegalArgumentException("unknown fixture plan hash: " + member.planHash());
            }
            jdbc.update("INSERT INTO rule_revision VALUES (?, ?, ?, ?)", revisionId,
                    member.ruleKey(), member.revision(), definition);
            jdbc.update("INSERT INTO detection_plan VALUES (?, ?, ?, ?, ?)", planId, revisionId,
                    DetectionPlanCompiler.VERSION, planJson, member.planHash());
            jdbc.update("INSERT INTO rule_job_assignment VALUES (?, ?, ?, ?, ?, ?, ?)",
                    manifest.tenantId(), member.ruleKey(), member.revision(), planId,
                    member.planHash(), manifest.jobGroupKey(), manifest.generation());
        }
    }

    private static RuntimeObservation runningObservation(DetectionRuntimeTarget target,
                                                           RuntimeManifest manifest,
                                                           String jobId) {
        String key = new DetectionJobNameCodec().jobKey(target.tenantId(), target.targetCluster(),
                target.groupKey());
        return RuntimeObservation.running(target, jobId, key, manifest.members().stream()
                .map(member -> new RuntimeObservation.Member(member.ruleKey(), member.revision(),
                        member.planHash()))
                .toList());
    }

    private static final class FakeRunner implements CommandRunner {
        private final List<CommandRunner.CommandResult> results;
        private final List<List<String>> commands = new ArrayList<>();
        private int index;

        private FakeRunner(List<CommandRunner.CommandResult> results) {
            this.results = results;
        }

        @Override
        public CommandRunner.CommandResult run(List<String> arguments, Duration timeout) {
            commands.add(List.copyOf(arguments));
            if (index >= results.size()) return new CommandRunner.CommandResult(0, "", "");
            return results.get(index++);
        }
    }
}
