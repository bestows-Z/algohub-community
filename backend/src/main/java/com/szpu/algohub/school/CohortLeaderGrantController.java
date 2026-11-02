package com.szpu.algohub.school;

import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import org.springframework.web.bind.annotation.*;
import com.szpu.algohub.auth.CurrentUser;
import com.szpu.algohub.common.ApiResult;

@RestController @RequestMapping("/api/school-admin/{schoolId}/cohort-leaders")
public class CohortLeaderGrantController {
    private final CohortLeaderService service;
    public CohortLeaderGrantController(CohortLeaderService service) { this.service=service; }
    public record Grant(@NotNull @Positive Long userId,@NotNull @Min(2000) @Max(2100) Integer enrollmentYear) {}
    @GetMapping public ApiResult<?> list(@PathVariable long schoolId,@RequestParam(required=false) Integer enrollmentYear) {
        return ApiResult.ok(service.grants(CurrentUser.requireId(),schoolId,enrollmentYear));
    }
    @GetMapping("/candidates") public ApiResult<?> candidates(@PathVariable long schoolId,
            @RequestParam(defaultValue="") String q,@RequestParam(required=false) Integer enrollmentYear) {
        return ApiResult.ok(service.candidates(CurrentUser.requireId(),schoolId,q,enrollmentYear));
    }
    @PostMapping public ApiResult<?> grant(@PathVariable long schoolId,@Valid @RequestBody Grant input) {
        return ApiResult.ok(service.grant(CurrentUser.requireId(),schoolId,input.userId(),input.enrollmentYear()));
    }
    @DeleteMapping("/{id}") public ApiResult<?> revoke(@PathVariable long schoolId,@PathVariable long id) {
        service.revoke(CurrentUser.requireId(),schoolId,id); return ApiResult.ok();
    }
}
