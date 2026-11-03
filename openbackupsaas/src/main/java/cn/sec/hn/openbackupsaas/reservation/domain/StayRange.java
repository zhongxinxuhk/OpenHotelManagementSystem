package cn.sec.hn.openbackupsaas.reservation.domain;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * 住宿区间（半开 {@code [checkIn, checkOut)}）。
 *
 * <p>为什么是半开：退房日的白天仍可卖给下一单——
 * "今天退房"与"今天入住"必须能共存，闭区间会把相邻两单判成冲突。
 * 这与数据库里 {@code daterange} 的规范形式、以及占用排斥约束的语义一致。
 *
 * <p>约束（与迁移 V4 的 CHECK 对齐，服务层再判一次只为给出可读错误）：
 * 非空、有界、入住日早于离店日。
 */
public record StayRange(LocalDate checkIn, LocalDate checkOut) {

    public StayRange {
        Objects.requireNonNull(checkIn, "入住日不能为空");
        Objects.requireNonNull(checkOut, "离店日不能为空");
        if (!checkOut.isAfter(checkIn)) {
            throw new IllegalArgumentException("离店日必须晚于入住日：" + checkIn + " → " + checkOut);
        }
    }

    public static StayRange of(LocalDate checkIn, LocalDate checkOut) {
        return new StayRange(checkIn, checkOut);
    }

    /** PostgreSQL {@code daterange} 的规范文本形式。 */
    public String toPgLiteral() {
        return "[" + checkIn + "," + checkOut + ")";
    }

    /** 解析 PG 返回的 {@code daterange} 文本；只接受规范半开形式，避免静默接受闭区间。 */
    public static StayRange parse(String literal) {
        if (literal == null || literal.isBlank()) {
            throw new IllegalArgumentException("日期区间不能为空");
        }
        String text = literal.trim();
        if (!text.startsWith("[") || !text.endsWith(")")) {
            throw new IllegalArgumentException("日期区间必须是半开形式 [入住日,离店日)：" + literal);
        }
        String body = text.substring(1, text.length() - 1);
        int comma = body.indexOf(',');
        if (comma <= 0) {
            throw new IllegalArgumentException("日期区间格式不合法：" + literal);
        }
        return new StayRange(LocalDate.parse(body.substring(0, comma).trim()),
                LocalDate.parse(body.substring(comma + 1).trim()));
    }

    /** 相邻：前一单的离店日 == 后一单的入住日，可续住，不冲突。 */
    public boolean isAdjacentTo(StayRange other) {
        return checkOut.equals(other.checkIn()) || other.checkOut().equals(checkIn);
    }

    /** 逐晚展开：半开区间的每一天，用于按"房间 + 住宿日"写库存流水。 */
    public List<LocalDate> dates() {
        List<LocalDate> dates = new ArrayList<>();
        for (LocalDate date = checkIn; date.isBefore(checkOut); date = date.plusDays(1)) {
            dates.add(date);
        }
        return List.copyOf(dates);
    }

    public int nights() {
        return (int) java.time.temporal.ChronoUnit.DAYS.between(checkIn, checkOut);
    }
}
