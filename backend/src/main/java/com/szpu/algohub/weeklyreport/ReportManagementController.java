package com.szpu.algohub.weeklyreport;

import java.util.*;
import org.springframework.web.bind.annotation.*;
import com.szpu.algohub.auth.CurrentUser;
import com.szpu.algohub.common.ApiResult;

@RestController
@RequestMapping("/api/weekly-report-management/{school}")
public class ReportManagementController {
    private final WeeklyReportService reports;
    private final ReportSettingsService settings;
    private final ReportCycleService cycles;
    private final ReportReminderService reminders;
    public ReportManagementController(WeeklyReportService reports,ReportSettingsService settings,ReportCycleService cycles,ReportReminderService reminders) {
        this.reports=reports;this.settings=settings;this.cycles=cycles;this.reminders=reminders;
    }
    @GetMapping("/availability") public ApiResult<?> availability(@PathVariable long school,@RequestParam(required=false) Integer year) { return ApiResult.ok(settings.availability(CurrentUser.requireId(),school,year)); }
    @GetMapping("/settings") public ApiResult<?> settings(@PathVariable long school) {
        var result=new LinkedHashMap<>(settings.get(CurrentUser.requireId(),school));result.putAll(reminders.status());return ApiResult.ok(result);
    }
    @PostMapping("/settings") public ApiResult<?> saveSettings(@PathVariable long school,@RequestBody ReportSettingsService.Input input) { return ApiResult.ok(settings.save(CurrentUser.requireId(),school,input)); }
    @GetMapping("/reports") public ApiResult<?> reports(@PathVariable long school,@RequestParam(required=false) Integer year,@RequestParam String week,
        @RequestParam(defaultValue="") String status,@RequestParam(defaultValue="") String q,@RequestParam(defaultValue="1") int page) {
        return ApiResult.ok(reports.managed(CurrentUser.requireId(),school,year,week,status,q,page));
    }
    @GetMapping("/reports/{id}") public ApiResult<?> detail(@PathVariable long school,@PathVariable long id,@RequestParam(required=false) Integer year) { return ApiResult.ok(reports.managedDetail(CurrentUser.requireId(),school,year,id)); }
    @PostMapping("/reports/{id}/review") public ApiResult<?> review(@PathVariable long school,@PathVariable long id,@RequestParam(required=false) Integer year,@RequestBody WeeklyReportService.Decision input) { return ApiResult.ok(reports.decide(CurrentUser.requireId(),school,year,id,input)); }
    @GetMapping("/cycles") public ApiResult<?> cycles(@PathVariable long school,@RequestParam(required=false) Integer year) { return ApiResult.ok(cycles.cycles(CurrentUser.requireId(),school,year)); }
    @GetMapping("/missing") public ApiResult<?> missing(@PathVariable long school,@RequestParam(required=false) Integer year,@RequestParam String week,
        @RequestParam(defaultValue="") String className,@RequestParam(defaultValue="") String q,@RequestParam(defaultValue="") String late,
        @RequestParam(defaultValue="") String reminder,@RequestParam(defaultValue="1") int page) {
        return ApiResult.ok(cycles.missing(CurrentUser.requireId(),school,year,week,className,q,late,reminder,page));
    }
    @GetMapping("/reminders") public ApiResult<?> reminders(@PathVariable long school,@RequestParam String week) {return ApiResult.ok(reminders.overview(CurrentUser.requireId(),school,week));}
}
