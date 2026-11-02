package com.szpu.algohub.weeklyreport;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;
import java.time.*;
import java.util.*;
import jakarta.mail.Session;
import jakarta.mail.internet.MimeMessage;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.http.MediaType;
import org.springframework.transaction.annotation.Transactional;
import com.szpu.algohub.auth.*;
import com.szpu.algohub.school.CohortLeaderService;
import com.szpu.algohub.common.ApiException;

@SpringBootTest(properties={"algohub.email.from=weekly@test.invalid","PUBLIC_ORIGIN=https://weekly.test.invalid"})
@AutoConfigureMockMvc @Transactional
class WeeklyReportTest {
    @Autowired JdbcTemplate db;
    @Autowired WeeklyReportService reports;
    @Autowired ReportSettingsService settings;
    @Autowired ReportCycleService cycles;
    @Autowired ReportReminderService reminders;
    @Autowired ReportJobs jobs;
    @Autowired CohortLeaderService leaders;
    @Autowired MockMvc mvc;
    @Autowired AuthService auth;
    @Autowired AppUserMapper users;
    @MockitoBean ReportTime clock;
    @MockitoBean JavaMailSender mail;
    long school,otherSchool,manager,leader,student,older,foreign;
    Instant base;
    LocalDate week;
    long user(String label) {
        String name="wr-"+label+"-"+UUID.randomUUID();
        long id=db.queryForObject("insert into app_user(username,display_name,role) values (?,?,'STUDENT') returning id",Long.class,name,name);
        db.update("insert into verified_login_email(user_id,email) values (?,?)",id,name+"@test.invalid");return id;
    }
    void member(long school,long user,int year) { db.update("insert into school_member(school_id,user_id,enrollment_year,status,class_name,student_no) values (?,?,?,'APPROVED','A','123')",school,user,year); }
    String token(long id) {return "Bearer "+auth.issueSession(users.findById(id),"127.0.0.1","JUnit","LOGIN_SUCCESS").view().accessToken();}
    @BeforeEach void setup() {
        school=db.queryForObject("insert into school(name) values (?) returning id",Long.class,"Weekly A "+UUID.randomUUID());
        otherSchool=db.queryForObject("insert into school(name) values (?) returning id",Long.class,"Weekly B "+UUID.randomUUID());
        manager=user("manager");leader=user("leader");student=user("student");older=user("older");foreign=user("foreign");
        member(school,leader,2026);member(school,student,2026);member(school,older,2025);member(otherSchool,foreign,2026);
        db.update("insert into school_administrator(user_id,school_id) values (?,?)",manager,school);
        leaders.grant(manager,school,leader,2026);
        // PostgreSQL timestamps have microsecond precision. Linux clocks may supply nanoseconds;
        // rounding a persisted reminder upward must not make this synthetic exact-time call early.
        base=Instant.now().plusSeconds(5).truncatedTo(java.time.temporal.ChronoUnit.MICROS);week=ReportTime.week(base);at(base);
        when(mail.createMimeMessage()).thenAnswer(i->new MimeMessage(Session.getInstance(new Properties())));
    }
    void at(Instant now) {when(clock.now()).thenReturn(now);}
    Map<String,Object> save(long user,Long id,int version,String text,boolean submit) {return reports.save(user,new WeeklyReportService.Draft(id,week.toString(),version,text,"",""),submit);}
    long id(Map<String,Object> row) {return ((Number)row.get("id")).longValue();}
    int version(Map<String,Object> row) {return ((Number)row.get("version")).intValue();}
    boolean deliver(Instant now) {at(now);return reminders.deliverOne(school,now);}
    void enable() {settings.save(manager,school,new ReportSettingsService.Input(true,7,"23:59",6,true,0,false));}
    long cycle() {
        enable();
        db.update("delete from weekly_report_cycle where school_id=?",school);
        long id=db.queryForObject("insert into weekly_report_cycle(school_id,week_start,cutoff_at,remind_at,email_enabled) values (?,?,?,?,true) returning id",Long.class,school,week,ReportTime.sql(base.plusSeconds(600)),ReportTime.sql(base.plusSeconds(100)));
        assertThat(ReportTime.instant(db.queryForObject("select remind_at from weekly_report_cycle where id=?",java.sql.Timestamp.class,id))).isEqualTo(base.plusSeconds(100));
        return id;
    }
    @Test void draftSubmitReviewReturnAndResubmitPreserveVersions() {
        var draft=save(student,null,0,"draft only",false);assertThat(draft.get("status")).isEqualTo("DRAFT");
        assertThat(reports.managed(manager,school,null,week.toString(),"","",1).get("total")).isEqualTo(0L);
        var submitted=save(student,id(draft),version(draft),"submitted body",true);
        var reviewed=reports.decide(leader,school,2026,id(draft),new WeeklyReportService.Decision(version(submitted),false,"Good"));
        assertThat(reviewed.get("status")).isEqualTo("REVIEWED");
        var returned=reports.decide(manager,school,null,id(draft),new WeeklyReportService.Decision(version(reviewed),true,"More detail"));
        var editing=save(student,id(draft),version(returned),"PRIVATE UNSENT CHANGES",false);
        var managed=reports.managedDetail(leader,school,2026,id(draft));
        assertThat(managed.get("summary")).isEqualTo("submitted body");assertThat(managed.toString()).doesNotContain("PRIVATE UNSENT CHANGES");
        var resubmitted=save(student,id(draft),version(editing),"fixed",true);
        assertThat(resubmitted.get("status")).isEqualTo("SUBMITTED");
        assertThat((List<?>)resubmitted.get("history")).hasSize(6);
    }
    @Test void ownerIsolationAndStudentManagementEndpointsAreDenied() throws Exception {
        var r=save(student,null,0,"secret",false);
        mvc.perform(get("/api/weekly-reports/"+id(r)).header("Authorization",token(foreign))).andExpect(status().isNotFound());
        for(String endpoint:List.of("settings","cycles","missing?week="+week,"reminders?week="+week,"reports?week="+week))
            mvc.perform(get("/api/weekly-report-management/"+school+"/"+endpoint).header("Authorization",token(student))).andExpect(status().isForbidden());
        mvc.perform(get("/api/weekly-reports").header("Authorization",token(student))).andExpect(status().isOk()).andExpect(content().string(org.hamcrest.Matchers.not(org.hamcrest.Matchers.containsString("cutoffStatus"))));
    }
    @Test void crossSchoolYearAndContentOnlyAdminCannotReadReports() throws Exception {
        var r=save(older,null,0,"old grade secret",true);
        assertThatThrownBy(()->reports.managedDetail(leader,school,2026,id(r))).isInstanceOf(ApiException.class);
        assertThatThrownBy(()->reports.managed(leader,school,2025,week.toString(),"","",1)).isInstanceOf(ApiException.class);
        assertThatThrownBy(()->reports.managed(manager,otherSchool,null,week.toString(),"","",1)).isInstanceOf(ApiException.class);
        long restricted=user("content");db.update("update app_user set role='ADMIN',admin_permissions=array['CONTENT'] where id=?",restricted);
        mvc.perform(get("/api/weekly-report-management/"+school+"/settings").header("Authorization",token(restricted))).andExpect(status().isForbidden());
    }
    @Test void blankFuturePreEnrollmentAndStaleWritesAreRejected() {
        assertThatThrownBy(()->save(student,null,0," ",true)).isInstanceOf(ApiException.class);
        assertThatThrownBy(()->reports.save(student,new WeeklyReportService.Draft(null,week.plusWeeks(1).toString(),0,"future","",""),true)).isInstanceOf(ApiException.class);
        assertThatThrownBy(()->reports.save(student,new WeeklyReportService.Draft(null,week.minusWeeks(2).toString(),0,"past","",""),true)).isInstanceOf(ApiException.class);
        var r=save(student,null,0,"one",false);
        assertThatThrownBy(()->save(student,id(r),0,"stale",true)).isInstanceOf(ApiException.class);
        reports.delete(student,id(r),version(r));assertThat(reports.mine(student,1).get("total")).isEqualTo(0L);
    }
    @Test void submittedReportCannotBeOverwrittenDeletedOrSelfReviewed() {
        var r=save(leader,null,0,"captain",true);
        assertThatThrownBy(()->save(leader,id(r),version(r),"overwrite",false)).isInstanceOf(ApiException.class);
        assertThatThrownBy(()->reports.delete(leader,id(r),version(r))).isInstanceOf(ApiException.class);
        assertThatThrownBy(()->reports.decide(leader,school,2026,id(r),new WeeklyReportService.Decision(version(r),false,"self"))).isInstanceOf(ApiException.class);
        reports.decide(manager,school,null,id(r),new WeeklyReportService.Decision(version(r),false,"reviewed"));
        assertThatThrownBy(()->reports.decide(manager,school,null,id(r),new WeeklyReportService.Decision(version(r),true,"stale"))).isInstanceOf(ApiException.class);
    }
    @Test void settingsDefaultOffValidateAndChangesApplyNextWeek() {
        assertThat(settings.get(manager,school).get("enabled")).isEqualTo(false);
        assertThatThrownBy(()->settings.save(manager,school,new ReportSettingsService.Input(true,1,"01:00",2,true,0,false))).isInstanceOf(ApiException.class);
        enable();
        settings.save(manager,school,new ReportSettingsService.Input(true,6,"21:00",3,false,1,false));
        assertThat(cycles.rule(school,week).get("day_of_week")).isEqualTo(7);
        assertThat(cycles.rule(school,week.plusWeeks(1)).get("day_of_week")).isEqualTo(6);
        assertThatThrownBy(()->settings.save(manager,school,new ReportSettingsService.Input(false,6,"21:00",3,false,2,false))).isInstanceOf(ApiException.class);
    }
    @Test void snapshotUsesDeadlineStatusAndPreservesLateSubmission() {
        long c=cycle();var early=save(older,null,0,"early",true);var draft=save(student,null,0,"draft",false);
        at(base.plusSeconds(601));save(student,id(draft),version(draft),"late",true);
        cycles.snapshot(c,base.plusSeconds(900));cycles.snapshot(c,base.plusSeconds(901));
        assertThat(db.queryForObject("select eligible_count from weekly_report_cycle where id=?",Integer.class,c)).isEqualTo(3);
        assertThat(db.queryForObject("select submitted_count from weekly_report_cycle where id=?",Integer.class,c)).isEqualTo(1);
        var missing=cycles.missing(manager,school,null,week.toString(),"","","YES","",1);
        assertThat(missing.get("total")).isEqualTo(1L);
        assertThat(missing.toString()).contains("cutoffStatus=DRAFT","currentStatus=SUBMITTED","lateAt=").doesNotContain("lateAt=null");
        assertThat(cycles.missing(manager,school,null,week.toString(),"","","","",1).get("total")).isEqualTo(2L);
    }
    @Test void returnedAtCutoffIsMissingButReviewedAtCutoffIsNot() {
        long c=cycle();var r=save(student,null,0,"body",true);
        reports.decide(manager,school,null,id(r),new WeeklyReportService.Decision(version(r),true,"fix"));
        var o=save(older,null,0,"body",true);reports.decide(manager,school,null,id(o),new WeeklyReportService.Decision(version(o),false,"fine"));
        cycles.snapshot(c,base.plusSeconds(600));
        assertThat(db.queryForObject("select cutoff_status from weekly_report_missing where cycle_id=? and user_id=?",String.class,c,student)).isEqualTo("RETURNED");
        assertThat(db.queryForObject("select count(*) from weekly_report_missing where cycle_id=? and user_id=?",Integer.class,c,older)).isZero();
    }
    @Test void exactCutoffSubmissionCountsAndEmptyMissingStillHasCompletedCycle() {
        long c=cycle();at(base.plusSeconds(600));
        for(long u:new long[]{student,leader,older})save(u,null,0,"on time",true);
        cycles.snapshot(c,base.plusSeconds(601));
        assertThat(db.queryForObject("select status from weekly_report_cycle where id=?",String.class,c)).isEqualTo("COMPLETE");
        assertThat(cycles.missing(manager,school,null,week.toString(),"","","","",1).get("total")).isEqualTo(0L);
    }
    @Test void scopeCountsExcludeCaptainSelfAndMissingYearIsSchoolOnly() {
        long missingYear=user("unknown");member(school,missingYear,2026);db.update("update school_member set enrollment_year=null where user_id=?",missingYear);
        long c=cycle();cycles.snapshot(c,base.plusSeconds(700));
        assertThat(cycles.missing(leader,school,2026,week.toString(),"","","","",1).get("total")).isEqualTo(1L);
        assertThat(cycles.missing(manager,school,null,week.toString(),"","","","",1).get("total")).isEqualTo(4L);
        assertThat(cycles.cycles(leader,school,2026).getFirst().get("eligibleCount")).isNull();
    }
    @Test void historicalRosterSurvivesLaterGradeChangeAndDisable() {
        long c=cycle();
        db.update("update school_member set enrollment_year=2025,class_name='B' where user_id=?",student);
        db.update("update weekly_report_roster_history set happened_at=? where user_id=? and enrollment_year=2025",ReportTime.sql(base.plusSeconds(800)),student);
        db.update("update app_user set status='DISABLED' where id=?",older);
        db.update("update weekly_report_roster_history set happened_at=? where user_id=? and not eligible",ReportTime.sql(base.plusSeconds(800)),older);
        cycles.snapshot(c,base.plusSeconds(900));
        assertThat(db.queryForObject("select enrollment_year from weekly_report_missing where cycle_id=? and user_id=?",Integer.class,c,student)).isEqualTo(2026);
        assertThat(db.queryForObject("select eligible_count from weekly_report_cycle where id=?",Integer.class,c)).isEqualTo(3);
    }
    @Test void disableStopsPendingMailAndCaptainsHistoryButNotReviewOrOwnReads() {
        long c=cycle();var r=save(student,null,0,"body",true);reminders.generate(c,base.plusSeconds(100));cycles.snapshot(c,base.plusSeconds(600));
        settings.save(manager,school,new ReportSettingsService.Input(false,7,"23:59",6,true,1,true));
        assertThat(deliver(base.plusSeconds(200))).isFalse();
        assertThatThrownBy(()->cycles.cycles(leader,school,2026)).isInstanceOf(ApiException.class);
        assertThat(cycles.cycles(manager,school,null)).hasSize(1);
        assertThat(reports.own(student,id(r)).get("status")).isEqualTo("SUBMITTED");
        reports.decide(manager,school,null,id(r),new WeeklyReportService.Decision(version(r),false,"still available"));
    }
    @Test void remindersDeduplicateExcludeSubmittedAndCancelAfterSubmission() {
        long c=cycle();save(older,null,0,"already",true);
        reminders.generate(c,base.plusSeconds(100));reminders.generate(c,base.plusSeconds(110));
        assertThat(db.queryForObject("select count(*) from weekly_report_reminder where cycle_id=?",Integer.class,c)).isEqualTo(2);
        save(student,null,0,"now submitted",true);
        while(deliver(base.plusSeconds(200))) {}
        verify(mail,times(1)).send(any(MimeMessage.class));
        assertThat(db.queryForObject("select o.last_error from study_mail_outbox o join weekly_report_reminder n on n.notification_id=o.notification_id where n.cycle_id=? and n.user_id=?",String.class,c,student)).contains("已提交");
    }
    @Test void mailOptOutAndNoVerifiedEmailAreDistinctWithoutDisclosure() {
        long c=cycle();db.update("delete from verified_login_email where user_id=?",student);
        db.update("insert into study_goal_setting(user_id,email_enabled) values (?,false)",older);
        reminders.generate(c,base.plusSeconds(100));
        var overview=reminders.overview(manager,school,week.toString()).toString();
        assertThat(overview).contains("无已验证邮箱","个人已关闭").doesNotContain("@test.invalid");
        assertThat(db.queryForObject("select count(*) from study_notification where kind='WEEKLY_REPORT_DUE' and user_id=?",Integer.class,older)).isEqualTo(1);
    }
    @Test void mailFailureRetriesAreBoundedAndExpireAtCutoff() {
        long c=cycle();save(older,null,0,"done",true);save(leader,null,0,"done",true);
        reminders.generate(c,base.plusSeconds(100));doThrow(new org.springframework.mail.MailSendException("private internal address")).when(mail).send(any(MimeMessage.class));
        assertThat(deliver(base.plusSeconds(101))).isTrue();
        var result=reminders.overview(manager,school,week.toString()).toString();assertThat(result).contains("PENDING","邮件服务发送失败").doesNotContain("private internal");
        assertThat(deliver(base.plusSeconds(600))).isTrue();
        verify(mail,times(1)).send(any(MimeMessage.class));assertThat(reminders.overview(manager,school,week.toString()).toString()).contains("过期");
    }
    @Test void reminderServiceDoesNotPreventStatisticsWhenMailDisabled() {
        long c=cycle();jobs.tick(base.plusSeconds(601),false);
        assertThat(db.queryForObject("select status from weekly_report_cycle where id=?",String.class,c)).isEqualTo("COMPLETE");
        assertThat(db.queryForObject("select count(*) from weekly_report_reminder where cycle_id=?",Integer.class,c)).isZero();
    }
    @Test void revokedLeaderTokenCannotReadPreviouslyAccessibleReport() throws Exception {
        var r=save(student,null,0,"private",true);String token=token(leader);
        db.update("update school_cohort_leader set revoked_at=now() where user_id=?",leader);
        mvc.perform(get("/api/weekly-report-management/"+school+"/reports/"+id(r)).param("year","2026").header("Authorization",token)).andExpect(status().isForbidden());
    }
    @Test void deletingAndRecreatingDraftAfterDeadlineDoesNotEraseCutoffState() {
        long c=cycle();var draft=save(student,null,0,"deleted private text",false);
        at(base.plusSeconds(650));reports.delete(student,id(draft),version(draft));
        assertThatThrownBy(()->reports.own(student,id(draft))).isInstanceOf(ApiException.class);
        assertThat(db.queryForObject("select count(*) from weekly_report_event where report_id=? and summary like '%private%'",Integer.class,id(draft))).isZero();
        at(base.plusSeconds(700));save(student,null,0,"replacement",true);
        cycles.snapshot(c,base.plusSeconds(800));
        assertThat(db.queryForObject("select cutoff_status from weekly_report_missing where cycle_id=? and user_id=?",String.class,c,student)).isEqualTo("DRAFT");
    }
    @Test void deletionBeforeDeadlineIsRecordedAsNotCreated() {
        long c=cycle();var draft=save(student,null,0,"draft",false);at(base.plusSeconds(100));reports.delete(student,id(draft),version(draft));
        cycles.snapshot(c,base.plusSeconds(800));
        assertThat(db.queryForObject("select cutoff_status from weekly_report_missing where cycle_id=? and user_id=?",String.class,c,student)).isEqualTo("NOT_CREATED");
    }
    @Test void actualSendClockStopsSecondRecipientAfterFirstSendCrossesDeadline() {
        long c=cycle();save(older,null,0,"already",true);reminders.generate(c,base.plusSeconds(100));
        at(base.plusSeconds(200));doAnswer(i->{at(base.plusSeconds(601));return null;}).when(mail).send(any(MimeMessage.class));
        reminders.deliverOne(school,base.plusSeconds(200));reminders.deliverOne(school,base.plusSeconds(200));
        verify(mail,times(1)).send(any(MimeMessage.class));
        assertThat(reminders.overview(manager,school,week.toString()).toString()).contains("过期");
    }
    @Test void futureCyclesCannotStarveAnAlreadyDueReminder() {
        long c=cycle();
        for(int i=0;i<101;i++) {
            long s=db.queryForObject("insert into school(name) values (?) returning id",Long.class,"Future "+i+" "+UUID.randomUUID());
            db.update("insert into weekly_report_setting(school_id,enabled,enabled_since) values (?,true,?)",s,ReportTime.sql(base));
            db.update("insert into weekly_report_cycle(school_id,week_start,cutoff_at,email_enabled,reminder_done) values (?,?,?,true,true)",s,week,ReportTime.sql(base.plusSeconds(300)));
        }
        at(base.plusSeconds(100));jobs.tick(base.plusSeconds(100),true);
        assertThat(db.queryForObject("select reminder_done from weekly_report_cycle where id=?",Boolean.class,c)).isTrue();
    }
    @Test void restartReconstructsAWholeMissedWeekOnlyWithinContinuousEnabledPeriod() {
        enable();db.update("delete from weekly_report_cycle where school_id=?",school);
        LocalDate missed=week.plusWeeks(1);Instant after= com.szpu.algohub.common.TimeSupport.campusDayStart(week.plusWeeks(2)).plusSeconds(60);
        at(after);jobs.tick(after,false);
        assertThat(db.queryForObject("select status from weekly_report_cycle where school_id=? and week_start=?",String.class,school,missed)).isEqualTo("COMPLETE");
        settings.save(manager,school,new ReportSettingsService.Input(false,7,"23:59",6,true,1,true));
        Instant reenable=com.szpu.algohub.common.TimeSupport.campusDayStart(week.plusWeeks(4)).plusSeconds(60);at(reenable);
        settings.save(manager,school,new ReportSettingsService.Input(true,7,"23:59",6,true,2,false));jobs.tick(reenable,false);
        assertThat(db.queryForObject("select count(*) from weekly_report_cycle where school_id=? and week_start=?",Integer.class,school,week.plusWeeks(3))).isZero();
    }
    @Test void snapshotFilterPaginationAndEmailPrivacyStayWithinScope() {
        long c=cycle();
        for(int i=0;i<23;i++) {long u=user("page-"+i);member(school,u,2026);}
        cycles.snapshot(c,base.plusSeconds(700));
        assertThat((List<?>)cycles.missing(leader,school,2026,week.toString(),"A","","","",1).get("rows")).hasSize(20);
        assertThat((List<?>)cycles.missing(leader,school,2026,week.toString(),"A","","","",2).get("rows")).hasSize(4);
        assertThat(cycles.missing(leader,school,2026,week.toString(),"A","","","",1).toString()).doesNotContain("@test.invalid");
    }
    @Test void snapshotReminderResultDoesNotChangeAfterExpirationOrDisable() {
        long c=cycle();reminders.generate(c,base.plusSeconds(100));
        // Outbox trigger uses the database clock; place the synthetic event at its test business time.
        db.update("update weekly_report_mail_event h set happened_at=? from weekly_report_reminder n where n.notification_id=h.notification_id and n.cycle_id=?",ReportTime.sql(base.plusSeconds(100)),c);
        deliver(base.plusSeconds(650));
        db.update("update weekly_report_mail_event h set happened_at=? from weekly_report_reminder n where n.notification_id=h.notification_id and n.cycle_id=? and h.status='SKIPPED'",ReportTime.sql(base.plusSeconds(650)),c);
        cycles.snapshot(c,base.plusSeconds(700));
        assertThat(db.queryForObject("select reminder_status from weekly_report_missing where cycle_id=? and user_id=?",String.class,c,leader)).isEqualTo("PENDING");
        at(base.plusSeconds(800));settings.save(manager,school,new ReportSettingsService.Input(false,7,"23:59",6,true,1,true));
        assertThat(db.queryForObject("select reminder_status from weekly_report_missing where cycle_id=? and user_id=?",String.class,c,leader)).isEqualTo("PENDING");
    }
    @Test void zeroLeadAndSchoolMailOptOutStillHaveClearBehavior() {
        settings.save(manager,school,new ReportSettingsService.Input(true,7,"23:59",0,false,0,false));
        var c=db.queryForMap("select * from weekly_report_cycle where school_id=?",school);
        assertThat(c.get("remind_at")).isNull();assertThat(c.get("reminder_done")).isEqualTo(true);
        assertThat(settings.get(manager,school).get("nextReminderAt")).isNull();
    }
    @Test void settingsRejectFractionalHoursAndMalformedSwitchTypes() throws Exception {
        String url="/api/weekly-report-management/"+school+"/settings",token=token(manager);
        for(String body:List.of(
            "{\"enabled\":true,\"dayOfWeek\":7,\"time\":\"22:00\",\"leadHours\":1.5,\"emailEnabled\":true,\"version\":0}",
            "{\"enabled\":1,\"dayOfWeek\":7,\"time\":\"22:00\",\"leadHours\":1,\"emailEnabled\":true,\"version\":0}"))
            mvc.perform(post(url).header("Authorization",token).contentType(MediaType.APPLICATION_JSON).content(body)).andExpect(status().isBadRequest());
        mvc.perform(post(url).header("Authorization",token).contentType(MediaType.APPLICATION_JSON)
            .content("{\"enabled\":true,\"dayOfWeek\":7,\"time\":\"23:59\",\"leadHours\":0,\"emailEnabled\":false,\"version\":0}"))
            .andExpect(status().isOk()).andExpect(jsonPath("$.data.enabled").value(true));
    }
}
