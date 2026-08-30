package com.siem;

import com.siem.config.RuleDecl;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RuntimeManifestVerifierTest {

    @TempDir
    Path temp;

    @Test
    void verifiesRawHashGenerationAndExactLoadedRuleSet() throws Exception {
        RuleDecl first = rule("rule-a");
        RuleDecl second = rule("rule-b");
        String raw = "{\"schemaVersion\":\"1\",\"tenantId\":\"tenant\","
                + "\"targetCluster\":\"default\",\"jobGroupKey\":\"group\","
                + "\"generation\":4,\"members\":["
                + "{\"ruleKey\":\"rule-a\",\"revision\":1,\"planHash\":\"a\"},"
                + "{\"ruleKey\":\"rule-b\",\"revision\":2,\"planHash\":\"b\"}]}";
        Files.writeString(temp.resolve("runtime-manifest.json"), raw, StandardCharsets.UTF_8);
        String hash = sha256(raw.getBytes(StandardCharsets.UTF_8));
        DetectionJobArguments args = DetectionJobArguments.managed(temp.toString(),
                "dg-" + "a".repeat(24), 4L, hash);

        RuntimeManifestVerifier.Verification result = new RuntimeManifestVerifier().verify(
                temp, args, List.of(first, second));

        assertEquals(hash, result.rawHash());
        assertEquals(2, result.ruleKeys().size());
    }

    @Test
    void rejectsHashGenerationMissingExtraAndDuplicateIds() throws Exception {
        String raw = "{\"schemaVersion\":\"1\",\"generation\":4,\"members\":["
                + "{\"ruleKey\":\"rule-a\"},{\"ruleKey\":\"rule-a\"}]}";
        Files.writeString(temp.resolve("runtime-manifest.json"), raw);
        String hash = sha256(raw.getBytes(StandardCharsets.UTF_8));
        DetectionJobArguments args = DetectionJobArguments.managed(temp.toString(),
                "dg-" + "a".repeat(24), 4L, hash);
        RuleDecl rule = rule("rule-a");

        assertThrows(IllegalStateException.class,
                () -> new RuntimeManifestVerifier().verify(temp, args, List.of(rule)));
        assertThrows(IllegalStateException.class,
                () -> new RuntimeManifestVerifier().verify(temp,
                        DetectionJobArguments.managed(temp.toString(), "dg-" + "a".repeat(24), 5L, hash),
                        List.of(rule)));
        assertThrows(IllegalStateException.class,
                () -> new RuntimeManifestVerifier().verify(temp,
                        DetectionJobArguments.managed(temp.toString(), "dg-" + "a".repeat(24), 4L,
                                "c".repeat(64)), List.of(rule)));
    }

    @Test
    void rejectsMissingAndExtraLoadedRuleIdsIndependently() throws Exception {
        String raw = "{\"schemaVersion\":\"1\",\"generation\":1,\"members\":["
                + "{\"ruleKey\":\"rule-a\",\"revision\":1,\"planHash\":\"a\"}]}";
        Files.writeString(temp.resolve("runtime-manifest.json"), raw, StandardCharsets.UTF_8);
        String hash = sha256(raw.getBytes(StandardCharsets.UTF_8));
        DetectionJobArguments args = DetectionJobArguments.managed(temp.toString(),
                "dg-" + "a".repeat(24), 1L, hash);
        RuntimeManifestVerifier verifier = new RuntimeManifestVerifier();

        assertThrows(IllegalStateException.class, () -> verifier.verify(temp, args, List.of()));
        assertThrows(IllegalStateException.class, () -> verifier.verify(temp, args,
                List.of(rule("rule-a"), rule("rule-extra"))));
    }

    @Test
    void rejectsMalformedUtf8BeforeJsonParsing() throws Exception {
        byte[] prefix = "{\"schemaVersion\":\"1\",\"generation\":1,\"members\":[],\"x\":\"".getBytes(StandardCharsets.UTF_8);
        byte[] suffix = "\"}".getBytes(StandardCharsets.UTF_8);
        byte[] raw = new byte[prefix.length + 1 + suffix.length];
        System.arraycopy(prefix, 0, raw, 0, prefix.length);
        raw[prefix.length] = (byte) 0xC3;
        System.arraycopy(suffix, 0, raw, prefix.length + 1, suffix.length);
        Files.write(temp.resolve("runtime-manifest.json"), raw);
        DetectionJobArguments args = DetectionJobArguments.managed(temp.toString(),
                "dg-" + "a".repeat(24), 1L, sha256(raw));

        assertThrows(IllegalStateException.class,
                () -> new RuntimeManifestVerifier().verify(temp, args, List.of()));
    }

    @Test
    void legacyVerificationIsExplicitAndSkipsManifestRead() {
        RuntimeManifestVerifier.Verification result = new RuntimeManifestVerifier().verify(
                temp, DetectionJobArguments.legacy(temp.toString()), List.of());

        assertTrue(result.legacy());
        assertEquals(java.util.Set.of(), result.ruleKeys());
    }

    private static RuleDecl rule(String id) {
        RuleDecl result = new RuleDecl();
        result.id = id;
        result.enabled = true;
        return result;
    }

    private static String sha256(byte[] bytes) throws Exception {
        return java.util.HexFormat.of().formatHex(
                java.security.MessageDigest.getInstance("SHA-256").digest(bytes));
    }
}
