package com.szpu.algohub.problem;

import com.szpu.algohub.platform.*;
import com.szpu.algohub.platform.PlatformDtos.*;
import com.szpu.algohub.statistics.HistoryMapper;
import com.szpu.algohub.statistics.SubmissionMapper;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.annotation.Transactional;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

@SpringBootTest
@Transactional
class ProblemRatingRefreshTest {
    @Autowired JdbcTemplate jdbc;
    @Autowired NowcoderWriteTransactions writes;
    @Autowired ProblemMapper problems;
    @Autowired ProblemService problemService;
    @Autowired SubmissionMapper submissions;
    @Autowired HistoryMapper history;
    @Autowired org.mybatis.spring.SqlSessionTemplate sqlSession;

    @ParameterizedTest
    @EnumSource(value = Platform.class, names = {"CODEFORCES", "ATCODER", "NOWCODER"})
    void oldSubmissionsShowNewAndRevisedRatingsWithoutResubmission(Platform platform) {
        String key = "rating-test-" + UUID.randomUUID();
        Long user = jdbc.queryForObject("insert into app_user(username,password_hash,display_name) values (?,'x','rating') returning id",
                Long.class, key);
        Long account = jdbc.queryForObject("insert into platform_account(user_id,platform,handle,platform_uid,ownership_status) values (?,?,?,?,'VERIFIED') returning id",
                Long.class, user, platform.name(), key, key);
        String source = platform == Platform.ATCODER ? "THIRD_PARTY" : "OFFICIAL";
        var unrated = new FetchedProblem(key, "比赛题", "https://example.com", null, null, source, List.of(), false);
        Long problem = problemService.upsert(platform, unrated);
        submissions.upsert(account, problem, platform.name(), "old", "AC", true,
                Instant.parse("2020-01-01T00:00:00Z"), "EXACT", "C++", null);
        assertThat(history.rows(user, null, true, null, null, 10, 0).getFirst().get("difficulty")).isNull();

        var registry = mock(PlatformRegistry.class);
        var adapter = mock(PlatformAdapter.class);
        when(registry.require(platform)).thenReturn(adapter);
        var service = new ProblemCatalogService(registry, problems, jdbc, writes);
        for (int rating : new int[]{1600, 1800}) {
            String label = platform == Platform.CODEFORCES ? "CF " + rating
                    : platform == Platform.ATCODER ? "AtCoder Problems 估计 " + rating : String.valueOf(rating);
            var rated = new FetchedProblem(key, "比赛题", "https://example.com", String.valueOf(rating), label, source, List.of(), false);
            when(adapter.fetchProblemCatalog()).thenReturn(List.of(new CatalogProblem(rated, rating, null)));
            service.syncCatalog(platform);
            // 生产中页面刷新是新的请求/SqlSession；测试事务中显式结束上一次查询缓存。
            sqlSession.clearCache();
            assertThat(history.rows(user, null, true, null, null, 10, 0).getFirst().get("difficulty")).isEqualTo(label);
            assertThat(history.publicRows(user, null, true, null, null, 10, 0).getFirst().get("difficulty")).isEqualTo(label);
            assertThat(problems.findById(problem).getDifficultyValue()).isEqualTo(rating);
        }
        when(adapter.fetchProblemCatalog()).thenReturn(List.of(new CatalogProblem(unrated, null, null)));
        service.syncCatalog(platform);
        sqlSession.clearCache();
        problemService.upsert(platform, new FetchedProblem(key, "比赛题", null, null, null, "NONE", List.of(), false));
        assertThat(problems.findById(problem).getDifficultyValue()).isEqualTo(1800);
        assertThat(problems.findById(problem).getDifficultySource()).isEqualTo(source);
        assertThat(submissions.countByAccount(account)).isEqualTo(1);
        // 普通提交同步得到后续调分时，标签、数值筛选同时更新。
        problemService.upsert(platform, new FetchedProblem(key, "比赛题", null, "1900", "1900", source, List.of(), false));
        assertThat(problems.findById(problem).getDifficultyValue()).isEqualTo(1900);
    }
}
