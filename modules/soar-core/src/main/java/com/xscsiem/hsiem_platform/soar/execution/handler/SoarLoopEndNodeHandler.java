package com.xscsiem.hsiem_platform.soar.execution.handler;

import com.xscsiem.hsiem_platform.soar.SoarExecutionContext;
import com.xscsiem.hsiem_platform.soar.SoarNodeHandler;
import com.xscsiem.hsiem_platform.soar.SoarNodeResult;
import org.springframework.stereotype.Component;

import java.util.Map;

/** Marker node; the store intercepts it to persist the next loop iteration. */
@Component
public class SoarLoopEndNodeHandler implements SoarNodeHandler {

    @Override
    public String type() {
        return "loop_end";
    }

    @Override
    public SoarNodeResult execute(SoarExecutionContext context, Map<String, Object> resolvedConfig) {
        return SoarNodeResult.advance("next", Map.of("loopBodyCompleted", true));
    }
}
