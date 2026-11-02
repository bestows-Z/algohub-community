package com.szpu.algohub.sync;

import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.function.Supplier;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import com.szpu.algohub.account.AvatarService;
import com.szpu.algohub.account.CredentialCipher;
import com.szpu.algohub.account.PlatformAccount;
import com.szpu.algohub.account.PlatformAccountMapper;
import com.szpu.algohub.account.PlatformCredentialMapper;
import com.szpu.algohub.common.ApiException;
import com.szpu.algohub.common.ErrorKind;
import com.szpu.algohub.common.Redaction;
import com.szpu.algohub.config.AppProperties;
import com.szpu.algohub.contest.ContestService;
import com.szpu.algohub.platform.Platform;
import com.szpu.algohub.platform.PlatformAdapter;
import com.szpu.algohub.platform.PlatformDtos.FetchedSubmission;
import com.szpu.algohub.platform.PlatformDtos.PlatformCapability;
import com.szpu.algohub.platform.PlatformDtos.RatedContest;
import com.szpu.algohub.platform.PlatformDtos.SubmissionPage;
import com.szpu.algohub.platform.PlatformDtos.SyncContext;
import com.szpu.algohub.platform.PlatformDtos.TotalsSnapshot;
import com.szpu.algohub.platform.PlatformRegistry;
import com.szpu.algohub.problem.ProblemCatalogService;
import com.szpu.algohub.problem.ProblemService;
import com.szpu.algohub.statistics.ContestParticipationMapper;
import com.szpu.algohub.statistics.DailyStatMapper;
import com.szpu.algohub.statistics.SolvedMapper;
import com.szpu.algohub.statistics.SubmissionMapper;
import com.szpu.algohub.statistics.TotalSnapshotMapper;

/**
 * 同步执行逻辑：抓取 → 清洗去重 → 入库 → 推进游标 → 重算统计。
 * 同步失败保留旧数据，不把失败结果写成零。
 *
 * 事务边界：外站请求一律在数据库事务之外。
 * - 开始：短事务核对仍持有任务（执行编号未变），再把账号标为同步中；
 * - 抓取：不持有数据库连接，慢站点不会占着连接或行锁；
 * - 保存：一个短事务里先锁账号行、再锁任务行，核对执行编号与账号标识，然后一次性写入刷题数据、
 *   游标、统计、账号状态与任务状态。租约过期被接管、账号在抓取期间解绑或改了标识，都整批放弃，
 *   不会回退游标、重复计数或覆盖新执行的结果。
 * 锁顺序固定为"账号行 → 任务行"，与解绑时级联删除的顺序一致，避免死锁。
 *
 * 数据项（平台统计题数 / 逐题通过列表 / 提交历史）各自记录成败：一项成功、另一项失败时账号显示"部分成功"，
 * 失败的一项保留上次成功的数据与时间，不写成零。
 */
@Service
public class SyncService {

    private static final Logger log = LoggerFactory.getLogger(SyncService.class);
    public static final String CURSOR_FORWARD = "SUBMISSION_FORWARD";
    private static final String LUOGU_RECHECK_FROM = "LUOGU_RECHECK_FROM";
    /** 平台返回限流后，同平台所有任务一起冷却的时长。 */
    static final long PLATFORM_COOL_DOWN_SECONDS = 300;

    private final PlatformRegistry registry;
    private final PlatformAccountMapper accountMapper;
    private final PlatformCredentialMapper credentialMapper;
    private final CredentialCipher cipher;
    private final ProblemService problemService;
    private final SubmissionMapper submissionMapper;
    private final SolvedMapper solvedMapper;
    private final DailyStatMapper dailyStatMapper;
    private final TotalSnapshotMapper snapshotMapper;
    private final SyncTaskMapper taskMapper;
    private final SyncCursorMapper cursorMapper;
    private final PlatformThrottleMapper throttleMapper;
    private final AccountDataStatusMapper dataStatusMapper;
    private final ContestParticipationMapper contestMapper;
    private final ContestService contestService;
    private final ProblemCatalogService catalogService;
    private final AppProperties properties;
    private final AvatarService avatars;
    private final TransactionTemplate tx;
    private final com.szpu.algohub.problem.NowcoderWriteTransactions writes;

