package com.xscsiem.hsiem_platform.logsearch;

import java.util.List;

/** 可供检索页面生成条件控件的可信字段目录。 */
public record LogSearchCatalog(List<Field> fields, List<String> operators) {

    public record Field(String name, String label, String type, List<String> operators) {
    }
}
