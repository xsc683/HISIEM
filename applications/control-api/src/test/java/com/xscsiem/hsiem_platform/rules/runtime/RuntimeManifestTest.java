package com.xscsiem.hsiem_platform.rules.runtime;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RuntimeManifestTest {

    private final RuntimeManifestCodec codec = new RuntimeManifestCodec();

    @Test
    void memberOrderIsCanonicalAndProducesStableJsonAndHash() {
        RuntimeManifest first = manifest(List.of(member("rule-b", 2, "hash-b"),
                member("rule-a", 1, "hash-a")));
        RuntimeManifest second = manifest(List.of(member("rule-a", 1, "hash-a"),
                member("rule-b", 2, "hash-b")));

        assertEquals(List.of("rule-a", "rule-b"), first.members().stream()
                .map(RuntimeManifest.Member::ruleKey).toList());
        assertEquals(codec.encode(first), codec.encode(second));
        assertEquals(codec.specHash(first), codec.specHash(second));
        RuntimeManifest observed = new RuntimeManifest(first.schemaVersion(), first.tenantId(),
                first.targetCluster(), first.jobGroupKey(), first.generation(), "job-1", "key-1",
                first.members());
        assertEquals(codec.specHash(first), codec.specHash(observed));
        assertFalse(codec.canonicalSpecJson(observed).contains("jobId"));
        assertFalse(codec.canonicalSpecJson(observed).contains("jobKey"));
        assertEquals(first.canonicalRepresentation(), observed.canonicalRepresentation());
        assertEquals("44136fa355b3678a1146ad16f7e8649e94fb4fc21fe77e8310c060f61caaff8a",
                codec.sha256("{}"));
        assertTrue(codec.encode(first).indexOf("rule-a") < codec.encode(first).indexOf("rule-b"));
    }

    @Test
    void constructorRejectsDuplicateMembersAndNegativeGeneration() {
        assertThrows(IllegalArgumentException.class, () -> manifest(List.of(
                member("rule-a", 1, "hash-a"), member("rule-a", 2, "hash-b"))));
        assertThrows(IllegalArgumentException.class, () -> new RuntimeManifest(
                RuntimeManifest.SCHEMA_VERSION, "tenant-a", "cluster-a", "group-a", -1,
                List.of(member("rule-a", 1, "hash-a"))));
    }

    @Test
    void codecRejectsUnknownSchemaVersionAndRoundTripsObservedJobFields() {
        RuntimeManifest observed = new RuntimeManifest("v1", "tenant-a", "cluster-a", "group-a",
                3, "job-123", "job-key", List.of(member("rule-a", 1, "hash-a")));
        RuntimeManifest decoded = codec.decode(codec.encode(observed));
        assertEquals(observed, decoded);

        assertThrows(IllegalArgumentException.class, () -> codec.encode(new RuntimeManifest(
                "2", "tenant-a", "cluster-a", "group-a", 1,
                List.of(member("rule-a", 1, "hash-a")))));
        assertThrows(IllegalArgumentException.class, () -> codec.decode(
                codec.encode(observed) + "{}"));
    }

    @Test
    void diffSeparatesMissingOutdatedAndUnexpectedAndReportsGeneration() {
        RuntimeManifest expected = manifest(List.of(member("rule-a", 1, "hash-a"),
                member("rule-b", 1, "hash-b")));
        RuntimeManifest observed = new RuntimeManifest(RuntimeManifest.SCHEMA_VERSION,
                "tenant-a", "cluster-a", "group-a", 2, null, null,
                List.of(member("rule-a", 2, "hash-new"), member("rule-c", 1, "hash-c")));

        RuntimeDiff diff = RuntimeDiff.compare(expected, observed);
        assertEquals(List.of("rule-b"), diff.missingRuleKeys());
        assertEquals(List.of("rule-c"), diff.unexpectedRuleKeys());
        assertEquals(List.of("rule-a"), diff.outdatedRuleKeys());
        RuntimeDiff.Outdated outdated = diff.outdated().getFirst();
        assertTrue(outdated.revisionMismatch());
        assertTrue(outdated.planHashMismatch());
        assertTrue(outdated.generationMismatch());
        assertFalse(diff.isEmpty());
    }

    private static RuntimeManifest manifest(List<RuntimeManifest.Member> members) {
        return new RuntimeManifest(RuntimeManifest.SCHEMA_VERSION, "tenant-a", "cluster-a",
                "group-a", 1, members);
    }

    private static RuntimeManifest.Member member(String key, long revision, String hash) {
        return new RuntimeManifest.Member(key, revision, hash);
    }
}
