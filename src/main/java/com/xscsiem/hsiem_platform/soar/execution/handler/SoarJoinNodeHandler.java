package com.xscsiem.hsiem_platform.soar.execution.handler;

import com.xscsiem.hsiem_platform.soar.SoarExecutionContext;
import com.xscsiem.hsiem_platform.soar.SoarNodeHandler;
import com.xscsiem.hsiem_platform.soar.SoarNodeResult;
import org.springframework.stereotype.Component;

import java.util.Map;

/** Join nodes are normally consumed by the store when the final branch arrives. */
@Component
public class SoarJoinNodeHandler implements SoarNodeHandler {

    @Override
    public String type() {
        return "join";
    }

    @Override
    public SoarNodeResult execute(SoarExecutionContext context, Map<String, Object> resolvedConfig) {
        return SoarNodeResult.advance("next", Map.of("joined", true));
    }
}
