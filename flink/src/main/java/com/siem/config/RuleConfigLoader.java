package com.siem.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * 加载 infra/rules/*.yaml 为规则声明列表(检测即代码单一来源)。
 *
 * 目录解析(DetectionJob):
 * - 容器:deploy.sh 把 infra/rules 同步到 jobmanager /opt/flink/rules
 * - 本地:传仓库相对路径(如 infra/rules)或设置环境变量 SIEM_RULES_DIR
 */
public class RuleConfigLoader {

    private final ObjectMapper yamlMapper = new ObjectMapper(new YAMLFactory());

    public List<RuleDecl> loadDir(String dir) {
        Path d = Path.of(dir);
        if (!Files.isDirectory(d)) {
            throw new IllegalStateException("规则目录不存在: " + dir);
        }
        List<RuleDecl> decls = new ArrayList<>();
        File[] files = d.toFile().listFiles((x, n) -> n.endsWith(".yaml") || n.endsWith(".yml"));
        if (files != null) {
            java.util.Arrays.sort(files, Comparator.comparing(File::getName));
            Set<String> ids = new HashSet<>();
            for (File f : files) {
                try {
                    RuleDecl declaration = yamlMapper.readValue(f, RuleDecl.class);
                    if (declaration == null || declaration.id == null || declaration.id.isBlank()) {
                        throw new IllegalStateException("规则 ID 为空: " + f.getName());
                    }
                    if (!ids.add(declaration.id)) {
                        throw new IllegalStateException("规则 ID 重复: " + declaration.id);
                    }
                    decls.add(declaration);
                } catch (IOException e) {
                    throw new IllegalStateException("规则加载失败: " + f.getName(), e);
                }
            }
        }
        if (decls.isEmpty()) {
            throw new IllegalStateException("规则目录为空: " + dir);
        }
        return decls;
    }

    /** 仅返回 enabled=true 的规则(启动注册依据)。 */
    public List<RuleDecl> loadEnabled(String dir) {
        return loadDir(dir).stream().filter(d -> d.enabled).toList();
    }
}
