package com.xscsiem.hsiem_platform.detection.runtime;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Locale;
import java.util.Objects;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Stable, user-string-free identity and structured Flink job-name codec. */
public final class DetectionJobNameCodec {

    private static final Pattern NAME = Pattern.compile(
            "^SIEM-DETECTION-(dg-[0-9a-f]{24})-g([0-9]+)-m([0-9a-f]{64})$");
    private static final Pattern JOB_KEY = Pattern.compile("dg-[0-9a-f]{24}");
    private static final Pattern HASH = Pattern.compile("[0-9a-f]{64}");

    public String jobKey(String tenantId, String targetCluster, String groupKey) {
        String input = part(tenantId, "tenantId") + part(targetCluster, "targetCluster")
                + part(groupKey, "groupKey");
        return "dg-" + sha256(input).substring(0, 24);
    }

    public static String stableJobKey(String tenantId, String targetCluster, String groupKey) {
        return new DetectionJobNameCodec().jobKey(tenantId, targetCluster, groupKey);
    }

    public String encode(String jobKey, long generation, String manifestHash) {
        validateJobKey(jobKey);
        if (generation < 0) throw new IllegalArgumentException("generation must be non-negative");
        validateHash(manifestHash);
        return "SIEM-DETECTION-" + jobKey + "-g" + generation + "-m"
                + manifestHash.toLowerCase(Locale.ROOT);
    }

    public static String jobName(String jobKey, long generation, String manifestHash) {
        return new DetectionJobNameCodec().encode(jobKey, generation, manifestHash);
    }

    public JobIdentity decode(String name) {
        if (name == null) throw new IllegalArgumentException("job name must not be null");
        Matcher matcher = NAME.matcher(name.trim());
        if (!matcher.matches()) {
            throw new IllegalArgumentException("unrecognised managed detection job name");
        }
        long generation;
        try {
            generation = Long.parseLong(matcher.group(2));
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException("job generation is invalid", e);
        }
        return new JobIdentity(matcher.group(1), generation, matcher.group(3));
    }

    public static JobIdentity parse(String name) {
        return new DetectionJobNameCodec().decode(name);
    }

    public static void validateJobKey(String value) {
        if (value == null || !JOB_KEY.matcher(value).matches()) {
            throw new IllegalArgumentException("jobKey must match dg-[0-9a-f]{24}");
        }
    }

    public static void validateHash(String value) {
        if (value == null || !HASH.matcher(value.toLowerCase(Locale.ROOT)).matches()) {
            throw new IllegalArgumentException("manifestHash must be 64 hexadecimal characters");
        }
    }

    private static String part(String value, String field) {
        Objects.requireNonNull(value, field + " must not be null");
        byte[] bytes = value.getBytes(StandardCharsets.UTF_8);
        return bytes.length + ":" + value + "|";
    }

    private static String sha256(String value) {
        try {
            return java.util.HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 is unavailable", e);
        }
    }

    public record JobIdentity(String jobKey, long generation, String manifestHash) {
        public JobIdentity {
            validateJobKey(jobKey);
            if (generation < 0) throw new IllegalArgumentException("generation must be non-negative");
            validateHash(manifestHash);
            manifestHash = manifestHash.toLowerCase(Locale.ROOT);
        }
    }
}
