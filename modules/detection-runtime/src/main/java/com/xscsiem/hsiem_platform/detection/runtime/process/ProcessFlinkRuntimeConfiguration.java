package com.xscsiem.hsiem_platform.detection.runtime.process;

import com.xscsiem.hsiem_platform.detection.runtime.DetectionArtifactBuilder;
import com.xscsiem.hsiem_platform.detection.runtime.DetectionArtifactRepositoryPort;
import com.xscsiem.hsiem_platform.detection.runtime.DetectionJobNameCodec;
import com.xscsiem.hsiem_platform.rules.runtime.RuntimeManifestCodec;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Arrays;
import java.util.Set;
import java.util.stream.Collectors;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/** Process adapter composition is opt-in; the disabled adapter remains the default elsewhere. */
@Configuration(proxyBeanMethods = false)
@ConditionalOnProperty(name = "app.detection.runtime-adapter", havingValue = "process")
public class ProcessFlinkRuntimeConfiguration {

    @Bean
    public CommandRunner processCommandRunner() {
        return new ProcessCommandRunner();
    }

    @Bean
    public DetectionJobNameCodec detectionJobNameCodec() {
        return new DetectionJobNameCodec();
    }

    @Bean
    public RuntimeManifestCodec runtimeManifestCodec() {
        return new RuntimeManifestCodec();
    }

    @Bean
    public DetectionArtifactBuilder detectionArtifactBuilder(
            DetectionArtifactRepositoryPort repository,
            RuntimeManifestCodec codec,
            @Value("${app.detection.artifact-root:./data/detection-artifacts}") String artifactRoot,
            @Value("${app.detection.container-artifact-root:/opt/flink/detection-artifacts}")
                    String containerRoot) {
        return new DetectionArtifactBuilder(
                repository, codec, Path.of(artifactRoot), containerRoot);
    }

    @Bean
    public ProcessFlinkRuntimeAdapter processFlinkRuntimeAdapter(
            CommandRunner commands,
            DetectionArtifactBuilder artifacts,
            DetectionJobNameCodec names,
            RuntimeManifestCodec codec,
            @Value("${app.detection.cluster-id:default}") String clusterId,
            @Value("${app.detection.container-name:siem-flink-jobmanager}") String containerName,
            @Value("${app.detection.jar-path:/opt/flink/detection-job-1.0.jar}") String jarPath,
            @Value("${app.detection.savepoint-root:file:///opt/flink/savepoints}")
                    String savepointRoot,
            @Value("${app.detection.command-timeout:PT120S}") Duration commandTimeout,
            @Value("${app.detection.verify-poll:PT1S}") Duration verifyPoll,
            @Value("${app.detection.allowed-clusters:}") String allowedClusters) {
        Set<String> whitelist =
                Arrays.stream(allowedClusters == null ? new String[0] : allowedClusters.split(","))
                        .map(String::trim)
                        .filter(value -> !value.isBlank())
                        .collect(Collectors.toSet());
        return new ProcessFlinkRuntimeAdapter(
                commands,
                artifacts,
                names,
                codec,
                clusterId,
                containerName,
                jarPath,
                savepointRoot,
                commandTimeout,
                verifyPoll,
                whitelist);
    }
}
