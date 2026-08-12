package com.analyzercoder.infrastructure.persistence.type;

import java.sql.CallableStatement;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.UUID;
import org.apache.ibatis.type.BaseTypeHandler;
import org.apache.ibatis.type.JdbcType;
import org.apache.ibatis.type.MappedJdbcTypes;
import org.apache.ibatis.type.MappedTypes;

/** 在 PostgreSQL UUID 与 Java UUID 之间进行显式转换，避免驱动推断差异。 */
@MappedTypes(UUID.class)
@MappedJdbcTypes(value = JdbcType.OTHER, includeNullJdbcType = true)
public class PostgresUuidTypeHandler extends BaseTypeHandler<UUID> {
    @Override
    public void setNonNullParameter(
            PreparedStatement statement, int index, UUID value, JdbcType jdbcType)
            throws SQLException {
        statement.setObject(index, value);
    }

    @Override
    public UUID getNullableResult(ResultSet resultSet, String column) throws SQLException {
        return uuid(resultSet.getObject(column));
    }

    @Override
    public UUID getNullableResult(ResultSet resultSet, int column) throws SQLException {
        return uuid(resultSet.getObject(column));
    }

    @Override
    public UUID getNullableResult(CallableStatement statement, int column) throws SQLException {
        return uuid(statement.getObject(column));
    }

    private static UUID uuid(Object value) {
        if (value == null) {
            return null;
        }
        return value instanceof UUID uuid ? uuid : UUID.fromString(value.toString());
    }
}
