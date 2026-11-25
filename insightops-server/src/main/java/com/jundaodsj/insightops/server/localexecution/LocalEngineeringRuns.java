package com.jundaodsj.insightops.server.localexecution;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.jundaodsj.insightops.businesssystem.application.EngineeringBaselineStore.Snapshot;
import com.jundaodsj.insightops.businesssystem.application.MavenPomFacts;
import com.jundaodsj.insightops.identity.application.ActorContext;
import com.jundaodsj.insightops.infrastructure.config.ResearchModelCatalog;
import com.jundaodsj.insightops.infrastructure.model.DeepSeekCostEstimator;
import com.jundaodsj.insightops.model.application.AgentPlanningModelGateway;
import com.jundaodsj.insightops.model.application.ResearchModel;
import com.jundaodsj.insightops.report.application.BuildEvidence.Role;
import com.jundaodsj.insightops.report.application.EngineeringTaskStore.Readiness;
import com.jundaodsj.insightops.report.application.EngineeringTaskStore.TaskVersion;
import com.jundaodsj.insightops.server.businesssystem.EngineeringBaselineService;
import com.jundaodsj.insightops.server.localexecution.LocalRunStore.Command;
import com.jundaodsj.insightops.server.localexecution.LocalRunStore.Detail;
import com.jundaodsj.insightops.server.localexecution.LocalRunStore.Run;
import com.jundaodsj.insightops.server.localexecution.LocalRunStore.Status;
import com.jundaodsj.insightops.server.report.EngineeringTaskService;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.server.ResponseStatusException;

import javax.sql.DataSource;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.SQLException;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.BooleanSupplier;

final class LocalEngineeringRuns {
    private static final Logger LOG = LoggerFactory.getLogger(LocalEngineeringRuns.class);
    private static final int MAX_CANDIDATE_BUILDS = 3;
    private static final long DATABASE_WORKER_LOCK = 4326153907212011L;

    record ModelOption(String id, String label, String provider) {}
    record Overview(boolean enabled, boolean taskReady, List<LocalWorkspaces.Workspace> workspaces,
                    List<ModelOption> models, List<Run> runs) {
        static Overview disabled() { return new Overview(false, false, List.of(), List.of(), List.of()); }
    }

    private final LocalWorkspaces workspaces;
    private final LocalMavenBuild maven;
    private final LocalRunStore store;
    private final LocalRunArchive archive;
    private final LocalBuildEvidence evidence;
    private final EngineeringTaskService tasks;
    private final EngineeringBaselineService baselines;
    private final ResearchModelCatalog catalog;
    private final AgentPlanningModelGateway gateway;
    private final DeepSeekCostEstimator estimator;
    private final ObjectMapper json;
    private final TransactionTemplate tx;
    private final DataSource dataSource;
    private final ExecutorService worker = Executors.newSingleThreadExecutor(
            Thread.ofPlatform().daemon().name("insightops-local-engineering").factory());
    private final Map<UUID, AtomicBoolean> cancellations = new ConcurrentHashMap<>();
    private Connection databaseLock;

    LocalEngineeringRuns(LocalExecutionProperties config, LocalRunStore store, LocalBuildEvidence evidence,
                         EngineeringTaskService tasks, EngineeringBaselineService baselines, ResearchModelCatalog catalog,
                         AgentPlanningModelGateway gateway, DeepSeekCostEstimator estimator, ObjectMapper json,
                         TransactionTemplate tx, DataSource dataSource) throws IOException {
        this.workspaces = new LocalWorkspaces(config);
        this.maven = new LocalMavenBuild(config, workspaces, json);
        this.archive = new LocalRunArchive(workspaces.data(), json);
        this.store = store;
        this.evidence = evidence;
        this.tasks = tasks;
        this.baselines = baselines;
        this.catalog = catalog;
        this.gateway = gateway;
        this.estimator = estimator;
        this.json = json;
        this.tx = tx;
        this.dataSource = dataSource;
    }

    @PostConstruct
    void initialize() throws SQLException {
        databaseLock = dataSource.getConnection();
        try {
            if (!lock("pg_try_advisory_lock")) throw new IllegalStateException("该本地数据库已有工程执行实例。");
            for (UUID id : store.active()) {
                maven.recover(id);
                store.finish(id, Status.INTERRUPTED, "进程在完成前中断，已保留当时的可取得证据。", null, null, false);
            }
        } catch (RuntimeException | SQLException e) {
            release();
            throw e;
        }
    }

