package com.xscsiem.hsiem_platform.onboarding;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.List;

/**
 * 数据源声明存储:infra/log-sources/*.yaml(文件 + Git)。
 * 目录不存在时首次写入自动创建;文件由 Git 版本化(review/审计可追溯)。
 */
@Component
public class LogSourceStore {

    private final ObjectMapper yamlMapper = new ObjectMapper(new YAMLFactory());
    private final String dir;

    public LogSourceStore(@Value("${app.log-sources-dir:infra/log-sources}") String dir) {
        this.dir = dir;
    }

    private Path dirPath() throws IOException {
        Path p = Path.of(dir);
        Files.createDirectories(p);
        return p;
    }

    public List<LogSource> list() {
        File d = new File(dir);
        File[] files = d.listFiles((x, n) -> n.endsWith(".yaml") || n.endsWith(".yml"));
        List<LogSource> out = new ArrayList<>();
        if (files != null) {
            for (File f : files) {
                try {
                    out.add(yamlMapper.readValue(f, LogSource.class));
                } catch (Exception e) {
                    throw new IllegalStateException("数据源加载失败: " + f.getName(), e);
                }
            }
        }
        return out;
    }

    public LogSource find(String id) {
        return list().stream()
                .filter(s -> s.id != null && s.id.equals(id))
                .findFirst()
                .orElseThrow(() -> new NotFoundException("数据源不存在: " + id));
    }

    public void save(LogSource s) {
        try {
            Path p = dirPath().resolve(s.id + ".yaml");
            Path tmp = Files.createTempFile(p.getParent(), p.getFileName().toString(), ".tmp");
            try {
                yamlMapper.writeValue(tmp.toFile(), s);
                try {
                    Files.move(tmp, p, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
                } catch (java.nio.file.AtomicMoveNotSupportedException e) {
                    Files.move(tmp, p, StandardCopyOption.REPLACE_EXISTING);
                }
            } finally {
                Files.deleteIfExists(tmp);
            }
        } catch (IOException e) {
            throw new IllegalStateException("数据源保存失败: " + s.id, e);
        }
    }

    public void delete(String id) {
        try {
            Files.deleteIfExists(dirPath().resolve(id + ".yaml"));
        } catch (IOException e) {
            throw new IllegalStateException("数据源删除失败: " + id, e);
        }
    }
}