    public SyncService(PlatformRegistry registry, PlatformAccountMapper accountMapper,
                       PlatformCredentialMapper credentialMapper, CredentialCipher cipher,
                       ProblemService problemService, SubmissionMapper submissionMapper,
                       SolvedMapper solvedMapper, DailyStatMapper dailyStatMapper,
                       TotalSnapshotMapper snapshotMapper, SyncTaskMapper taskMapper,
                       SyncCursorMapper cursorMapper, PlatformThrottleMapper throttleMapper,
                       AccountDataStatusMapper dataStatusMapper, ContestParticipationMapper contestMapper,
                       ContestService contestService, ProblemCatalogService catalogService,
                       AppProperties properties, AvatarService avatars,
                       PlatformTransactionManager transactionManager,
                       com.szpu.algohub.problem.NowcoderWriteTransactions writes) {
        this.writes = writes;
        this.registry = registry;
        this.accountMapper = accountMapper;
        this.credentialMapper = credentialMapper;
        this.cipher = cipher;
        this.problemService = problemService;
        this.submissionMapper = submissionMapper;
        this.solvedMapper = solvedMapper;
        this.dailyStatMapper = dailyStatMapper;
        this.snapshotMapper = snapshotMapper;
        this.taskMapper = taskMapper;
        this.cursorMapper = cursorMapper;
        this.throttleMapper = throttleMapper;
        this.dataStatusMapper = dataStatusMapper;
        this.contestMapper = contestMapper;
        this.contestService = contestService;
        this.catalogService = catalogService;
        this.properties = properties;
        this.avatars = avatars;
        this.tx = new TransactionTemplate(transactionManager);
    }

    /** 入队账号同步；同一账号已有待执行或执行中的任务时合并为一个（部分唯一索引保证）。 */
    public boolean enqueueAccountSync(Long accountId, String platform, int priority) {
        PlatformAccount account = accountMapper.findById(accountId);
        if (account != null && "BROWSER".equals(account.getAuthMode())) return false;
        SyncTask task = new SyncTask();
        task.setTaskType("ACCOUNT_SYNC");
        task.setPlatform(platform);
        task.setAccountId(accountId);
        task.setPriority(priority);
        task.setMaxAttempt(5);
        boolean queued = taskMapper.enqueue(task) > 0;
        if (queued) {
            accountMapper.markAttempt(accountId, "QUEUED");
        }
        return queued;
    }

    /** 题库同步：按平台入队，同一平台已有待执行任务时不重复排队。 */
    public void enqueueCatalogRefresh(String platform) {
        if (taskMapper.countActiveByTypeAndPlatform("PROBLEM_CATALOG", platform) > 0) {
            return;
        }
        SyncTask task = new SyncTask();
        task.setTaskType("PROBLEM_CATALOG");
        task.setPlatform(platform);
        task.setPriority(150);
        task.setMaxAttempt(3);
        taskMapper.enqueue(task);
    }

    public void enqueueContestRefresh() {
        if (taskMapper.countActiveGlobal("CONTEST_REFRESH") > 0) {
            return;
        }
        SyncTask task = new SyncTask();
        task.setTaskType("CONTEST_REFRESH");
        task.setPriority(50);
        task.setMaxAttempt(3);
        taskMapper.enqueue(task);
    }

    /**
     * 执行一个已领取的任务（带执行编号）。异常在此分类并写入任务状态；
     * 已无权保存结果时整批放弃，不改写任何状态。
     */
    public void execute(SyncTask task) {
        long start = System.currentTimeMillis();
        RunProgress progress = new RunProgress();
        try {
            switch (task.getTaskType()) {
                case "ACCOUNT_SYNC", "HISTORY_BACKFILL" -> {
                    if (!syncAccount(task, progress)) {
                        // 这一轮取完了：顺路刷新头像（24 小时内查过就跳过），失败只记日志，不影响同步结果
                        avatars.refreshIfDue(task.getAccountId());
                    }
                }
                case "CONTEST_REFRESH" -> {
                    contestService.refreshAll(write -> fenced(task, null, () -> { write.run(); return null; }));
                    complete(task, start);
                }
                case "PROBLEM_CATALOG" -> {
                    catalogService.syncCatalog(Platform.parse(task.getPlatform()),
                            write -> fenced(task, null, () -> { write.run(); return null; }));
                    complete(task, start);
                }
                default -> throw new ApiException(ErrorKind.UNSUPPORTED, "未知任务类型: " + task.getTaskType());
            }
        } catch (SyncAbandonedException e) {
            log.info("同步任务 {} 放弃本次结果：{}", task.getId(), e.getMessage());
            if (e.isReleasable()) {
                release(task);
            }
        } catch (Exception e) {
            fail(task, e, start, progress);
        }
    }

