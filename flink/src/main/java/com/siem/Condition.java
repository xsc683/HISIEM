package com.siem;

import java.io.Serializable;
import java.util.Map;

/**
 * 规则条件:对解析后的事件字段做判定。
 * 后续可扩展 FieldRegex / FieldIn / 时间窗口聚合条件等。
 */
public interface Condition extends Serializable {

    boolean matches(Map<String, Object> event);
}
