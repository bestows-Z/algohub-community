package com.szpu.algohub.school;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import com.szpu.algohub.admin.console.AdminAccess;
import com.szpu.algohub.admin.console.AdminPermission;
import com.szpu.algohub.common.ApiException;

@Service
public class CohortLeaderAccess {
    private final JdbcTemplate db;
    private final AdminAccess admins;
    public CohortLeaderAccess(JdbcTemplate db,AdminAccess admins) { this.db=db; this.admins=admins; }

    /** Match existing administration/deletion paths: administrators first, then other IDs. */
    public void lockUsers(long actor,long target) {
        db.queryForList("select id from app_user where id in (?,?) order by case when role='ADMIN' then 0 else 1 end,id for no key update",actor,target);
    }

    public void requireManager(long actor,long school) {
        db.queryForList("select id from app_user where id=? for share",actor);
        var grant=admins.of(actor);
        if(grant.active() && grant.has(AdminPermission.SCHOOLS)) return;
        if(!grant.active() || grant.admin() || db.queryForList(
                "select user_id from school_administrator where user_id=? and school_id=? for share",actor,school).isEmpty())
            throw ApiException.forbidden("没有这所学校的负责人任免权限");
    }

    public void requireScope(long actor,long school,int year) {
        var user=db.queryForList("select role,status from app_user where id=? for share",actor);
        if(user.isEmpty() || !"STUDENT".equals(user.getFirst().get("role")) || !"ACTIVE".equals(user.getFirst().get("status"))
                || !db.queryForList("select user_id from school_administrator where user_id=?",actor).isEmpty())
            throw ApiException.forbidden("年级管理授权已失效，请联系学校管理员");
        var members=db.queryForList("select id from school_member where user_id=? and school_id=? and status='APPROVED' for share",actor,school);
        if(members.isEmpty() || db.queryForList("""
                select id from school_cohort_leader where user_id=? and school_id=? and enrollment_year=?
                    and member_id=? and revoked_at is null for share
                """,actor,school,year,members.getFirst().get("id")).isEmpty())
            throw ApiException.forbidden("年级管理授权已失效，请联系学校管理员");
    }
}