    /** 一次账号同步的过程：正在取哪一项、本轮哪些数据项已成功保存、哪些项失败但不影响其他项。 */
    static final class RunProgress {
        private String currentItem;
        private final Set<String> succeeded = new LinkedHashSet<>();
        private final Map<String, ApiException> failed = new LinkedHashMap<>();

        void attempt(String item) {
            currentItem = item;
        }

        void succeeded(String item) {
            succeeded.add(item);
        }

        void failed(String item, ApiException error) {
            failed.put(item, error);
        }
    }

    private void fail(SyncTask task, Exception e, long start, RunProgress progress) {
        ErrorKind kind = e instanceof ApiException api ? api.getKind() : ErrorKind.INTERNAL;
        String message = truncate(e.getMessage());
        long backoff = backoffSeconds(kind, task.getAttempt());
        if (kind == ErrorKind.RATE_LIMITED && task.getPlatform() != null) {
            // 限流是平台层面的事实：不论本次执行是否还持有任务，同平台都一起冷却
            throttleMapper.coolDown(task.getPlatform(), PLATFORM_COOL_DOWN_SECONDS, message);
        }
        boolean requiresCredential = task.getPlatform() != null && registry.find(Platform.parse(task.getPlatform()))
                .map(adapter -> adapter.capability().requiresCredential()).orElse(false);
        try {
            tx.executeWithoutResult(status -> {
                if (task.getAccountId() != null && accountMapper.lockForSync(task.getAccountId()) == null) {
                    throw new SyncAbandonedException("账号已解绑");
                }
                requireOwned(task);
                taskMapper.markFailure(task.getId(), task.getRunToken(), kind.name(), message, kind.isRetryable(),
                        backoff, System.currentTimeMillis() - start);
                if (task.getAccountId() == null) {
                    return;
                }
                Long accountId = task.getAccountId();
                if (progress.currentItem != null) {
                    dataStatusMapper.failure(accountId, progress.currentItem,
                            kind == ErrorKind.CREDENTIAL_INVALID ? "NEEDS_CREDENTIAL" : "FAILED", kind.name(), message);
                }
                if (kind == ErrorKind.CREDENTIAL_INVALID) {
                    // 源站判定登录态无效：不再拿它反复请求，等本人重新设置
                    credentialMapper.updateStatus(accountId, "INVALID");
                }
                if (kind == ErrorKind.CREDENTIAL_INVALID && requiresCredential) {
                    // 没有登录态就什么也取不到的平台：暂停自动同步，等待重新授权
                    accountMapper.markFailure(accountId, "PAUSED", kind.name(), message);
                } else if (!progress.succeeded.isEmpty()) {
                    // 本轮已有数据项保存成功：部分成功，不把整个账号标成失败
                    accountMapper.markPartial(accountId, kind.name(), message);
                } else {
                    accountMapper.markFailure(accountId, "FAILED", kind.name(), message);
                }
            });
            log.warn("同步任务 {} 失败({}): {}", task.getId(), kind, message);
        } catch (SyncAbandonedException lost) {
            // 新的执行已接管：它的结果为准，旧执行的失败不能把任务或账号改成失败
            log.info("同步任务 {} 失败，但本次执行已不再持有任务，不改写状态：{}", task.getId(), lost.getMessage());
        }
    }

    private long backoffSeconds(ErrorKind kind, int attempt) {
        long base = switch (kind) {
            case RATE_LIMITED -> 300;
            case NETWORK, UPSTREAM_ERROR -> 120;
            default -> 600;
        };
        return Math.min(base * Math.max(1, attempt), 3600);
    }

    /**
     * 同步一个账号，返回是否还有后续页（有则同一任务留在队列里继续）。
     * 任务必须是本次执行领取的：开始、保存与收尾都核对执行编号。
     */
    public boolean syncAccount(SyncTask task) {
        return syncAccount(task, new RunProgress());
    }

