package com.szpu.algohub.weeklyreport;

import static com.szpu.algohub.weeklyreport.ReportTime.*;
import java.time.*;
import java.util.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import com.szpu.algohub.common.ApiException;
import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.databind.JsonNode;

@Service
@Transactional
public class ReportSettingsService {
    private final JdbcTemplate db;
    private final ReportAccess access;
    private final ReportCycleService cycles;
    private final ReportTime clock;
    public ReportSettingsService(JdbcTemplate db,ReportAccess access,ReportCycleService cycles,ReportTime clock) {
        this.db=db;this.access=access;this.cycles=cycles;this.clock=clock;
    }
    public record Input(boolean enabled,int dayOfWeek,String time,int leadHours,boolean emailEnabled,int version,boolean confirmDisable) {
        @JsonCreator(mode=JsonCreator.Mode.DELEGATING)
        public static Input read(JsonNode node) {
            if(!node.isObject() || !node.path("time").isTextual()) throw ApiException.validation("请填写完整统计配置");
            return new Input(bool(node,"enabled"),integer(node,"dayOfWeek"),node.get("time").asText(),integer(node,"leadHours"),
                bool(node,"emailEnabled"),integer(node,"version"),node.has("confirmDisable") && bool(node,"confirmDisable"));
        }
        private static int integer(JsonNode node,String key) {
            if(!node.path(key).isIntegralNumber() || !node.path(key).canConvertToInt()) throw ApiException.validation("统计日期、提前小时及版本须为整数");
            return node.get(key).intValue();
        }
        private static boolean bool(JsonNode node,String key) {
            if(!node.path(key).isBoolean()) throw ApiException.validation("开关参数无效");
            return node.get(key).booleanValue();
        }
    }
    private void ensure(long school) { db.update("insert into weekly_report_setting(school_id) values (?) on conflict do nothing",school); }
    public Map<String,Object> get(long actor,long school) {
        access.manager(actor,school);ensure(school);
        var row=db.queryForMap("select * from weekly_report_setting where school_id=?",school);Instant now=clock.now();LocalDate week=week(now);
        var current=cycles.rule(school,week);var next=cycles.rule(school,week.plusWeeks(1));
        var out=new LinkedHashMap<String,Object>();out.put("enabled",row.get("enabled"));out.put("version",row.get("version"));
        out.put("configured",current!=null);out.put("current",view(current));out.put("next",view(next));out.put("timezone","Asia/Shanghai");
        out.put("nextCutoffAt",null);out.put("nextReminderAt",null);
        if(Boolean.TRUE.equals(row.get("enabled")) && current!=null) {
            Instant cutoff=cycles.cutoff(week,current);
            if(!cutoff.isAfter(now)) {week=week.plusWeeks(1);current=next;cutoff=cycles.cutoff(week,current);}
            out.put("nextCutoffAt",cutoff);
            for(int offset=0;offset<=1;offset++) {
                LocalDate reminderWeek=ReportTime.week(now).plusWeeks(offset);var reminderRule=cycles.rule(school,reminderWeek);
                if(reminderRule==null)continue;
                int lead=((Number)reminderRule.get("lead_hours")).intValue();Instant remind=cycles.cutoff(reminderWeek,reminderRule).minusSeconds(lead*3600L);
                if(lead>0 && remind.isAfter(now) && !remind.isBefore(instant(row.get("enabled_since")))) {out.put("nextReminderAt",remind);break;}
            }
        }
        return out;
    }
    private Map<String,Object> view(Map<String,Object> rule) {
        if(rule==null) return Map.of("dayOfWeek",7,"time","22:00","leadHours",6,"emailEnabled",true);
        return Map.of("effectiveWeek",rule.get("effective_week"),"dayOfWeek",rule.get("day_of_week"),
            "time",LocalTime.MIN.plusMinutes(((Number)rule.get("minute_of_day")).intValue()).toString(),"leadHours",rule.get("lead_hours"),"emailEnabled",rule.get("email_enabled"));
    }
    public Map<String,Object> save(long actor,long school,Input input) {
        access.manager(actor,school);ensure(school);
        var old=db.queryForMap("select * from weekly_report_setting where school_id=? for update",school);
        access.manager(actor,school);WeeklyReportService.version(old,input.version());
        if(Boolean.TRUE.equals(old.get("enabled")) && !input.enabled() && !input.confirmDisable()) throw ApiException.validation("关闭后停止后续统计和提醒，请确认关闭");
        LocalTime time;
        try { time=LocalTime.parse(input.time()); if(time.getSecond()!=0 || time.getNano()!=0) throw new IllegalArgumentException(); }
        catch(Exception e) { throw ApiException.validation("请选择有效的小时和分钟"); }
        int minutes=time.getHour()*60+time.getMinute();
        if(input.dayOfWeek()<1 || input.dayOfWeek()>7 || input.leadHours()<0 || input.leadHours()>167
            || input.leadHours()*60>(input.dayOfWeek()-1)*1440+minutes) throw ApiException.validation("提醒时间不能早于本周一，提前小时须为非负整数");
        Instant now=clock.now();LocalDate effective=week(now).plusWeeks(old.get("enabled_since")==null?0:1);
        db.update("""
            insert into weekly_report_rule(school_id,effective_week,day_of_week,minute_of_day,lead_hours,email_enabled)
            values (?,?,?,?,?,?) on conflict(school_id,effective_week) do update set day_of_week=excluded.day_of_week,
            minute_of_day=excluded.minute_of_day,lead_hours=excluded.lead_hours,email_enabled=excluded.email_enabled
            """,school,effective,input.dayOfWeek(),minutes,input.leadHours(),input.emailEnabled());
        db.update("""
            update weekly_report_setting set enabled=?,version=version+1,
            enabled_since=case when ? and not enabled then ? else enabled_since end where school_id=?
            """,input.enabled(),input.enabled(),sql(now),school);
        if(!input.enabled()) {
            db.update("""
                update study_mail_outbox o set status='SKIPPED',last_error='学校已关闭周报提醒',lease_token=null,lease_until=null
                from weekly_report_reminder n join weekly_report_cycle c on c.id=n.cycle_id
                where o.notification_id=n.notification_id and c.school_id=? and o.status in ('PENDING','SENDING')
                """,school);
            db.update("update weekly_report_cycle set status='CANCELLED',error='学校已关闭统计' where school_id=? and status in ('PENDING','FAILED')",school);
        } else cycles.ensureCycle(school,week(now),now);
        access.audit(actor,"WEEKLY_REPORT_SETTINGS","school="+school+",enabled="+input.enabled()+",effective="+effective);
        return get(actor,school);
    }
    public Map<String,Object> availability(long actor,long school,Integer year) {
        boolean manager=access.scope(actor,school,year);
        boolean enabled=Boolean.TRUE.equals(db.queryForObject("select coalesce((select enabled from weekly_report_setting where school_id=?),false)",Boolean.class,school));
        return Map.of("manager",manager,"statisticsEnabled",enabled);
    }
}
