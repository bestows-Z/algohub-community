package com.szpu.algohub.admin;

import java.security.SecureRandom;
import java.util.List;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.DeleteMapping;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.szpu.algohub.common.ApiException;
import com.szpu.algohub.common.ApiResult;
import com.szpu.algohub.school.School;
import com.szpu.algohub.school.SchoolMapper;

/**
 * 管理员查看、更换学校邀请码（/api/admin/** 只给管理员）。同学填对邀请码直接加入学校，没填码的进待审核；
 * 码发到班群，泄露了就换一个，旧码立刻失效。
 */
@RestController
@RequestMapping("/api/admin/schools")
public class AdminSchoolController {

    /** 去掉容易看错的 0/O、1/I/L */
    private static final String ALPHABET = "ABCDEFGHJKMNPQRSTUVWXYZ23456789";
    private static final SecureRandom RANDOM = new SecureRandom();

    private final SchoolMapper schools;
    private final JdbcTemplate db;

    public AdminSchoolController(SchoolMapper schools, JdbcTemplate db) {
        this.schools = schools;
        this.db = db;
    }

    public record SchoolInput(@NotBlank @Size(max=128) String name) {}

    @PostMapping
    public ApiResult<Long> create(@Valid @RequestBody SchoolInput input) {
        return ApiResult.ok(db.queryForObject("insert into school(name) values (?) returning id", Long.class, input.name().trim()));
    }

    @PostMapping("/{id}")
    public ApiResult<Void> rename(@PathVariable Long id, @Valid @RequestBody SchoolInput input) {
        if (db.update("update school set name=? where id=?", input.name().trim(), id) == 0) throw ApiException.notFound("学校不存在");
        return ApiResult.ok(null);
    }

    @DeleteMapping("/{id}/invite-code")
    public ApiResult<Void> disableInvite(@PathVariable Long id) {
        if (schools.updateInviteCode(id, null) == 0) throw ApiException.notFound("学校不存在");
        return ApiResult.ok(null);
    }

    @DeleteMapping("/{id}")
    @Transactional
    public ApiResult<Void> delete(@PathVariable Long id) {
        if (db.queryForList("select id from school where id=? for update", id).isEmpty()) throw ApiException.notFound("学校不存在");
        for (String table : List.of("school_member", "problem_list", "training_activity", "announcement", "school_administrator", "school_cohort_leader")) {
            if (db.queryForObject("select count(*) from " + table + " where school_id=?", Long.class, id) > 0)
                throw ApiException.conflict("学校已有成员、教学数据、公告或管理员授权，不能删除；可以停用邀请码");
        }
        db.update("delete from school where id=?", id);
        return ApiResult.ok(null);
    }

    public record SchoolInvite(Long id, String name, String inviteCode) {
    }

    @GetMapping
    public ApiResult<List<SchoolInvite>> list() {
        return ApiResult.ok(schools.findAll().stream().map(s -> new SchoolInvite(s.getId(), s.getName(), s.getInviteCode())).toList());
    }

    @PostMapping("/{id}/invite-code")
    public ApiResult<SchoolInvite> regenerate(@PathVariable Long id) {
        School school = schools.findById(id);
        if (school == null) {
            throw ApiException.notFound("学校不存在");
        }
        StringBuilder code = new StringBuilder();
        for (int i = 0; i < 8; i++) {
            code.append(ALPHABET.charAt(RANDOM.nextInt(ALPHABET.length())));
        }
        schools.updateInviteCode(id, code.toString());
        return ApiResult.ok(new SchoolInvite(id, school.getName(), code.toString()));
    }
}