    private boolean syncAccount(SyncTask task, RunProgress progress) {
        long start = System.currentTimeMillis();
        PlatformAccount account = accountMapper.findById(task.getAccountId());
        if (account == null) {
            throw ApiException.notFound("账号不存在或已解绑");
        }
        if ("BROWSER".equals(account.getAuthMode())) return true;
        Platform platform = Platform.parse(account.getPlatform());
        PlatformAdapter adapter = registry.require(platform);
        PlatformCapability capability = adapter.capability();
        // 短事务登记尝试：仍持有任务才把账号标为同步中
        fenced(task, account, () -> {
            accountMapper.updateHistorySupport(account.getId(), capability.submissionHistory());
            accountMapper.markAttempt(account.getId(), "RUNNING");
            return null;
        });

        String credential = loadCredential(account.getId(), capability);
        boolean hasCredential = credential != null && !credential.isBlank();
        if (platform == Platform.LUOGU && (!hasCredential || cursorMapper.findValue(account.getId(), CURSOR_FORWARD) == null)) {
            // 公开题单与需登录的评测记录独立：记录鉴权失败时，仍保有公开总量与每日活动。
            TotalsSnapshot publicTotals = null;
            progress.attempt(AccountDataStatusMapper.TOTALS);
            try {
                publicTotals = adapter.fetchTotals(context(account, null, null, null));
            } catch (ApiException e) {
                if (!hasCredential) {
                    throw e;
                }
                // 公开题单失败不挡评测记录：记下这一项失败，继续取历史
                progress.failed(AccountDataStatusMapper.TOTALS, e);
                log.warn("洛谷账号 {} 公开题单本轮失败，继续尝试评测记录", account.getId());
            }
            if (!hasCredential) {
                saveTotals(task, account, publicTotals, true, start, progress);
                return false;
            }
            if (publicTotals != null) {
                saveTotals(task, account, publicTotals, false, start, progress);
            }
        }
        if (capability.submissionHistory()) {
            return syncHistory(task, account, adapter, credential, start, progress);
        }
        if (capability.totalsSnapshot()) {
            progress.attempt(AccountDataStatusMapper.TOTALS);
            TotalsSnapshot snapshot = adapter.fetchTotals(context(account, null, credential, null));
            saveTotals(task, account, snapshot, true, start, progress);
            return false;
        }
        throw new ApiException(ErrorKind.UNSUPPORTED, platform.getDisplayName() + " 当前没有可用的同步能力");
    }

    private String loadCredential(Long accountId, PlatformCapability capability) {
        String cipherText = credentialMapper.findCipherText(accountId);
        if (cipherText == null) {
            if (capability.requiresCredential()) {
                throw new ApiException(ErrorKind.CREDENTIAL_INVALID,
                        capability.platform().getDisplayName() + " 需要本人登录态，请先在账号页面提供");
            }
            return null;
        }
        return cipher.decrypt(cipherText);
    }

    private static SyncContext context(PlatformAccount account, String cursor, String credential, Instant recheckFrom) {
        return new SyncContext(account.getId(), account.getHandle(), account.getPlatformUid(), cursor, credential,
                recheckFrom);
    }

