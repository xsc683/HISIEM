package com.xscsiem.hsiem_platform.logsearch;

import java.util.List;

/** 前端日志检索请求。所有字段和操作符都由服务端白名单解释，不接收 Elasticsearch DSL。 */
public record LogSearchRequest(
        String from,
        String to,
        Integer page,
        Integer size,
        String sort,
        String logic,
        List<Condition> conditions) {

    public record Condition(String field, String operator, Object value) {
    }
}
