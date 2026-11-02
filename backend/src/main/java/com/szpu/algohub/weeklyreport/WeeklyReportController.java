package com.szpu.algohub.weeklyreport;

import java.util.Map;
import org.springframework.web.bind.annotation.*;
import com.szpu.algohub.auth.CurrentUser;
import com.szpu.algohub.common.ApiResult;

@RestController
@RequestMapping("/api/weekly-reports")
public class WeeklyReportController {
    private final WeeklyReportService reports;
    public WeeklyReportController(WeeklyReportService reports) { this.reports=reports; }
    @GetMapping("/context") public ApiResult<Map<String,Object>> context() { return ApiResult.ok(reports.context(CurrentUser.requireId())); }
    @GetMapping public ApiResult<Map<String,Object>> mine(@RequestParam(defaultValue="1") int page) { return ApiResult.ok(reports.mine(CurrentUser.requireId(),page)); }
    @GetMapping("/find") public ApiResult<Map<String,Object>> find(@RequestParam String week) { return ApiResult.ok(reports.find(CurrentUser.requireId(),week)); }
    @GetMapping("/{id}") public ApiResult<Map<String,Object>> own(@PathVariable long id) { return ApiResult.ok(reports.own(CurrentUser.requireId(),id)); }
    @PostMapping("/draft") public ApiResult<Map<String,Object>> save(@RequestBody WeeklyReportService.Draft input) { return ApiResult.ok(reports.save(CurrentUser.requireId(),input,false)); }
    @PostMapping("/submit") public ApiResult<Map<String,Object>> submit(@RequestBody WeeklyReportService.Draft input) { return ApiResult.ok(reports.save(CurrentUser.requireId(),input,true)); }
    @DeleteMapping("/{id}") public ApiResult<Void> delete(@PathVariable long id,@RequestParam int version) { reports.delete(CurrentUser.requireId(),id,version);return ApiResult.ok(null); }
}
