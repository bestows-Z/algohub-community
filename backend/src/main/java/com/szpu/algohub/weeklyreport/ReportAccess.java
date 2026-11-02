package com.szpu.algohub.weeklyreport;

import java.util.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import com.szpu.algohub.admin.console.*;
import com.szpu.algohub.school.CohortLeaderAccess;
import com.szpu.algohub.common.ApiException;

@Service
public class ReportAccess {
    private final JdbcTemplate db;
    private final AdminAccess admins;
    private final CohortLeaderAccess cohorts;
    public ReportAccess(JdbcTemplate db,AdminAccess admins,CohortLeaderAccess cohorts) {
        this.db=db;this.admins=admins;this.cohorts=cohorts;
    }
    public boolean isManager(long actor,long school) {
        var grant=admins.of(actor);
        return grant.active() && (grant.has(AdminPermission.SCHOOLS) || (!grant.admin() && !db.queryForList(
            "select user_id from school_administrator where user_id=? and school_id=?",actor,school).isEmpty()));
    }
    public void manager(long actor,long school) { cohorts.requireManager(actor,school); }
    /** Return true only for the full-school view. Specifying a year never widens a captain scope. */
    public boolean scope(long actor,long school,Integer year) {
        if(isManager(actor,school)) { manager(actor,school); return true; }
        if(year==null) throw ApiException.forbidden("请选择有效的负责年级");
        cohorts.requireScope(actor,school,year);return false;
    }
    public Map<String,Object> student(long actor) {
        var rows=db.queryForList("""
            select m.*,s.name as "schoolName" from school_member m join school s on s.id=m.school_id
            join app_user u on u.id=m.user_id where m.user_id=? and m.status='APPROVED'
            and u.status='ACTIVE' and u.role='STUDENT'
            and not exists(select 1 from school_administrator a where a.user_id=u.id)
            """,actor);
        if(rows.isEmpty()) throw ApiException.forbidden("需要有效的学校学生身份才能提交周报");
        return rows.getFirst();
    }
    public void audit(long actor,String event,String detail) {
        db.update("insert into auth_event(user_id,event,detail) values (?,?,?)",actor,event,detail);
    }
}
