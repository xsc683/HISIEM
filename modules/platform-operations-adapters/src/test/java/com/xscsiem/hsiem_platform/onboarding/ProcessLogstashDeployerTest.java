package com.xscsiem.hsiem_platform.onboarding;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

class ProcessLogstashDeployerTest {

    @Test
    void syncAndRestartPassInputsAsBashPositionalArguments() {
        List<List<String>> commands = new ArrayList<>();
        String repo = "/mnt/d/Project/SIEM;$(touch PWNED)'quoted'";
        String deploy = "~/projects/mini siem";
        ProcessLogstashDeployer deployer =
                new ProcessLogstashDeployer(
                        repo,
                        deploy,
                        "siem-logstash",
                        "compose-file.yml",
                        command -> {
                            commands.add(command);
                            return true;
                        });

        deployer.syncLogstash();
        deployer.restartLogstash();

        assertEquals(3, commands.size());
        List<String> sync = commands.get(0);
        assertEquals(List.of("wsl", "bash", "-c", sync.get(3), "--", repo, deploy), sync);
        assertTrue(sync.get(3).contains("$1") && sync.get(3).contains("$2"));
        assertFalse(sync.get(3).contains(repo));
        assertFalse(sync.get(3).contains(deploy));

        List<String> composeSync = commands.get(1);
        assertEquals(
                List.of(
                        "wsl",
                        "bash",
                        "-c",
                        composeSync.get(3),
                        "--",
                        repo,
                        deploy,
                        "compose-file.yml"),
                composeSync);
        assertFalse(composeSync.get(3).contains(repo));

        List<String> restart = commands.get(2);
        assertEquals(
                List.of("wsl", "bash", "-c", restart.get(3), "--", deploy, "compose-file.yml"),
                restart);
        assertTrue(restart.get(3).contains("${COMPOSE_PROJECT_NAME:-infra}"));
        assertFalse(restart.get(3).contains(deploy));
    }

    @Test
    void dockerOperationsRemainArgumentVectors() {
        List<List<String>> commands = new ArrayList<>();
        ProcessLogstashDeployer deployer =
                new ProcessLogstashDeployer(
                        "/mnt/d/Project/SIEM",
                        "~/projects/mini-siem",
                        "container-name",
                        "docker-compose.yml",
                        command -> {
                            commands.add(command);
                            return true;
                        });

        assertTrue(deployer.validateConfig("/usr/share/logstash/pipeline/log-sources/rule.conf"));
        deployer.reloadLogstash();

        assertEquals(
                List.of(
                        "docker",
                        "exec",
                        "container-name",
                        "logstash",
                        "--config.test_and_exit",
                        "-f",
                        "/usr/share/logstash/pipeline/log-sources/rule.conf",
                        commands.get(0).get(7)),
                commands.get(0));
        assertTrue(commands.get(0).get(7).startsWith("--path.data=/tmp/ls-validate-"));
        assertEquals(
                List.of("docker", "exec", "container-name", "kill", "-HUP", "1"), commands.get(1));
    }

    @Test
    void invalidConfigPathIsRejectedWithoutRunningDocker() {
        List<List<String>> commands = new ArrayList<>();
        ProcessLogstashDeployer deployer =
                new ProcessLogstashDeployer(
                        "/mnt/d/Project/SIEM",
                        "~/projects/mini-siem",
                        "container",
                        "compose.yml",
                        command -> {
                            commands.add(command);
                            return true;
                        });

        assertFalse(deployer.validateConfig("/usr/share/logstash/pipeline/../secret.conf"));
        assertFalse(deployer.validateConfig("/etc/logstash/pipeline/rule.conf"));
        assertFalse(deployer.validateConfig("/usr/share/logstash/pipeline/rule.txt"));
        assertFalse(deployer.validateConfig("/usr/share/logstash/pipeline/$(touch PWNED).conf"));
        assertEquals(0, commands.size());
    }

    @Test
    void unsafeConfiguredValuesAreRejected() {
        assertThrows(
                IllegalArgumentException.class,
                () -> deployer("relative/path", "~/deploy", "container", "compose.yml"));
        assertThrows(
                IllegalArgumentException.class,
                () -> deployer("/mnt/repo/../other", "~/deploy", "container", "compose.yml"));
        assertThrows(
                IllegalArgumentException.class,
                () -> deployer("/mnt/repo", "~/deploy/./nested", "container", "compose.yml"));
        assertThrows(
                IllegalArgumentException.class,
                () -> deployer("/mnt/repo", "~/deploy", "container;bad", "compose.yml"));
        assertThrows(
                IllegalArgumentException.class,
                () -> deployer("/mnt/repo", "~/deploy", "container", "../compose.yml"));
        assertThrows(
                IllegalArgumentException.class,
                () ->
                        deployer(
                                "/mnt/repo\n$(touch PWNED)",
                                "~/deploy",
                                "container",
                                "compose.yml"));
    }

    @Test
    void failedCommandStopsFollowingSyncStep() {
        List<List<String>> commands = new ArrayList<>();
        ProcessLogstashDeployer deployer =
                new ProcessLogstashDeployer(
                        "/mnt/d/Project/SIEM",
                        "~/projects/mini-siem",
                        "container",
                        "compose.yml",
                        command -> {
                            commands.add(command);
                            return false;
                        });

        assertThrows(IllegalStateException.class, deployer::syncLogstash);
        assertEquals(1, commands.size());
    }

    private static ProcessLogstashDeployer deployer(
            String repo, String deploy, String container, String compose) {
        return new ProcessLogstashDeployer(repo, deploy, container, compose, command -> true);
    }
}