    private boolean syncHistory(SyncTask task, PlatformAccount account, PlatformAdapter adapter,
                                String credential, long start, RunProgress progress) {
        Platform platform = Platform.parse(account.getPlatform());
        String cursor = cursorMapper.findValue(account.getId(), CURSOR_FORWARD);
        boolean historyComplete = Boolean.TRUE.equals(cursorMapper.findCompleted(account.getId(), CURSOR_FORWARD));

        // 已完成首次回填后，仅回查最近若干天，处理重判与迟到数据。
        Instant recheckFrom = historyComplete
                ? Instant.now().minus(Duration.ofDays(properties.getSync().getRecheckDays()))
                : null;
        boolean newLuoguRound = false;
        if (platform == Platform.LUOGU && historyComplete) {
            // 固定整轮边界，不能让每页更新的 lastSuccessAt 推进边界而漏掉停机期间的提交。
            if (cursor == null) {
                // 用已收录的提交时间，而非会被公开快照更新的账号成功时间。
                Instant previous = submissionMapper.latestSyncedAcceptedAt(account.getId());
                if (previous == null) {
                    recheckFrom = null;
                } else {
                    Instant overlap = previous.minus(Duration.ofDays(properties.getSync().getRecheckDays()));
                    if (overlap.isBefore(recheckFrom)) {
                        recheckFrom = overlap;
                    }
                }
                newLuoguRound = true;
            } else {
                String saved = cursorMapper.findValue(account.getId(), LUOGU_RECHECK_FROM);
                recheckFrom = saved == null ? null : Instant.parse(saved);
            }
        }

        SyncContext context = context(account, cursor, credential, recheckFrom);
        progress.attempt(AccountDataStatusMapper.HISTORY);
        SubmissionPage page = adapter.fetchSubmissions(context);
        // 历史补不全的平台（力扣只公开最近通过的题）每轮都一并取总量；牛客在每轮第一页取
        String partialNote = adapter.partialHistoryNote();
        TotalsSnapshot nowcoderTotals = null;
        if ((platform == Platform.NOWCODER && cursor == null)
                || (partialNote != null && adapter.capability().totalsSnapshot())) {
            try {
                nowcoderTotals = adapter.fetchTotals(context);
            } catch (ApiException e) {
                progress.failed(AccountDataStatusMapper.TOTALS, e);
                log.warn("账号 {} 总量读取失败，继续保存已获取的提交历史", account.getId());
            }
        }

        // 比赛与 Rating：这一轮历史取完（没有下一页）后取一次。失败不影响已取到的提交，账号记为部分成功。
        List<RatedContest> contests = null;
        if (!page.hasMore() && adapter.contestHistorySupported()) {
            try {
                contests = adapter.fetchContestHistory(context);
            } catch (ApiException e) {
                progress.failed(AccountDataStatusMapper.RATING, e);
                log.warn("账号 {} 比赛与 Rating 本轮读取失败，继续保存提交历史：{}", account.getId(), e.getMessage());
            }
        }

        boolean nowComplete = historyComplete || page.reachedHistoryStart();
        List<RatedContest> ratings = contests;
        Instant luoguBoundary = recheckFrom;
        boolean saveBoundary = newLuoguRound;
        TotalsSnapshot totals = nowcoderTotals;
        int stored = fenced(task, account, () -> {
            if (saveBoundary) {
                // 整轮边界与第一页一起提交，后续页沿用
                cursorMapper.save(account.getId(), LUOGU_RECHECK_FROM,
                        luoguBoundary == null ? null : luoguBoundary.toString(), Instant.now(), false);
            }
            if (totals != null) {
                snapshotMapper.insert(account.getId(), totals.solvedTotal(), totals.submissionTotal(), "SYNC",
                        snapshotExtra(totals));
                dataStatusMapper.success(account.getId(), AccountDataStatusMapper.TOTALS, totals.solvedTotal(), null);
            }
            if (ratings != null) {
                for (RatedContest c : ratings) {
                    contestMapper.upsert(account.getId(), c.contestKey(), c.contestName(), c.place(), c.oldRating(),
                            c.newRating(), c.performance(), c.rated(), c.finishedAt());
                }
                dataStatusMapper.success(account.getId(), AccountDataStatusMapper.RATING, ratings.size(),
                        ratingDetail(ratings));
            }
            int count = 0;
            for (FetchedSubmission submission : page.submissions()) {
                Long problemId = problemService.upsert(platform, submission.problem());
                submissionMapper.upsert(account.getId(), problemId, platform.name(), submission.platformSubId(),
                        submission.verdict(), submission.accepted(), submission.submittedAt(),
                        submission.timePrecision(), submission.language(), submission.contestRef());
                if (submission.accepted() && problemId != null) {
                    solvedMapper.upsert(account.getUserId(), account.getId(), problemId, platform.name(),
                            submission.submittedAt(), "EARLIEST_KNOWN", "SYNC");
                }
                count++;
            }
            // 成功入库后保存适配器返回的续传位置。
            cursorMapper.save(account.getId(), CURSOR_FORWARD, page.nextCursor(), Instant.now(), nowComplete);
            if (nowComplete) {
                solvedMapper.promoteConfidence(account.getId());
            } else {
                solvedMapper.downgradeConfidence(account.getId());
            }
            rebuildSubmissionStats(account.getId());
            dailyStatMapper.deleteSnapshotBased(account.getId());
            if (partialNote != null) {
                // 历史永远补不全：总量的观察增量仍是"新增了几道"的唯一依据，留着
                dailyStatMapper.rebuildSnapshotDelta(account.getId());
            }
            dataStatusMapper.success(account.getId(), AccountDataStatusMapper.HISTORY, count,
                    nowComplete ? "历史已完整" : partialNote != null ? partialNote : "回填中，仍有更早的记录未取");
            accountMapper.markSuccess(account.getId(), nowComplete);
            recordSideFailures(account.getId(), progress);
            if (page.hasMore()) {
                accountMapper.markAttempt(account.getId(), "QUEUED");
            }
            finish(task, page.hasMore(), start);
            return count;
        });
        progress.succeeded(AccountDataStatusMapper.HISTORY);
        if (ratings != null) {
            progress.succeeded(AccountDataStatusMapper.RATING);
        }
        log.info("账号 {}({}) 同步完成，本轮写入 {} 条提交，历史完整={}", account.getHandle(),
                platform.name(), stored, nowComplete);
        return page.hasMore();
    }

