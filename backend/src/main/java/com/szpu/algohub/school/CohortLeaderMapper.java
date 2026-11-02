package com.szpu.algohub.school;

import java.util.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

/** Fresh JDBC reads keep delegation revocation visible across existing sessions. */
@Repository
public class CohortLeaderMapper {
    private final JdbcTemplate db;
    public CohortLeaderMapper(JdbcTemplate db) { this.db = db; }

    private static final String VALID = """
        from school_cohort_leader g
        join school_member m on m.id=g.member_id and m.school_id=g.school_id and m.user_id=g.user_id
        join app_user u on u.id=g.user_id
        where g.revoked_at is null and m.status='APPROVED' and u.status='ACTIVE' and u.role='STUDENT'
          and not exists(select 1 from school_administrator sa where sa.user_id=g.user_id)
        """;

    public List<Map<String,Object>> scopes(long actor) {
        return db.queryForList("select distinct g.school_id as \"schoolId\",s.name as \"schoolName\",g.enrollment_year as \"enrollmentYear\" "
                + VALID.replace("where g.revoked_at", "join school s on s.id=g.school_id where g.revoked_at")
                + " and g.user_id=? order by \"schoolName\",\"enrollmentYear\" desc", actor);
    }

    public Map<String,Object> profileScope(long actor, long target) {
        var rows = db.queryForList("select g.school_id as \"schoolId\",g.enrollment_year as \"enrollmentYear\" " + VALID + """
            and g.user_id=? and exists(select 1 from school_member t join app_user tu on tu.id=t.user_id
                where t.user_id=? and t.school_id=g.school_id and t.enrollment_year=g.enrollment_year
                  and t.status='APPROVED' and tu.status='ACTIVE' and tu.role='STUDENT'
                  and not exists(select 1 from school_administrator sa where sa.user_id=t.user_id))
            order by g.id limit 1
            """,actor,target);
        return rows.isEmpty() ? null : rows.getFirst();
    }

    public boolean isLeader(long user) {
        return Boolean.TRUE.equals(db.queryForObject("select exists(select 1 " + VALID + " and g.user_id=?)",Boolean.class,user));
    }

    public List<Map<String,Object>> grants(long school, Integer year) {
        return db.queryForList("""
            select g.id,g.user_id as "userId",u.username,u.display_name as "displayName",
                g.enrollment_year as "enrollmentYear",g.created_at as "createdAt"
            from school_cohort_leader g join app_user u on u.id=g.user_id
            where g.school_id=? and g.revoked_at is null and (?::integer is null or g.enrollment_year=?)
            order by g.enrollment_year desc,g.id
            """,school,year,year);
    }

    public List<Map<String,Object>> candidates(long school, String query, Integer year) {
        return db.queryForList("""
            select u.id as "userId",u.username,u.display_name as "displayName",m.enrollment_year as "enrollmentYear"
            from school_member m join app_user u on u.id=m.user_id
            where m.school_id=? and m.status='APPROVED' and m.enrollment_year is not null
              and u.role='STUDENT' and u.status='ACTIVE'
              and not exists(select 1 from school_administrator sa where sa.user_id=u.id)
              and (?::integer is null or m.enrollment_year=?)
              and position(lower(?) in lower(u.username||' '||u.display_name))>0
            order by u.id limit 30
            """,school,year,year,query);
    }

    public Map<String,Object> member(long school, int year, long id, boolean lock) {
        var rows=db.queryForList("""
            select m.*,u.role,u.status as "userStatus",
                exists(select 1 from school_administrator sa where sa.user_id=m.user_id) as "schoolAdmin"
            from school_member m join app_user u on u.id=m.user_id
            where m.id=? and m.school_id=? and m.enrollment_year=?
            """ + (lock ? " for update of m" : ""),id,school,year);
        return rows.isEmpty() ? null : rows.getFirst();
    }

    public Map<String,Object> members(long actor,long school,int year,String status,String query,int page) {
        String where="""
            from school_member m join app_user u on u.id=m.user_id
            where m.school_id=? and m.enrollment_year=? and (?='' or m.status=?)
              and position(lower(?) in lower(u.display_name||' '||u.username||' '||coalesce(m.student_no,'')||' '||coalesce(m.class_name,'')))>0
            """;
        Object[] args={school,year,status,status,query};
        long total=db.queryForObject("select count(*) " + where,Long.class,args);
        var parameters=new ArrayList<Object>(); parameters.add(actor); parameters.addAll(Arrays.asList(args)); parameters.add((page-1)*50);
        var rows=db.queryForList("""
            select m.id,m.user_id as "userId",u.display_name as "displayName",u.username,m.status,
                m.enrollment_year as "enrollmentYear",m.class_name as "className",m.student_no as "studentNo",
                (u.role='STUDENT' and u.status='ACTIVE' and not exists(select 1 from school_administrator sa where sa.user_id=u.id)) as "canEdit",
                (m.user_id<>? and u.role='STUDENT' and u.status='ACTIVE'
                    and not exists(select 1 from school_administrator sa where sa.user_id=u.id)
                    and not exists(select 1 from school_cohort_leader g where g.user_id=u.id and g.revoked_at is null)) as "canReview"
            """ + where + " order by m.id desc limit 50 offset ?",parameters.toArray());
        return Map.of("rows",rows,"total",total);
    }

    public void audit(long actor,String event,String detail) {
        db.update("insert into auth_event(user_id,event,detail) values (?,?,?)",actor,event,detail);
    }
}
