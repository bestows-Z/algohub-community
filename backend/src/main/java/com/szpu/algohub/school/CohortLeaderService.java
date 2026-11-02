package com.szpu.algohub.school;

import java.util.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import com.szpu.algohub.common.ApiException;

@Service @Transactional
public class CohortLeaderService {
    private final JdbcTemplate db;
    private final CohortLeaderMapper mapper;
    private final CohortLeaderAccess access;
    private final SchoolService schools;
    public CohortLeaderService(JdbcTemplate db,CohortLeaderMapper mapper,CohortLeaderAccess access,SchoolService schools) {
        this.db=db; this.mapper=mapper; this.access=access; this.schools=schools;
    }
    public List<Map<String,Object>> scopes(long actor) { return mapper.scopes(actor); }
    public List<Map<String,Object>> grants(long actor,long school,Integer year) {
        access.requireManager(actor,school); return mapper.grants(school,year);
    }
    public List<Map<String,Object>> candidates(long actor,long school,String query,Integer year) {
        access.requireManager(actor,school);
        if(query.length()>100) throw ApiException.validation("搜索内容最多 100 字");
        return mapper.candidates(school,query.trim(),year);
    }
    public Map<String,Object> grant(long actor,long school,long user,int year) {
        access.lockUsers(actor,user); access.requireManager(actor,school);
        var members=db.queryForList("""
            select m.id from school_member m join app_user u on u.id=m.user_id
            where m.user_id=? and m.school_id=? and m.status='APPROVED' and m.enrollment_year is not null
              and u.role='STUDENT' and u.status='ACTIVE'
              and not exists(select 1 from school_administrator sa where sa.user_id=u.id)
            for update of m
            """,user,school);
        if(user==actor || members.isEmpty()) throw ApiException.validation("请选择本校已审核的正常普通学生");
        if(year<2000 || year>2100 || !Boolean.TRUE.equals(db.queryForObject("""
            select exists(select 1 from study_goal_default where enrollment_year=? and enabled)
                or exists(select 1 from school_member where school_id=? and enrollment_year=?)
            """,Boolean.class,year,school,year))) throw ApiException.validation("请选择本校已有或当前开放的入学年级");
        var inserted=db.queryForList("""
            insert into school_cohort_leader(school_id,enrollment_year,user_id,member_id,granted_by)
            values (?,?,?,?,?) on conflict(school_id,enrollment_year,user_id) where revoked_at is null do nothing returning id
            """,school,year,user,members.getFirst().get("id"),actor);
        if(!inserted.isEmpty()) mapper.audit(actor,"COHORT_LEADER_GRANTED","grantId="+inserted.getFirst().get("id")+",schoolId="+school+",year="+year+",targetUserId="+user);
        return mapper.grants(school,year).stream().filter(g->((Number)g.get("userId")).longValue()==user).findFirst().orElseThrow();
    }
    public void revoke(long actor,long school,long id) {
        var rows=db.queryForList("select user_id from school_cohort_leader where id=? and school_id=?",id,school);
        access.lockUsers(actor,rows.isEmpty()?actor:((Number)rows.getFirst().get("user_id")).longValue());
        access.requireManager(actor,school);
        if(rows.isEmpty()) throw ApiException.notFound("本校负责人授权不存在");
        if(db.update("update school_cohort_leader set revoked_at=now(),revoked_by=?,revoke_reason='MANUAL' where id=? and school_id=? and revoked_at is null",actor,id,school)>0)
            mapper.audit(actor,"COHORT_LEADER_REVOKED","grantId="+id+",schoolId="+school);
    }
    public Map<String,Object> members(long actor,long school,int year,String status,String query,int page) {
        access.requireScope(actor,school,year);
        if(!List.of("","PENDING","APPROVED","REJECTED").contains(status) || query.length()>100 || page<1 || page>100000)
            throw ApiException.validation("筛选参数无效");
        return mapper.members(actor,school,year,status,query.trim(),page);
    }
    private Map<String,Object> target(long actor,long school,int year,long id) {
        var initial=mapper.member(school,year,id,false);
        access.lockUsers(actor,initial==null?actor:((Number)initial.get("user_id")).longValue());
        access.requireScope(actor,school,year);
        var target=mapper.member(school,year,id,true);
        if(target==null) throw ApiException.notFound("本届成员不存在");
        if(!"STUDENT".equals(target.get("role")) || !"ACTIVE".equals(target.get("userStatus")) || Boolean.TRUE.equals(target.get("schoolAdmin")))
            throw ApiException.forbidden("不能修改管理员或停用用户");
        return target;
    }
    public SchoolMember review(long actor,long school,int year,long id,boolean approve,String reason) {
        var target=target(actor,school,year,id);
        long user=((Number)target.get("user_id")).longValue();
        if(user==actor || mapper.isLeader(user)) throw ApiException.forbidden("本人或其他负责人的成员状态请由学校管理员处理");
        var result=schools.review(id,approve,actor,reason);
        mapper.audit(actor,"COHORT_MEMBER_REVIEWED","memberId="+id+",schoolId="+school+",year="+year+",approved="+approve);
        return result;
    }
    public SchoolMember profile(long actor,long school,int year,long id,String className,String studentNo) {
        target(actor,school,year,id);
        var result=schools.adminUpdateProfile(id,year,className,studentNo,actor);
        mapper.audit(actor,"COHORT_MEMBER_PROFILE_CHANGED","memberId="+id+",schoolId="+school+",year="+year);
        return result;
    }
}