    /** 快照附加信息：有难度分布就存分布，否则存适配器给的说明。 */
    private static String snapshotExtra(TotalsSnapshot snapshot) {
        return snapshot.difficultyBreakdown() == null || snapshot.difficultyBreakdown().isEmpty()
                ? snapshot.rawExtra()
                : snapshot.difficultyBreakdown().toString();
    }

    /** 例如"参加 9 场（8 场计分），当前 478，最高 558"；一场都没参加过也照实写，不写成 0 分。 */
    static String ratingDetail(List<RatedContest> contests) {
        if (contests.isEmpty()) {
            return "还没有参加过比赛";
        }
        List<RatedContest> rated = contests.stream()
                .filter(c -> c.rated() && c.newRating() != null)
                .sorted(java.util.Comparator.comparing(RatedContest::finishedAt))
                .toList();
        String text = "参加 " + contests.size() + " 场"
                + (rated.size() == contests.size() ? "" : "（" + rated.size() + " 场计分）");
        if (!rated.isEmpty()) {
            int current = rated.getLast().newRating();
            int max = rated.stream().mapToInt(RatedContest::newRating).max().orElse(current);
            text += "，当前 " + current + "，最高 " + max;
        }
        return text;
    }

    /** 保存总量快照；completeTask 为 true 时同一事务里结束任务。 */
    private void saveTotals(SyncTask task, PlatformAccount account, TotalsSnapshot snapshot,
                            boolean completeTask, long start, RunProgress progress) {
        Platform platform = Platform.parse(account.getPlatform());
        fenced(task, account, () -> {
            // 通过题单只能证明"通过过"，没有提交或 AC 时间，不补造时间。
            if (snapshot.solvedProblems() != null) {
                for (var problem : snapshot.solvedProblems()) {
                    Long problemId = problemService.upsert(platform, problem);
                    solvedMapper.upsert(account.getUserId(), account.getId(), problemId, platform.name(),
                            null, "UNKNOWN", "SYNC");
                }
            }
            snapshotMapper.insert(account.getId(), snapshot.solvedTotal(), snapshot.submissionTotal(),
                    "SYNC", snapshotExtra(snapshot));
            dataStatusMapper.success(account.getId(), AccountDataStatusMapper.TOTALS, snapshot.solvedTotal(), null);
            if (snapshot.solvedProblems() != null && !snapshot.solvedProblems().isEmpty()) {
                dataStatusMapper.success(account.getId(), AccountDataStatusMapper.SOLVED_LIST,
                        snapshot.solvedProblems().size(), "只有通过关系，没有通过时间");
            }
            dailyStatMapper.deleteSnapshotBased(account.getId());
            if (platform != Platform.QOJ) dailyStatMapper.rebuildSnapshotDelta(account.getId());
            snapshot.dailyActivity().forEach((date, value) -> dailyStatMapper.upsertActivity(account.getId(), date, value));
            // 公开快照不改变已经完成的历史回填，也不推进或清空续传游标。
            boolean historyComplete = account.isHistoryComplete() || (platform == Platform.LUOGU
                    && Boolean.TRUE.equals(cursorMapper.findCompleted(account.getId(), CURSOR_FORWARD)));
            accountMapper.markSnapshotSuccess(account.getId(), historyComplete);
            if (completeTask) {
                finish(task, false, start);
            }
            return null;
        });
        progress.succeeded(AccountDataStatusMapper.TOTALS);
        log.info("账号 {}({}) 快照更新：通过 {} 题", account.getHandle(), platform.name(), snapshot.solvedTotal());
    }

