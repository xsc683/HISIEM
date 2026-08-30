package com.siem;

import java.util.Map;
import java.util.Objects;
import java.util.regex.Pattern;

/**
 * Immutable command-line contract for one managed detection job.
 *
 * <p>The first argument is the rules directory.  Managed jobs must also carry the stable job key,
 * generation and the SHA-256 of the raw runtime manifest as arguments 1-3.  A one-argument launch
 * remains supported for pre-5B jobs and is explicitly marked as legacy.</p>
 */
public record DetectionJobArguments(
        String rulesDir,
        String jobKey,
        long generation,
        String manifestHash,
        boolean legacy) {

    private static final Pattern JOB_KEY = Pattern.compile("dg-[0-9a-f]{24}");
    private static final Pattern HASH = Pattern.compile("[0-9a-f]{64}");

    public DetectionJobArguments {
        rulesDir = required(rulesDir, "rulesDir");
        if (legacy) {
            if (jobKey != null || manifestHash != null || generation != 0L) {
                throw new IllegalArgumentException("legacy arguments cannot carry managed identity");
            }
        } else {
            validateJobKey(jobKey);
            if (generation < 0L) {
                throw new IllegalArgumentException("generation must be non-negative");
            }
            validateManifestHash(manifestHash);
        }
    }

    public static DetectionJobArguments parse(String[] args) {
        return parse(args, System.getenv());
    }

    /** Parse arguments with an injectable environment map for deterministic tests. */
    public static DetectionJobArguments parse(String[] args, Map<String, String> environment) {
        Objects.requireNonNull(args, "args must not be null");
        Objects.requireNonNull(environment, "environment must not be null");
        String rulesDir = args.length > 0 ? args[0]
                : environment.getOrDefault("SIEM_RULES_DIR", "/opt/flink/rules");
        if (args.length != 0 && args.length != 1 && args.length != 4) {
            throw new IllegalArgumentException(
                    "managed DetectionJob arguments must be <rulesDir> <jobKey> <generation> <manifestHash>");
        }
        if (args.length == 4) {
            return managed(rulesDir, args[1], args[2], args[3]);
        }

        // Environment variables are an explicit compatibility bridge for launchers that cannot
        // append arguments.  Partial managed identity is rejected rather than silently downgraded.
        String key = environment.get("SIEM_JOB_KEY");
        String generation = environment.get("SIEM_JOB_GENERATION");
        String hash = environment.get("SIEM_MANIFEST_HASH");
        boolean anyManaged = key != null || generation != null || hash != null;
        if (anyManaged) {
            if (key == null || generation == null || hash == null) {
                throw new IllegalArgumentException("SIEM_JOB_KEY, SIEM_JOB_GENERATION and SIEM_MANIFEST_HASH must be supplied together");
            }
            return managed(rulesDir, key, generation, hash);
        }
        return legacy(rulesDir);
    }

    public static DetectionJobArguments managed(String rulesDir, String jobKey,
                                                 String generation, String manifestHash) {
        long parsedGeneration;
        try {
            if (generation == null || !generation.matches("[0-9]+")) {
                throw new NumberFormatException("not a non-negative decimal integer");
            }
            parsedGeneration = Long.parseLong(generation);
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException("generation must be a non-negative decimal integer", e);
        }
        return managed(rulesDir, jobKey, parsedGeneration, manifestHash);
    }

    public static DetectionJobArguments managed(String rulesDir, String jobKey,
                                                 long generation, String manifestHash) {
        validateJobKey(jobKey);
        if (generation < 0L) {
            throw new IllegalArgumentException("generation must be non-negative");
        }
        validateManifestHash(manifestHash);
        return new DetectionJobArguments(rulesDir, jobKey, generation,
                manifestHash.toLowerCase(java.util.Locale.ROOT), false);
    }

    public static DetectionJobArguments legacy(String rulesDir) {
        return new DetectionJobArguments(rulesDir, null, 0L, null, true);
    }

    public boolean managed() {
        return !legacy;
    }

    public static void validateJobKey(String jobKey) {
        if (jobKey == null || !JOB_KEY.matcher(jobKey).matches()) {
            throw new IllegalArgumentException("jobKey must match dg-[0-9a-f]{24}");
        }
    }

    public static void validateManifestHash(String manifestHash) {
        if (manifestHash == null || !HASH.matcher(manifestHash.toLowerCase(java.util.Locale.ROOT)).matches()) {
            throw new IllegalArgumentException("manifestHash must be 64 lowercase hexadecimal characters");
        }
    }

    private static String required(String value, String field) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(field + " must not be blank");
        }
        return value;
    }
}
