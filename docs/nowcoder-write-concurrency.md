# 牛客并发写入协调

账号提交同步、题库批量更新和 Tracker 缓存映射会同时更新 problem、user_solved_problem。各来源的题目顺序不同，Tracker 即使不改变题目字段，ON CONFLICT 也会锁住冲突行；仅跳过无变化更新不能避免循环等待。

三条路径现在在短事务开始时获取相同的 PostgreSQL 事务级 advisory lock，先于账号、任务、题目和通过记录行锁。提交或回滚自动释放，多 JVM 也使用同一把数据库锁。账号身份和执行编号仍在持锁后重新核对，失去任务所有权时整批放弃。

外站请求不持有此锁；题库仍按 500 条一批提交。仅牛客共享写入段串行，其他平台不使用此锁。此方案优先确保现有映射和游标原子性；如果以后增加牛客数据写入入口，也必须遵守相同边界。没有数据库结构或数据迁移，应用回滚不需要恢复数据库。

NowcoderWriteConcurrencyTest 使用三个并发连接分别调用真实账号同步、题库更新和 Tracker 合并，确认它们等待协调锁时未提前锁住账号/任务，释放后全部完成、提交/通过记录不重复且游标完成；另验证异常回滚释放锁、不留下部分状态。SyncFencingTest 继续验证租约接管和账号变更时旧结果不能覆盖新结果。

同时修复现有 CI 依赖扫描发现的 GHSA-g3hv-75wg-m249：仅将 @vue/language-core 使用的 minimatch 覆盖到 11.0.1。新 minimatch 及 @isaacs/nonrecursive-glob-match 使用 BlueOak-1.0.0，brace-expansion 和 balanced-match 使用 MIT，均为开发工具依赖；构建时许可证生成器继续收集对应许可。此覆盖不涉及前端业务依赖升级，需保持类型检查和构建验证。