    /** 本轮里没挡住主流程的失败项（例如公开总量失败但历史成功）：记下该项失败，账号标为部分成功。 */
    private void recordSideFailures(Long accountId, RunProgress progress) {
        ApiException first = null;
        for (Map.Entry<String, ApiException> entry : progress.failed.entrySet()) {
            ApiException error = entry.getValue();
            dataStatusMapper.failure(accountId, entry.getKey(),
                    error.getKind() == ErrorKind.CREDENTIAL_INVALID ? "NEEDS_CREDENTIAL" : "FAILED",
                    error.getKind().name(), truncate(error.getMessage()));
            if (first == null) {
                first = error;
            }
        }
        if (first != null) {
            accountMapper.markPartial(accountId, first.getKind().name(), truncate(first.getMessage()));
        }
    }

    /** 全局任务收尾；比赛按平台、题库按批次保存时也在事务内核对执行编号。 */
    private void complete(SyncTask task, long start) {
        fenced(task, null, () -> {
            finish(task, false, start);
            return null;
        });
    }

    /** 在保存事务内结束或续排任务。 */
    private void finish(SyncTask task, boolean hasMore, long start) {
        long duration = System.currentTimeMillis() - start;
        if (hasMore) {
            // 复用当前任务，避免 RUNNING 的回填任务被同类唯一索引挡住后续页。
            taskMapper.continueLater(task.getId(), task.getRunToken(), duration);
        } else {
            taskMapper.markSuccess(task.getId(), task.getRunToken(), duration);
        }
    }

    /**
     * 核对后才写：先锁账号行（确认没解绑、标识没变），再锁任务行（确认执行编号没变），
     * 然后在同一个事务里执行写入。任何一项不符都整批回滚。
     */
    private <T> T fenced(SyncTask task, PlatformAccount account, Supplier<T> work) {
        return writes.execute(task.getPlatform(), () -> {
            if (account != null) {
                PlatformAccount current = accountMapper.lockForSync(account.getId());
                if (current == null) {
                    throw new SyncAbandonedException("账号已在同步期间解绑");
                }
                if (!Objects.equals(current.getPlatformUid(), account.getPlatformUid())) {
                    throw new SyncAbandonedException("账号标识在同步期间发生变化", true);
                }
            }
            requireOwned(task);
            return work.get();
        });
    }

    /** 账号标识在抓取期间变了：这次结果作废，但任务仍归本次执行，立即放回队列按新标识重跑。 */
    private void release(SyncTask task) {
        try {
            tx.executeWithoutResult(status -> {
                requireOwned(task);
                taskMapper.release(task.getId(), task.getRunToken());
            });
        } catch (SyncAbandonedException lost) {
            log.info("同步任务 {} 已被接管，交给新的执行", task.getId());
        }
    }

    private void requireOwned(SyncTask task) {
        if (task.getRunToken() == null || taskMapper.lockOwned(task.getId(), task.getRunToken()) == null) {
            throw new SyncAbandonedException("任务已被其他执行接管或已结束");
        }
    }

    /** 重算某账号的逐日统计。原始提交保留，规则调整后可重复执行。 */
    public void rebuildSubmissionStats(Long accountId) {
        dailyStatMapper.deleteSubmissionBased(accountId);
        dailyStatMapper.rebuildSubmissions(accountId);
        dailyStatMapper.rebuildAcSubmissions(accountId);
        dailyStatMapper.rebuildUniqueAc(accountId);
        dailyStatMapper.rebuildFirstAc(accountId);
    }

    /** 写进任务、账号与数据项的错误说明：先去掉凭据再截断，截断不会把半截令牌留下。 */
    private String truncate(String message) {
        if (message == null) {
            return "未知错误";
        }
        String clean = Redaction.redact(message);
        return clean.length() > 480 ? clean.substring(0, 480) : clean;
    }
}
