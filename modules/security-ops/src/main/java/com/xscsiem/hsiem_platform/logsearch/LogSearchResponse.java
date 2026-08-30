package com.xscsiem.hsiem_platform.logsearch;

import java.util.List;
import java.util.Map;

/** 日志检索结果。from/to 是服务端实际采用的 UTC 时间边界。 */
public record LogSearchResponse(
        List<Map<String, Object>> items,
        int page,
        int size,
        long total,
        long tookMs,
        String from,
        String to) {
}