    @PreDestroy
    void close() {
        cancellations.values().forEach(c -> c.set(true));
        worker.shutdown();
        try {
            if (!worker.awaitTermination(6, TimeUnit.SECONDS)) worker.shutdownNow();
        } catch (InterruptedException e) {
            worker.shutdownNow();
            Thread.currentThread().interrupt();
        }
        release();
    }

    private boolean lock(String function) throws SQLException {
        try (var statement = databaseLock.prepareStatement("select " + function + "(?)")) {
            statement.setLong(1, DATABASE_WORKER_LOCK);
            try (var result = statement.executeQuery()) { return result.next() && result.getBoolean(1); }
        }
    }

    private void release() {
        if (databaseLock == null) return;
        try (var connection = databaseLock) { lock("pg_advisory_unlock"); }
        catch (SQLException e) { LOG.warn("Unable to release the local engineering worker lock", e); }
        finally { databaseLock = null; }
    }

    Overview overview(ActorContext actor, UUID report, UUID taskId) throws IOException {
        var task = tasks.version(actor, report, taskId);
        var options = catalog.available().stream().map(m -> new ModelOption(m.id(), m.label(), m.provider())).toList();
        return new Overview(true, ready(task), workspaces.list(actor.workspaceId()), options, store.list(actor, report, taskId));
    }

