package cn.sec.hn.openbackupsaas.shared.mybatis;

import cn.sec.hn.openbackupsaas.reservation.domain.StayRange;
import org.apache.ibatis.type.BaseTypeHandler;
import org.apache.ibatis.type.JdbcType;

import java.sql.CallableStatement;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;

/**
 * {@code daterange} ↔ {@link StayRange}（S1-05）。
 *
 * <p>MyBatis 既不内置 daterange 处理器，也不能把区间拆成两个日期列：
 * 区间是数据库 EXCLUDE 排斥约束的作用对象，必须作为**一个列**落库，否则约束失效。
 *
 * <p>参数以文本绑定，SQL 侧必须写成 {@code #{xxx}::daterange} 显式转换
 * （见 {@code ReservationMapper.xml}），这样不需要在编译期依赖 PostgreSQL 驱动
 * ——驱动保持 {@code runtime} 作用域，不污染编译类路径。
 */
public class DateRangeTypeHandler extends BaseTypeHandler<StayRange> {

    @Override
    public void setNonNullParameter(PreparedStatement ps, int i, StayRange parameter, JdbcType jdbcType)
            throws SQLException {
        ps.setString(i, parameter.toPgLiteral());
    }

    @Override
    public StayRange getNullableResult(ResultSet rs, String columnName) throws SQLException {
        return from(rs.getString(columnName));
    }

    @Override
    public StayRange getNullableResult(ResultSet rs, int columnIndex) throws SQLException {
        return from(rs.getString(columnIndex));
    }

    @Override
    public StayRange getNullableResult(CallableStatement cs, int columnIndex) throws SQLException {
        return from(cs.getString(columnIndex));
    }

    /**
     * 读取时把列当文本取：PG 返回的 {@code daterange} 文本就是规范形式 {@code [a,b)}，
     * 不需要额外转换，也不需要驱动类。
     */
    public static JdbcType jdbcType() {
        return JdbcType.VARCHAR;
    }

    private static StayRange from(String literal) {
        return literal == null ? null : StayRange.parse(literal);
    }
}
