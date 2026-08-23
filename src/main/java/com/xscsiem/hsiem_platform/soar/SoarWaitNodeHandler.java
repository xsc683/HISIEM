package com.xscsiem.hsiem_platform.soar;

import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.Set;

@Component
public class SoarWaitNodeHandler implements SoarNodeHandler {

    private static final long MAX_WAIT_MINUTES = Duration.ofDays(30).toMinutes();

    @Override
    public String type() {
        return "wait";
    }

    @Override
    public SoarNodeResult execute(SoarExecutionContext context, Map<String, Object> resolvedConfig) {
        if (context.nodeRun() != null && "waiting".equals(context.nodeRun().status())) {
            return SoarNodeResult.advance("next", Map.of("resumedAt", Instant.now().toString()));
        }
        long amount = Long.parseLong(text(resolvedConfig.get("amount")));
        String unit = text(resolvedConfig.get("unit"));
        Duration duration = "hours".equals(unit) ? Duration.ofHours(amount) : Duration.ofMinutes(amount);
        return SoarNodeResult.waitUntil(Instant.now().plus(duration));
    }

    @Override
    public void validate(String entryType, PlaybookGraph.Node node) {
        long amount;
        try {
            amount = Long.parseLong(text(node.config().get("amount")));
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException("等待时长必须是正整数");
        }
        String unit = text(node.config().get("unit"));
        if (amount < 1 || !Set.of("minutes", "hours").contains(unit)) {
            throw new IllegalArgumentException("等待仅支持正整数分钟或小时");
        }
        long minutes = "hours".equals(unit) ? Math.multiplyExact(amount, 60) : amount;
        if (minutes > MAX_WAIT_MINUTES) throw new IllegalArgumentException("单次等待不能超过 30 天");
    }

    private String text(Object value) {
        return value == null ? "" : String.valueOf(value).trim();
    }
}
