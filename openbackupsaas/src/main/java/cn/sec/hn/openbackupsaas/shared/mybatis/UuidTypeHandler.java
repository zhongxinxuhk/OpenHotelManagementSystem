package cn.sec.hn.openbackupsaas.shared.mybatis;

import org.apache.ibatis.type.BaseTypeHandler;
import org.apache.ibatis.type.JdbcType;
import org.apache.ibatis.type.MappedJdbcTypes;
import org.apache.ibatis.type.MappedTypes;

import java.sql.CallableStatement;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.UUID;

/**
 * uuid 列的类型处理器。
 *
 * <p>为什么必须自己写：MyBatis 3.5.19 起**不再内置** {@code UUIDTypeHandler}
 * （jar 内已无该类），而本项目主键与 tenant_id 全部是 uuid。
 * 不注册时结果映射会直接抛 {@code No typehandler found for property null}，
 * 参数绑定则退化为未知类型处理。
 *
 * <p>写入使用 {@code setObject}：PostgreSQL JDBC 会把 {@link UUID} 直接映射为 uuid 类型，
 * 无需转字符串再隐式转换。读取兼容驱动返回 UUID 或字符串两种情况。
 */
@MappedTypes(UUID.class)
@MappedJdbcTypes(JdbcType.OTHER)
public class UuidTypeHandler extends BaseTypeHandler<UUID> {

    @Override
    public void setNonNullParameter(PreparedStatement ps, int i, UUID parameter, JdbcType jdbcType)
            throws SQLException {
        ps.setObject(i, parameter);
    }

    @Override
    public UUID getNullableResult(ResultSet rs, String columnName) throws SQLException {
        return toUuid(rs.getObject(columnName));
    }

    @Override
    public UUID getNullableResult(ResultSet rs, int columnIndex) throws SQLException {
        return toUuid(rs.getObject(columnIndex));
    }

    @Override
    public UUID getNullableResult(CallableStatement cs, int columnIndex) throws SQLException {
        return toUuid(cs.getObject(columnIndex));
    }

    private static UUID toUuid(Object value) {
        if (value == null) {
            return null;
        }
        if (value instanceof UUID uuid) {
            return uuid;
        }
        return UUID.fromString(value.toString());
    }
}
