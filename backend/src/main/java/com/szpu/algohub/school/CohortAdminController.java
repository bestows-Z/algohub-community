package com.szpu.algohub.school;

import java.util.Map;
import java.util.Set;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Size;
import org.springframework.web.bind.annotation.*;
import com.szpu.algohub.auth.CurrentUser;
import com.szpu.algohub.common.ApiException;
import com.szpu.algohub.common.ApiResult;

@RestController @RequestMapping("/api/cohort-admin")
public class CohortAdminController {
    private final CohortLeaderService service;
    public CohortAdminController(CohortLeaderService service) { this.service=service; }
    public record Review(boolean approve,@Size(max=255) String reason) {}
    @GetMapping("/scopes") public ApiResult<?> scopes() { return ApiResult.ok(service.scopes(CurrentUser.requireId())); }
    @GetMapping("/{schoolId}/{year}/members") public ApiResult<?> members(@PathVariable long schoolId,@PathVariable int year,
            @RequestParam(defaultValue="") String status,@RequestParam(defaultValue="") String q,@RequestParam(defaultValue="1") int page) {
        return ApiResult.ok(service.members(CurrentUser.requireId(),schoolId,year,status,q,page));
    }
    @PostMapping("/{schoolId}/{year}/members/{id}/review") public ApiResult<?> review(@PathVariable long schoolId,
            @PathVariable int year,@PathVariable long id,@Valid @RequestBody Review input) {
        return ApiResult.ok(service.review(CurrentUser.requireId(),schoolId,year,id,input.approve(),input.reason()));
    }
    @PostMapping("/{schoolId}/{year}/members/{id}/profile") public ApiResult<?> profile(@PathVariable long schoolId,
            @PathVariable int year,@PathVariable long id,@RequestBody Map<String,Object> input) {
        if(!Set.of("className","studentNo").containsAll(input.keySet())) throw ApiException.validation("只能修改班级和学号");
        return ApiResult.ok(service.profile(CurrentUser.requireId(),schoolId,year,id,text(input,"className",64),text(input,"studentNo",32)));
    }
    private String text(Map<String,Object> input,String key,int max) {
        Object value=input.get(key);
        if(value==null) return null;
        if(!(value instanceof String s) || s.length()>max) throw ApiException.validation(key+" 格式或长度无效");
        return (String)value;
    }
}
