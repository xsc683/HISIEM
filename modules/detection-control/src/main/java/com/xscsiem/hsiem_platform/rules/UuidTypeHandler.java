package com.xscsiem.hsiem_platform.rules;

import java.sql.CallableStatement;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.UUID;
import org.apache.ibatis.type.BaseTypeHandler;
import org.apache.ibatis.type.JdbcType;

/** UUID mapping that works with both PostgreSQL's uuid and H2's UUID type. */
public final class UuidTypeHandler extends BaseTypeHandler<UUID> {
    @Override
    public void setNonNullParameter(
            PreparedStatement statement, int index, UUID value, JdbcType jdbcType)
            throws SQLException {
        statement.setObject(index, value);
    }

    @Override
    public UUID getNullableResult(ResultSet resultSet, String columnName) throws SQLException {
        return read(resultSet.getObject(columnName));
    }

    @Override
    public UUID getNullableResult(ResultSet resultSet, int columnIndex) throws SQLException {
        return read(resultSet.getObject(columnIndex));
    }

    @Override
    public UUID getNullableResult(CallableStatement statement, int columnIndex)
            throws SQLException {
        return read(statement.getObject(columnIndex));
    }

    private static UUID read(Object value) {
        if (value == null) return null;
        return value instanceof UUID uuid ? uuid : UUID.fromString(value.toString());
    }
}
