package com.siem;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.apache.flink.streaming.api.functions.ProcessFunction;
import org.apache.flink.util.Collector;
import org.apache.flink.util.OutputTag;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;

/** Converts poison input records into an observable Kafka DLQ instead of restarting the Flink job. */
public class EventParsingProcessFunction extends ProcessFunction<String, Event> {

    public static final OutputTag<String> DLQ = new OutputTag<String>("siem-event-parser-dlq") { };
    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final int MAX_ORIGINAL_CHARS = 65_536;
    private static final int MAX_ERROR_CHARS = 2_048;

    @Override
    public void processElement(String value, Context context, Collector<Event> output) {
        ParseOutcome outcome = parse(value);
        if (outcome.event() != null) {
            output.collect(outcome.event());
        } else {
            context.output(DLQ, outcome.dlqRecord());
        }
    }

    static ParseOutcome parse(String value) {
        try {
            return new ParseOutcome(EventParser.parseEvent(value), null);
        } catch (Exception error) {
            return new ParseOutcome(null, dlq(value, error));
        }
    }

    private static String dlq(String original, Exception error) {
        String safeOriginal = original == null ? "" : original;
        boolean truncated = safeOriginal.length() > MAX_ORIGINAL_CHARS;
        if (truncated) safeOriginal = safeOriginal.substring(0, MAX_ORIGINAL_CHARS);
        String errorMessage = error.getMessage() == null ? error.getClass().getSimpleName() : error.getMessage();
        if (errorMessage.length() > MAX_ERROR_CHARS) errorMessage = errorMessage.substring(0, MAX_ERROR_CHARS);
        Map<String, Object> record = new LinkedHashMap<>();
        record.put("@timestamp", Instant.now().toString());
        record.put("dlq.id", sha256(original == null ? "<null>" : original));
        record.put("dlq.stage", "flink.event-parser");
        record.put("dlq.error_type", error.getClass().getName());
        record.put("dlq.error_message", errorMessage);
        record.put("event.original", safeOriginal);
        record.put("event.original_truncated", truncated);
        try {
            return MAPPER.writeValueAsString(record);
        } catch (Exception serializationError) {
            throw new IllegalStateException("无法序列化 Flink DLQ 记录", serializationError);
        }
    }

    private static String sha256(String value) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(StandardCharsets.UTF_8));
            StringBuilder result = new StringBuilder(digest.length * 2);
            for (byte item : digest) result.append(String.format("%02x", item));
            return result.toString();
        } catch (Exception error) {
            throw new IllegalStateException("SHA-256 不可用", error);
        }
    }

    record ParseOutcome(Event event, String dlqRecord) { }
}
