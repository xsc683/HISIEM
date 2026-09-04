package com.xscsiem.hsiem_platform.control;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.util.HexFormat;

/** 文件型配置的统一原子写入与 revision 审计辅助。 */
public final class ConfigRevisionJournal {

    private ConfigRevisionJournal() {}

    public static void atomicWrite(Path path, String content) throws java.io.IOException {
        Files.createDirectories(path.toAbsolutePath().getParent());
        Path tmp =
                Files.createTempFile(
                        path.toAbsolutePath().getParent(), path.getFileName().toString(), ".tmp");
        try {
            Files.writeString(tmp, content, StandardCharsets.UTF_8);
            try {
                Files.move(
                        tmp,
                        path,
                        StandardCopyOption.REPLACE_EXISTING,
                        StandardCopyOption.ATOMIC_MOVE);
            } catch (java.nio.file.AtomicMoveNotSupportedException e) {
                Files.move(tmp, path, StandardCopyOption.REPLACE_EXISTING);
            }
        } finally {
            Files.deleteIfExists(tmp);
        }
    }

    public static void record(AuthStore control, String kind, Path path, String actor) {
        if (control == null || !Files.exists(path)) return;
        try {
            byte[] bytes = Files.readAllBytes(path);
            String revision =
                    HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
            control.audit(
                    actor == null || actor.isBlank() ? "system" : actor,
                    "config_revision",
                    kind + ":" + path + "#" + revision.substring(0, 16));
        } catch (Exception e) {
            throw new IllegalStateException("配置 revision 审计失败: " + path, e);
        }
    }
}
