package com.szpu.algohub.weeklyreport;

import java.time.*;
import org.springframework.stereotype.Component;
import com.szpu.algohub.common.*;

@Component
public class ReportTime {
    public Instant now() { return Instant.now(); }
    public static LocalDate week(Instant time) { return TimeSupport.campusDate(time).with(DayOfWeek.MONDAY); }
    public static LocalDate parseWeek(String value) {
        try {
            LocalDate date=LocalDate.parse(value);
            if(date.getDayOfWeek()!=DayOfWeek.MONDAY) throw new IllegalArgumentException();
            return date;
        } catch(Exception e) { throw ApiException.validation("周次须填写该周周一日期"); }
    }
    public static java.time.OffsetDateTime sql(Instant value) { return value==null?null:value.atOffset(ZoneOffset.UTC); }
    public static Instant instant(Object value) {
        if(value instanceof java.sql.Timestamp t) return t.toInstant();
        if(value instanceof OffsetDateTime t) return t.toInstant();
        return (Instant)value;
    }
}