    Run start(ActorContext actor, UUID report, UUID taskId, Command raw) throws IOException {
        var task = tasks.version(actor, report, taskId);
        if (!ready(task)) throw new IllegalArgumentException("只有已经就绪并绑定了工程基线的任务版本可以本地试改。");
        Command input = validated(raw);
        var previous = store.request(actor, report, taskId, input.requestId());
        if (previous.isPresent()) return replay(previous.get(), input);

        ResearchModel model = catalog.resolve(input.modelChoice());
        var selected = workspaces.check(actor.workspaceId(), input.workspace(), LocalProcess.ACTIVE);
        if (!selected.revision().equals(input.sourceRevision())) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "登记仓库提交已变化，请刷新后重新选择起点。");
        }
        Snapshot baseline = baselines.detail(actor, task.systemId(), task.engineeringBaselineId());
        if (baseline.baseline().version() != task.engineeringBaselineVersion()) throw new IllegalArgumentException("冻结基线版本不匹配。");
        var original = baselines.download(actor, task.systemId(), task.engineeringBaselineId(), MavenPomFacts.PATH);
        String pom = LocalWorkspaces.sha(original.bytes());
        UUID id = UUID.randomUUID();
        if (!store.insert(id, actor, report, taskId, task.engineeringBaselineId(), task.engineeringBaselineVersion(), pom, input)) {
            return store.request(actor, report, taskId, input.requestId()).map(run -> replay(run, input)).orElseThrow(() ->
                    new ResponseStatusException(HttpStatus.CONFLICT, "已有一项本地工程任务在执行，请查看进度后重试。"));
        }
        var run = lookup(actor, report, taskId, id);
        var cancelled = new AtomicBoolean();
        cancellations.put(id, cancelled);
        try {
            worker.execute(() -> execute(actor, task, baseline, run, model, cancelled));
        } catch (RuntimeException e) {
            cancellations.remove(id);
            store.finish(id, Status.INTERRUPTED, "当前进程未能启动本地执行。", null, null, false);
            throw e;
        }
        return run;
    }

    Detail detail(ActorContext actor, UUID report, UUID task, UUID run) {
        var found = lookup(actor, report, task, run);
        return new Detail(found, store.events(run));
    }

    Run cancel(ActorContext actor, UUID report, UUID task, UUID run) {
        var found = lookup(actor, report, task, run);
        if (!found.finished() && store.cancel(run)) {
            var cancelled = cancellations.get(run);
            if (cancelled != null) cancelled.set(true);
        }
        return lookup(actor, report, task, run);
    }

    byte[] evidence(ActorContext actor, UUID report, UUID task, UUID run) throws IOException {
        Detail detail = detail(actor, report, task, run);
        if (!detail.run().finished()) throw new ResponseStatusException(HttpStatus.CONFLICT, "请在本次运行收口后下载原件。");
        return archive.zip(detail);
    }

    byte[] patch(ActorContext actor, UUID report, UUID task, UUID run) throws IOException {
        return archive.patch(lookup(actor, report, task, run));
    }

    private Run lookup(ActorContext actor, UUID report, UUID task, UUID run) {
        tasks.version(actor, report, task);
        return store.find(actor, report, task, run).orElseThrow(() ->
                new ResponseStatusException(HttpStatus.NOT_FOUND, "未找到这次本地执行。"));
    }

    private static boolean ready(TaskVersion task) {
        return task.fields().readiness() == Readiness.READY && task.engineeringBaselineId() != null;
    }

    private static Command validated(Command raw) {
        if (raw == null || raw.requestId() == null || raw.workspace() == null || raw.sourceRevision() == null ||
                !raw.sourceRevision().matches("[0-9a-f]{40}") || raw.modelChoice() == null || raw.modelChoice().isBlank() ||
                raw.instruction() == null || raw.instruction().isBlank() || raw.instruction().length() > 6000 ||
                raw.instruction().indexOf('\0') >= 0) {
            throw new IllegalArgumentException("请选择仓库、完整提交、模型并填写 1 至 6000 字符的本地改动指令。");
        }
        return new Command(raw.requestId(), raw.workspace(), raw.sourceRevision(), raw.modelChoice(),
                raw.instruction().strip(), LocalRepositoryFiles.writeScopes(raw.writeScopes()),
                LocalBuildPlan.ALL.equals(raw.effectivePlan()) ? null : raw.buildPlan());
    }

    private static Run replay(Run old, Command input) {
        if (!old.input().equals(input)) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "该提交标识已经用于其他本地任务输入。");
        }
        return old;
    }

    private void execute(ActorContext actor, TaskVersion task, Snapshot snapshot, Run run, ResearchModel model,
                         AtomicBoolean cancelled) {
        UUID id = run.id();
        BooleanSupplier active = () -> !cancelled.get() && !Thread.currentThread().isInterrupted();
        try {
            if (lookup(actor, run.reportId(), run.taskVersionId(), id).cancelRequested()) cancelled.set(true);
            LocalCodingAgent.requireActive(active);
            perform(actor, task, snapshot, run, model, active);
        } catch (LocalCodingAgent.Halt | IllegalArgumentException | IOException e) {
            stop(id, active, visibleError(e), e);
        } catch (RuntimeException e) {
            stop(id, active, "本地执行未能保存或完成。请查看运行记录。", e);
        } finally {
            cancellations.remove(id);
        }
    }

    private void perform(ActorContext actor, TaskVersion task, Snapshot snapshot, Run run, ResearchModel model,
                         BooleanSupplier active) throws IOException {
        UUID id = run.id();
        var directory = archive.create(id);
        store.stage(id, Status.ANALYZING, "核对 Git 提交和冻结工程原件。");
        var frozen = new LocalWorkspaces.Workspace(run.input().workspace(), run.input().sourceRevision());
        var root = workspaces.prepare(actor.workspaceId(), frozen, run.pomSha256(), directory, active);
        verifyFrozenSources(snapshot, root);
        var files = new LocalRepositoryFiles(root, run.input().writeScopes());
        store.event(id, "WORKSPACE", "已建立独立工作副本并核对基线", Map.of("commit", frozen.revision(),
                "workspace", frozen.id(), "pomSha256", run.pomSha256(), "baselineId", run.baselineId(),
                "taskVersionId", run.taskVersionId(), "writeScopes", run.input().writeScopes()));
        store.stage(id, Status.BASELINE, "调用模型前，使用隔离环境验证原始版本的 Maven test。");
        var base = evidence.inspect(actor, run.reportId(), maven.run(id, "baseline", "", files, directory, run.input().effectivePlan(), active),
                Role.BASELINE, frozen.revision());
        store.event(id, "BUILD", "基线 Maven 结果", base.observation());
        LocalCodingAgent.requireActive(active);
        if (base.ran() == 0 || base.build().execution().end() != LocalProcess.End.EXITED) {
            throw new LocalCodingAgent.Halt("基线未取得实际测试结果，尚未发起模型分析。" + base.issue());
        }

        store.stage(id, Status.ANALYZING, "基线已实际执行，模型开始阅读项目。");
        var agent = new LocalCodingAgent(gateway, estimator, store, json, id, model, active);
        String plan = agent.analyze(run, task, new LocalAgentTools(json, files, () -> { throw new IllegalStateException(); }));
        store.event(id, "PLAN", "只读分析和试改计划", Map.of("text", plan));

        store.stage(id, Status.EDITING, "在独立副本试改，任何改动保留实际差异。");
        var candidates = new Candidates(actor, run, files, directory, base, active);
        String limitation = "";
        try {
            String answer = agent.edit(run, task, new LocalAgentTools(json, files, candidates::test), plan, base);
            store.event(id, "SUMMARY", "模型提交的执行说明", Map.of("text", answer));
        } catch (LocalCodingAgent.Halt e) {
            LocalCodingAgent.requireActive(active);
            limitation = e.getMessage();
            store.event(id, "LIMIT", limitation, Map.of());
        }
        LocalCodingAgent.requireActive(active);
        byte[] patch = workspaces.diff(root, active);
        if (patch.length == 0) {
            requireFinished(id, Status.FAILED, "本次运行没有形成可用的实际改动。" + limitation, null, null);
            return;
        }
        String sha = LocalWorkspaces.sha(patch);
        Files.write(directory.resolve("artifacts/changes.patch"), patch);
        if (!candidates.has(sha) && candidates.attempts < MAX_CANDIDATE_BUILDS) candidates.testPatch(patch, sha);
        if (!candidates.has(sha)) {
            requireFinished(id, Status.FAILED, "当前改动没有对应的已执行候选构建。" + limitation, null, sha);
            return;
        }
        var regression = verifyRegression(actor, run, files, directory, base, candidates.last, active);
        json.writeValue(directory.resolve("artifacts/regression-comparison.json").toFile(), regression);
        store.event(id, "REGRESSION", "原回归与新增用例对照", Map.of("comparison", regression));
        saveResult(actor, run, base, candidates.last, patch, sha, agent.observedModel(), limitation, regression, active);
    }

    private LocalRegressionComparison verifyRegression(ActorContext actor, Run run, LocalRepositoryFiles candidateFiles,
            Path directory, LocalBuildEvidence.Observed baseline, LocalBuildEvidence.Observed candidate,
            BooleanSupplier active) throws IOException {
        var names = workspaces.newTestFiles(candidateFiles.root(), active);
        LocalBuildEvidence.Observed replay = null;
        String issue = "";
        if (names.stream().anyMatch(name -> name.startsWith("src/test/java/") && name.endsWith(".java"))) {
            try {
                store.stage(run.id(), Status.TESTING, "在原实现上验证候选新增的测试。");
                var probe = workspaces.withNewTests(candidateFiles.root(), run.input().sourceRevision(),
                        directory.resolve("regression-source"), names, active);
                var testPatch = workspaces.diff(probe.root(), active);
                var build = maven.run(run.id(), "baseline-with-new-tests", LocalWorkspaces.sha(testPatch), probe,
                        directory, run.input().effectivePlan(), active);
                Files.write(build.artifacts().resolve("tests-only.patch"), testPatch);
                replay = evidence.inspect(actor, run.reportId(), build, Role.BASELINE, run.input().sourceRevision());
                store.event(run.id(), "BUILD", "原实现运行新增用例", replay.observation());
                if (replay.build().execution().end() != LocalProcess.End.EXITED || replay.parsed() == null) {
                    issue = "原实现上的新增用例未取得完整测试观察。" + replay.issue();
                }
            } catch (IOException | IllegalArgumentException e) {
                issue = "新增用例的原实现对照未完成。" + visibleError(e);
            }
        } else {
            issue = "候选未新增 Java 测试源文件。";
        }
        LocalCodingAgent.requireActive(active);
        return LocalRegressionComparison.compare(baseline, replay, candidate, names, issue);
    }

    private void saveResult(ActorContext actor, Run run, LocalBuildEvidence.Observed baseline,
                            LocalBuildEvidence.Observed candidate, byte[] patch, String sha, String model,
                            String limitation, LocalRegressionComparison regression, BooleanSupplier active) {
        LocalCodingAgent.requireActive(active);
        boolean preserves = LocalBuildEvidence.preservesBaseline(baseline, candidate);
        boolean passed = candidate.passed() && preserves;
        String note = (passed ? "候选 Maven test 通过，检测到的基线测试仍实际执行。" :
                "候选未满足通过条件。" + (candidate.issue().isEmpty() ? "" : candidate.issue()) +
                        (!preserves && candidate.parsed() != null ? "有基线测试在候选中缺失或未实际执行。" : "")) + limitation
                + " " + regression.summary();
        tx.executeWithoutResult(transaction -> {
            UUID verification = evidence.record(actor, run, baseline, candidate, patch, passed,
                    regression.failedThenPassed() > 0, note, model);
            requireFinished(run.id(), passed ? Status.SUCCEEDED : Status.FAILED, note, verification, sha);
        });
    }

    private void requireFinished(UUID run, Status status, String note, UUID verification, String sha) {
        if (!store.finish(run, status, note, verification, sha, true)) throw new LocalCodingAgent.Halt("执行已经取消或收口。");
    }

    private static void verifyFrozenSources(Snapshot baseline, Path root) throws IOException {
        for (var file : baseline.content().evidence().files()) {
            if (!file.path().startsWith("sources/") || file.path().equals(MavenPomFacts.PATH)) continue;
            var actual = root.resolve(file.path().substring("sources/".length())).normalize();
            if (!actual.startsWith(root) || !Files.isRegularFile(actual, LinkOption.NOFOLLOW_LINKS) ||
                    !actual.toRealPath().equals(actual) || Files.size(actual) != file.size() ||
                    !LocalWorkspaces.sha(Files.readAllBytes(actual)).equals(file.sha256())) {
                throw new LocalCodingAgent.Halt("冻结基线中的源码与选定提交不一致。" + file.path());
            }
        }
    }

    private void stop(UUID id, BooleanSupplier active, String reason, Exception failure) {
        boolean cancelled = !active.getAsBoolean();
        try {
            store.event(id, cancelled ? "CANCELLED" : "STOPPED", cancelled ? "已停止本地执行。" : reason,
                    Map.of("errorType", failure.getClass().getSimpleName()));
            if (!store.finish(id, cancelled ? Status.CANCELLED : Status.BLOCKED, cancelled ? "已取消。" : reason,
                    null, null, !cancelled)) {
                store.finish(id, Status.CANCELLED, "已取消。", null, null, false);
            }
        } catch (RuntimeException saveFailure) {
            LOG.warn("Unable to close local execution {}", id, saveFailure);
        }
        if (!cancelled && !(failure instanceof LocalCodingAgent.Halt)) {
            LOG.warn("Local execution {} stopped ({})", id, failure.getClass().getSimpleName());
        }
    }

    private static String visibleError(Exception error) {
        String note = error.getMessage();
        return note == null || note.isBlank() ? "本地执行受阻。" : note.substring(0, Math.min(900, note.length()));
    }

    private final class Candidates {
        private final ActorContext actor;
        private final Run run;
        private final LocalRepositoryFiles files;
        private final Path directory;
        private final LocalBuildEvidence.Observed baseline;
        private final BooleanSupplier active;
        private int attempts;
        private LocalBuildEvidence.Observed last;

        private Candidates(ActorContext actor, Run run, LocalRepositoryFiles files, Path directory,
                           LocalBuildEvidence.Observed baseline, BooleanSupplier active) {
            this.actor = actor;
            this.run = run;
            this.files = files;
            this.directory = directory;
            this.baseline = baseline;
            this.active = active;
        }

        private boolean has(String sha) { return last != null && sha.equals(last.build().patchSha256()); }

        private Map<String, Object> test() throws IOException {
            byte[] patch = workspaces.diff(files.root(), active);
            if (patch.length == 0) throw new IllegalArgumentException("尚无实际文件变更，先完成试改。");
            String sha = LocalWorkspaces.sha(patch);
            if (!has(sha)) testPatch(patch, sha);
            var facts = last.observation();
            facts.put("preservesBaselineTests", LocalBuildEvidence.preservesBaseline(baseline, last));
            return facts;
        }

        private void testPatch(byte[] patch, String sha) throws IOException {
            LocalCodingAgent.requireActive(active);
            if (attempts == MAX_CANDIDATE_BUILDS) throw new IllegalArgumentException("候选构建次数已用完。");
            String label = "candidate-" + ++attempts;
            store.stage(run.id(), Status.TESTING, "在隔离环境实际运行候选 Maven test，尝试 " + attempts + "。");
            var build = maven.run(run.id(), label, sha, files, directory, run.input().effectivePlan(), active);
            Files.write(build.artifacts().resolve("change.patch"), patch);
            last = evidence.inspect(actor, run.reportId(), build, Role.CANDIDATE, run.input().sourceRevision());
            store.event(run.id(), "BUILD", "候选 Maven 结果 · 尝试 " + attempts, last.observation());
            LocalCodingAgent.requireActive(active);
            store.stage(run.id(), Status.EDITING, "已取得候选结果，整理变更及验证证据。");
        }
    }
}
