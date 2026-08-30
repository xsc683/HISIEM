package com.xscsiem.hsiem_platform.logsearch;

/** Elasticsearch 当前不可用或拒绝了由服务端生成的查询。 */
public class LogSearchUnavailableException extends RuntimeException {

    public LogSearchUnavailableException(String message) {
        super(message);
    }

    public LogSearchUnavailableException(String message, Throwable cause) {
        super(message, cause);
    }
}
