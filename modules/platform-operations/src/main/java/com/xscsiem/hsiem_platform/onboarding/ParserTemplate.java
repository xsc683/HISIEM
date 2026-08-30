package com.xscsiem.hsiem_platform.onboarding;

import com.fasterxml.jackson.annotation.JsonAutoDetect;

import java.util.List;
import java.util.Map;

/**
 * 解析模板(用户接入层 Phase 4):一个模板 = 一个预置解析器。
 * 对应 infra/parser-templates/*.yaml,由 Jackson YAML 反序列化。
 */
@JsonAutoDetect(fieldVisibility = JsonAutoDetect.Visibility.ANY)
public class ParserTemplate {

    public String id;
    public String name;
    public String description;
    public String protocol;
    public String status;
    /** 固定补充的 ECS 字段,如 event.category。 */
    public Map<String, String> ecs;
    /** grok 模式数组(按序尝试)。 */
    public List<String> patterns;
    public Timestamp timestamp;
    /** 按消息内容补 event.action/outcome/type 等。 */
    public List<Action> actions;
    /** 正样本(须命中并匹配 expect 字段)。 */
    public List<Test> tests;
    /** 负样本(须不命中任何 grok 模式;story-09:顶层字段,置于 tests 之外)。 */
    public List<String> negative;

    @JsonAutoDetect(fieldVisibility = JsonAutoDetect.Visibility.ANY)
    public static class Timestamp {
        public String source;
        public List<String> formats;
        public String timezone;
    }

    @JsonAutoDetect(fieldVisibility = JsonAutoDetect.Visibility.ANY)
    public static class Action {
        /** 形如 "/Failed password/" 的匹配串(去斜杠后做正则 find)。 */
        public String match;
        public Map<String, String> fields;
    }

    @JsonAutoDetect(fieldVisibility = JsonAutoDetect.Visibility.ANY)
    public static class Test {
        public String sample;
        public Map<String, Object> expect;
    }
}
