package com.szpu.algohub.problem;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import com.szpu.algohub.platform.Platform;
import com.szpu.algohub.platform.PlatformAdapter;
import com.szpu.algohub.platform.PlatformDtos.CatalogProblem;
import com.szpu.algohub.platform.PlatformDtos.FetchedProblem;
import com.szpu.algohub.platform.PlatformRegistry;

/**
 * 题库同步：把平台公开的题目清单入库。
 *
 * 之前 problem 表里只有“有人做过的题”，推荐与标签统计都受限于此；
 * 同步题库后可以在完整题目集合上做推荐，同时明确区分：
 * 题库数据来自平台公开接口，难度与标签沿用平台原值（AtCoder 为第三方估计），不做跨平台换算。
 */
@Service
public class ProblemCatalogService {

    private static final Logger log = LoggerFactory.getLogger(ProblemCatalogService.class);
    private static final int BATCH = 500;

    private final PlatformRegistry registry;
    private final ProblemMapper problemMapper;
    private final JdbcTemplate jdbcTemplate;
    private final NowcoderWriteTransactions writes;

    public ProblemCatalogService(PlatformRegistry registry, ProblemMapper problemMapper,
                                 JdbcTemplate jdbcTemplate, NowcoderWriteTransactions writes) {
        this.writes = writes;
        this.registry = registry;
        this.problemMapper = problemMapper;
        this.jdbcTemplate = jdbcTemplate;
    }

    /** 同步单个平台的题库，返回处理条数。 */
    public Map<String, Object> syncCatalog(Platform platform) { return syncCatalog(platform, Runnable::run); }

    public Map<String, Object> syncCatalog(Platform platform, java.util.function.Consumer<Runnable> save) {
        PlatformAdapter adapter = registry.require(platform);
        List<CatalogProblem> catalog = adapter.fetchProblemCatalog();
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("platform", platform.name());
        if (catalog.isEmpty()) {
            result.put("fetched", 0);
            result.put("note", platform.getDisplayName() + " 没有可用的公开题库接口，未做任何写入");
            return result;
        }

        int written = 0;
        for (int i = 0; i < catalog.size(); i += BATCH) {
            List<CatalogProblem> chunk = catalog.subList(i, Math.min(i + BATCH, catalog.size()));
            save.accept(() -> writes.execute(platform.name(), () -> {
                upsertChunk(platform, chunk);
                insertTags(platform, chunk);
                return null;
            }));
            written += chunk.size();
        }
        int tags = catalog.stream().mapToInt(item -> item.problem().tags() == null ? 0 :
                (int) item.problem().tags().stream().filter(t -> t != null && !t.isBlank()).count()).sum();

        result.put("fetched", catalog.size());
        result.put("written", written);
        result.put("tagLinks", tags);
        result.put("catalogSize", problemMapper.countCatalog(platform.name()));
        log.info("{} 题库同步完成：拉取 {} 题，写入 {} 条，标签关系 {} 条",
                platform.getDisplayName(), catalog.size(), written, tags);
        return result;
    }

    private int upsertChunk(Platform platform, List<CatalogProblem> chunk) {
        String sql = """
                insert into problem (platform, problem_key, title, url, difficulty_raw, difficulty_label,
                                     difficulty_source, difficulty_value, solved_count, tags_complete,
                                     catalog_synced_at, updated_at)
                values (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, now(), now())
                on conflict (platform, problem_key) do update
                   set title = coalesce(excluded.title, problem.title),
                       url = coalesce(excluded.url, problem.url),
                       difficulty_raw = coalesce(excluded.difficulty_raw, problem.difficulty_raw),
                       difficulty_label = coalesce(excluded.difficulty_label, problem.difficulty_label),
                       difficulty_source = coalesce(excluded.difficulty_source, problem.difficulty_source),
                       difficulty_value = coalesce(excluded.difficulty_value, problem.difficulty_value),
                       solved_count = coalesce(excluded.solved_count, problem.solved_count),
                       tags_complete = problem.tags_complete or excluded.tags_complete,
                       catalog_synced_at = now(),
                       updated_at = now()
                """;
        List<Object[]> args = new ArrayList<>();
        for (CatalogProblem item : chunk) {
            FetchedProblem p = item.problem();
            args.add(new Object[]{platform.name(), p.problemKey(), p.title(), p.url(),
                    p.difficultyRaw(), p.difficultyLabel(), p.difficultySource(),
                    item.difficultyValue(), item.solvedCount(), p.tagsComplete()});
        }
        int[] counts = jdbcTemplate.batchUpdate(sql, args);
        return counts.length;
    }

    /** 标签按 (题目, 原始标签) 幂等写入，重复同步不会产生重复标签。 */
    private int insertTags(Platform platform, List<CatalogProblem> catalog) {
        String sql = """
                insert into problem_tag (problem_id, tag_raw, tag_source)
                select p.id, ?, ?
                  from problem p
                 where p.platform = ? and p.problem_key = ?
                on conflict (problem_id, tag_raw) do nothing
                """;
        List<Object[]> args = new ArrayList<>();
        for (CatalogProblem item : catalog) {
            FetchedProblem p = item.problem();
            if (p.tags() == null) {
                continue;
            }
            String source = "THIRD_PARTY".equals(p.difficultySource()) ? "THIRD_PARTY" : "OFFICIAL";
            for (String tag : p.tags()) {
                if (tag != null && !tag.isBlank()) {
                    args.add(new Object[]{tag.trim(), source, platform.name(), p.problemKey()});
                }
            }
        }
        if (args.isEmpty()) {
            return 0;
        }
        int total = 0;
        for (int i = 0; i < args.size(); i += BATCH) {
            total += jdbcTemplate.batchUpdate(sql, args.subList(i, Math.min(i + BATCH, args.size()))).length;
        }
        return total;
    }

    /** 哪些平台支持题库同步，供管理界面展示。 */
    public List<String> supportedPlatforms() {
        List<String> supported = new ArrayList<>();
        for (PlatformAdapter adapter : registry.all()) {
            if (!adapter.fetchProblemCatalogSupported()) {
                continue;
            }
            supported.add(adapter.platform().name());
        }
        return supported;
    }
}
