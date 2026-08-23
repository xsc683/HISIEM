package com.xscsiem.hsiem_platform.soar;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory;
import com.xscsiem.hsiem_platform.onboarding.NotFoundException;
import jakarta.annotation.PostConstruct;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

/** 从受版本控制的 YAML 目录加载并校验 Playbook。 */
@Component
public class SoarPlaybookRegistry {

    public static final Set<String> ALLOWED_ACTIONS = Set.of(
            "approval",
            "alert.set_status",
            "alert.set_verdict",
            "case.set_status",
            "case.add_alert",
            "case.add_evidence",
            "notification.create");
    private static final Set<String> OPERATORS = Set.of("eq", "ne", "gt", "gte", "lt", "lte", "exists", "contains");
    private static final Pattern ID = Pattern.compile("[a-z0-9][a-z0-9-]{2,127}");

    private final Path directory;
    private final ObjectMapper yaml = new ObjectMapper(new YAMLFactory()).findAndRegisterModules();
    private volatile Map<String, SoarPlaybook> playbooks = Map.of();

    public SoarPlaybookRegistry(@Value("${app.soar.playbooks-dir:infra/soar/playbooks}") String directory) {
        this.directory = Path.of(directory).toAbsolutePath().normalize();
    }

    @PostConstruct
    public void initialize() {
        reload();
    }

    public List<SoarPlaybook> list() {
        return new ArrayList<>(playbooks.values());
    }

    public SoarPlaybook get(String id) {
        SoarPlaybook playbook = playbooks.get(id);
        if (playbook == null) {
            throw new NotFoundException("SOAR Playbook 不存在: " + id);
        }
        return playbook;
    }

    public synchronized List<SoarPlaybook> reload() {
        if (!Files.isDirectory(directory)) {
            throw new IllegalStateException("SOAR Playbook 目录不存在: " + directory);
        }
        Map<String, SoarPlaybook> loaded = new LinkedHashMap<>();
        try (var files = Files.list(directory)) {
            for (Path path : files.filter(Files::isRegularFile)
                    .filter(p -> p.getFileName().toString().endsWith(".yaml")
                            || p.getFileName().toString().endsWith(".yml"))
                    .sorted().toList()) {
                SoarPlaybook playbook = yaml.readValue(path.toFile(), SoarPlaybook.class);
                validate(playbook, path);
                if (loaded.putIfAbsent(playbook.id(), playbook) != null) {
                    throw new IllegalArgumentException("SOAR Playbook ID 重复: " + playbook.id());
                }
            }
        } catch (IOException e) {
            throw new IllegalStateException("读取 SOAR Playbook 失败: " + e.getMessage(), e);
        }
        playbooks = Collections.unmodifiableMap(new LinkedHashMap<>(loaded));
        return list();
    }

    static void validate(SoarPlaybook playbook, Path source) {
        String label = source == null ? "playbook" : source.toString();
        if (playbook == null || playbook.id() == null || !ID.matcher(playbook.id()).matches()) {
            throw new IllegalArgumentException(label + " 的 id 必须为 3-128 位小写字母、数字或连字符");
        }
        if (blank(playbook.name()) || blank(playbook.version())) {
            throw new IllegalArgumentException(playbook.id() + " 缺少 name/version");
        }
        if (playbook.resourceTypes() == null || playbook.resourceTypes().isEmpty()
                || playbook.resourceTypes().stream().anyMatch(type -> !Set.of("alert", "case").contains(type))) {
            throw new IllegalArgumentException(playbook.id() + " 的 resourceTypes 仅支持 alert/case");
        }
        validateCondition(playbook.id(), playbook.when());
        if (playbook.steps() == null || playbook.steps().isEmpty() || playbook.steps().size() > 50) {
            throw new IllegalArgumentException(playbook.id() + " 的步骤数需在 1-50 之间");
        }
        Set<String> stepIds = new HashSet<>();
        for (SoarPlaybook.Step step : playbook.steps()) {
            if (step == null || step.id() == null || !ID.matcher(step.id()).matches()) {
                throw new IllegalArgumentException(playbook.id() + " 存在非法 step.id");
            }
            if (!stepIds.add(step.id())) {
                throw new IllegalArgumentException(playbook.id() + " 的 step.id 重复: " + step.id());
            }
            if (blank(step.name()) || !ALLOWED_ACTIONS.contains(step.action())) {
                throw new IllegalArgumentException(playbook.id() + "/" + step.id() + " 的 name/action 非法");
            }
            validateCondition(playbook.id() + "/" + step.id(), step.when());
            if ("approval".equals(step.action())) {
                String role = string(parameters(step).getOrDefault("requiredRole", "analyst"));
                if (!Set.of("admin", "analyst").contains(role)) {
                    throw new IllegalArgumentException(playbook.id() + "/" + step.id() + " 的审批角色仅支持 admin/analyst");
                }
            }
        }
    }

    static Map<String, Object> parameters(SoarPlaybook.Step step) {
        return step.parameters() == null ? Map.of() : step.parameters();
    }

    private static void validateCondition(String owner, SoarPlaybook.Condition condition) {
        if (condition != null && (blank(condition.field()) || !OPERATORS.contains(condition.operator()))) {
            throw new IllegalArgumentException(owner + " 的 when 条件非法");
        }
    }

    private static boolean blank(String value) {
        return value == null || value.isBlank();
    }

    private static String string(Object value) {
        return value == null ? "" : String.valueOf(value);
    }
}
