package com.szpu.algohub.school;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.annotation.Transactional;
import com.szpu.algohub.auth.*;

@SpringBootTest @AutoConfigureMockMvc @Transactional
class CohortLeaderBoundaryTest {
    @Autowired MockMvc mvc;
    @Autowired JdbcTemplate db;
    @Autowired AuthService auth;
    @Autowired AppUserMapper users;
    long school, otherSchool, manager, leader, student, pending, otherYear, foreign;
    long leaderMember, studentMember, pendingMember, otherYearMember, foreignMember;
    String managerToken, leaderToken;

    long user() {
        String name = "cohort-" + UUID.randomUUID();
        long id = db.queryForObject("insert into app_user(username,display_name,role) values (?,?,'STUDENT') returning id", Long.class, name, name);
        db.update("insert into verified_login_email(user_id,email) values (?,?)", id, name + "@test.invalid");
        db.update("insert into user_privacy(user_id,profile_visibility,show_email,show_accounts) values (?,'PRIVATE',false,false)", id);
        return id;
    }
    long member(long schoolId, long userId, int year, String status) {
        return db.queryForObject("insert into school_member(school_id,user_id,enrollment_year,status,class_name,student_no) values (?,?,?,?,'A','100') returning id", Long.class, schoolId, userId, year, status);
    }
    String token(long userId) {
        return "Bearer " + auth.issueSession(users.findById(userId), "127.0.0.1", "JUnit", "LOGIN_SUCCESS").view().accessToken();
    }
    String grants() { return "/api/school-admin/" + school + "/cohort-leaders"; }
    String cohort() { return "/api/cohort-admin/" + school + "/2026/members"; }
    void grant(long userId) throws Exception {
        mvc.perform(post(grants()).header("Authorization", managerToken).contentType(MediaType.APPLICATION_JSON)
                .content("{\"userId\":" + userId + ",\"enrollmentYear\":2026}")).andExpect(status().isOk());
    }
    @BeforeEach void setup() {
        school = db.queryForObject("insert into school(name) values (?) returning id", Long.class, "Cohort A " + UUID.randomUUID());
        otherSchool = db.queryForObject("insert into school(name) values (?) returning id", Long.class, "Cohort B " + UUID.randomUUID());
        manager=user(); leader=user(); student=user(); pending=user(); otherYear=user(); foreign=user();
        leaderMember=member(school,leader,2026,"APPROVED"); studentMember=member(school,student,2026,"APPROVED");
        pendingMember=member(school,pending,2026,"PENDING"); otherYearMember=member(school,otherYear,2025,"APPROVED");
        foreignMember=member(otherSchool,foreign,2026,"APPROVED");
        db.update("insert into school_administrator(user_id,school_id) values (?,?)", manager,school);
        managerToken=token(manager); leaderToken=token(leader);
    }
    @Test void grantsAreScopedIdempotentAndReturnedInIdentity() throws Exception {
        grant(leader); grant(leader);
        assertThat(db.queryForObject("select count(*) from school_cohort_leader where user_id=? and revoked_at is null", Long.class,leader)).isEqualTo(1);
        mvc.perform(get("/api/cohort-admin/scopes").header("Authorization",leaderToken)).andExpect(status().isOk())
                .andExpect(jsonPath("$.data.length()").value(1)).andExpect(jsonPath("$.data[0].enrollmentYear").value(2026));
        mvc.perform(get("/api/auth/me").header("Authorization",leaderToken)).andExpect(jsonPath("$.data.role").value("STUDENT"))
                .andExpect(jsonPath("$.data.managedCohorts.length()").value(1));
        mvc.perform(get(grants()).header("Authorization",leaderToken)).andExpect(status().isForbidden());
        mvc.perform(get("/api/admin/users").header("Authorization",leaderToken)).andExpect(status().isForbidden());
    }
    @Test void invalidCandidatesAndUnauthorizedActorsCannotGrant() throws Exception {
        for(long candidate:new long[]{pending,foreign,manager}) {
            mvc.perform(post(grants()).header("Authorization",managerToken).contentType(MediaType.APPLICATION_JSON)
                    .content("{\"userId\":"+candidate+",\"enrollmentYear\":2026}")).andExpect(status().isBadRequest());
        }
        mvc.perform(post(grants()).header("Authorization",token(student)).contentType(MediaType.APPLICATION_JSON)
                .content("{\"userId\":"+leader+",\"enrollmentYear\":2026}")).andExpect(status().isForbidden());
        mvc.perform(get("/api/school-admin/"+otherSchool+"/cohort-leaders").header("Authorization",managerToken)).andExpect(status().isForbidden());
        db.update("update app_user set status='DISABLED' where id=?",leader);
        mvc.perform(post(grants()).header("Authorization",managerToken).contentType(MediaType.APPLICATION_JSON)
                .content("{\"userId\":"+leader+",\"enrollmentYear\":2026}")).andExpect(status().isBadRequest());
    }
    @Test void memberListCountSearchAndWritesStayWithinSchoolAndYear() throws Exception {
        grant(leader);
        mvc.perform(get(cohort()).header("Authorization",leaderToken)).andExpect(status().isOk()).andExpect(jsonPath("$.data.total").value(3));
        mvc.perform(get(cohort()).param("q",users.findById(otherYear).getUsername()).header("Authorization",leaderToken))
                .andExpect(jsonPath("$.data.total").value(0));
        mvc.perform(get("/api/cohort-admin/"+school+"/2025/members").header("Authorization",leaderToken)).andExpect(status().isForbidden());
        for(long id:new long[]{otherYearMember,foreignMember}) {
            mvc.perform(post(cohort()+"/"+id+"/review").header("Authorization",leaderToken).contentType(MediaType.APPLICATION_JSON).content("{\"approve\":true}"))
                    .andExpect(status().isNotFound());
        }
        mvc.perform(post(cohort()+"/"+pendingMember+"/review").header("Authorization",leaderToken).contentType(MediaType.APPLICATION_JSON).content("{\"approve\":true}"))
                .andExpect(status().isOk());
        mvc.perform(post(cohort()+"/"+studentMember+"/profile").header("Authorization",leaderToken).contentType(MediaType.APPLICATION_JSON)
                .content("{\"className\":\" B \",\"studentNo\":\" 200 \"}")).andExpect(status().isOk());
        assertThat(db.queryForObject("select class_name from school_member where id=?",String.class,studentMember)).isEqualTo("B");
        assertThat(db.queryForObject("select enrollment_year from school_member where id=?",Integer.class,studentMember)).isEqualTo(2026);
        mvc.perform(post(cohort()+"/"+studentMember+"/profile").header("Authorization",leaderToken).contentType(MediaType.APPLICATION_JSON)
                .content("{\"className\":\"B\",\"enrollmentYear\":2025}")).andExpect(status().isBadRequest());
    }
    @Test void selfAndProtectedMembersCannotBeReviewedOrModified() throws Exception {
        grant(leader); grant(student);
        for(long id:new long[]{leaderMember,studentMember}) {
            mvc.perform(post(cohort()+"/"+id+"/review").header("Authorization",leaderToken).contentType(MediaType.APPLICATION_JSON).content("{\"approve\":false}"))
                    .andExpect(status().isForbidden());
        }
        long managerMember=member(school,manager,2026,"APPROVED");
        mvc.perform(post(cohort()+"/"+managerMember+"/profile").header("Authorization",leaderToken).contentType(MediaType.APPLICATION_JSON)
                .content("{\"className\":\"B\",\"studentNo\":\"200\"}")).andExpect(status().isForbidden());
        db.update("update app_user set role='ADMIN' where id=?",pending);
        mvc.perform(post(cohort()+"/"+pendingMember+"/review").header("Authorization",leaderToken).contentType(MediaType.APPLICATION_JSON).content("{\"approve\":true}"))
                .andExpect(status().isForbidden());
    }
    @Test void privateProfileAccessIsScopedWithoutExposingEmailOrSocialPrivileges() throws Exception {
        grant(leader);
        String[] urls={"/api/me/profile/","/api/stats/profile/","/api/history/user/","/api/stats/rating-history/","/api/activity/achievements/","/api/activity/streak/"};
        for(String prefix:urls) {
            mvc.perform(get(prefix+student).header("Authorization",leaderToken)).andExpect(status().isOk());
            mvc.perform(get(prefix+otherYear).header("Authorization",leaderToken)).andExpect(status().isNotFound());
            mvc.perform(get(prefix+foreign).header("Authorization",leaderToken)).andExpect(status().isNotFound());
        }
        mvc.perform(get("/api/me/profile/"+student).header("Authorization",leaderToken)).andExpect(jsonPath("$.data.email").doesNotExist());
        mvc.perform(post("/api/social/users/"+student+"/follow").header("Authorization",leaderToken)).andExpect(status().isNotFound());
        assertThat(db.queryForObject("select count(*) from auth_event where user_id=? and event='COHORT_PRIVATE_PROFILE_VIEW'",Integer.class,leader)).isPositive();
        db.update("update user_privacy set profile_visibility='PUBLIC' where user_id=?",otherYear);
        mvc.perform(get("/api/me/profile/"+otherYear).header("Authorization",leaderToken)).andExpect(status().isOk());
    }
    @Test void revokedGrantsCannotBeUsedWithExistingTokens() throws Exception {
        grant(leader);
        long id=db.queryForObject("select id from school_cohort_leader where user_id=? and revoked_at is null",Long.class,leader);
        mvc.perform(delete(grants()+"/"+id).header("Authorization",managerToken)).andExpect(status().isOk());
        mvc.perform(delete(grants()+"/"+id).header("Authorization",managerToken)).andExpect(status().isOk());
        mvc.perform(get(cohort()).header("Authorization",leaderToken)).andExpect(status().isForbidden());
        mvc.perform(get("/api/me/profile/"+student).header("Authorization",leaderToken)).andExpect(status().isNotFound());
        assertThat(auth.me(leader).managedCohorts()).isEmpty();
    }
    @Test void membershipChangesPermanentlyRevokeButClassChangesDoNot() throws Exception {
        grant(leader);
        db.update("update school_member set class_name='C',student_no='300' where id=?",leaderMember);
        mvc.perform(get(cohort()).header("Authorization",leaderToken)).andExpect(status().isOk());
        db.update("update school_member set enrollment_year=2025 where id=?",leaderMember);
        db.update("update school_member set enrollment_year=2026 where id=?",leaderMember);
        mvc.perform(get(cohort()).header("Authorization",leaderToken)).andExpect(status().isForbidden());
        grant(leader);
        db.update("update school_member set status='REJECTED' where id=?",leaderMember);
        db.update("update school_member set status='APPROVED' where id=?",leaderMember);
        mvc.perform(get(cohort()).header("Authorization",leaderToken)).andExpect(status().isForbidden());
    }
    @Test void schoolAdminPromotionPermanentlyRevokesOldLeaderGrants() throws Exception {
        grant(leader);
        db.update("insert into school_administrator(user_id,school_id) values (?,?)",leader,school);
        db.update("delete from school_administrator where user_id=?",leader);
        mvc.perform(get(cohort()).header("Authorization",leaderToken)).andExpect(status().isForbidden());
    }
    @Test void disabledAccountCannotRegainOldGrantAfterReactivation() throws Exception {
        grant(leader);
        db.update("update app_user set status='DISABLED' where id=?",leader);
        db.update("update app_user set status='ACTIVE' where id=?",leader);
        mvc.perform(get(cohort()).header("Authorization",leaderToken)).andExpect(status().isForbidden());
        assertThat(auth.me(leader).managedCohorts()).isEmpty();
    }
    @Test void restrictedPlatformAdminCannotUseLegacySchoolGrantsToDelegate() throws Exception {
        db.update("update app_user set role='ADMIN',admin_permissions=array['CONTENT']::varchar[] where id=?",manager);
        mvc.perform(post(grants()).header("Authorization",token(manager)).contentType(MediaType.APPLICATION_JSON)
                .content("{\"userId\":"+leader+",\"enrollmentYear\":2026}")).andExpect(status().isForbidden());
    }
}
