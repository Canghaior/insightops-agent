package com.jundaodsj.insightops.server.businesssystem;

import static com.jundaodsj.insightops.businesssystem.application.EngineeringBaselineStore.*;
import static com.jundaodsj.insightops.server.workflow.ResearchProfiles.text;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.jundaodsj.insightops.businesssystem.application.EngineeringBaselineStore;
import com.jundaodsj.insightops.identity.application.ActorContext;

import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.util.*;

@Service
public class EngineeringBaselineService {
    public record Draft(
            String name, byte[] bytes, String label, String note, String expectedJava) {}

    public record Preview(Content content, String token) {}

    public record Download(String name, byte[] bytes) {}

    private final EngineeringBaselineStore store;
    private final MavenBaselineArchive parser;
    private final ObjectMapper json;

    public EngineeringBaselineService(EngineeringBaselineStore store, ObjectMapper json) {
        this.store = store;
        this.json = json;
        parser = new MavenBaselineArchive(json);
    }

    private static ResponseStatusException missing() {
        return new ResponseStatusException(HttpStatus.NOT_FOUND, "未找到该业务系统或工程基线。");
    }

    private Profile profile(ActorContext actor, UUID system, boolean lock) {
        return store.profile(actor, system, lock).orElseThrow(EngineeringBaselineService::missing);
    }

    public List<Summary> list(ActorContext actor, UUID system) {
        profile(actor, system, false);
        return store.list(actor, system);
    }

    public Snapshot detail(ActorContext actor, UUID system, UUID id) {
        return store.find(actor, system, id).orElseThrow(EngineeringBaselineService::missing);
    }

    public Preview preview(ActorContext actor, UUID system, Draft d) {
        return prepare(profile(actor, system, false), d);
    }

    private String digest(Object value) {
        try {
            return MavenBaselineArchive.hash(json.writeValueAsBytes(value));
        } catch (com.fasterxml.jackson.core.JsonProcessingException e) {
            throw new IllegalStateException("无法核对工程资料。", e);
        }
    }

    private static String filename(String name) {
        if (name == null
                || name.length() > 180
                || name.isBlank()
                || name.contains("/")
                || name.contains("\\")
                || name.chars().anyMatch(Character::isISOControl))
            throw new IllegalArgumentException("工程原件名无效。");
        return name;
    }

    private Preview prepare(Profile profile, Draft d) {
        String name = filename(d.name());
        var analysis = parser.parse(name, d.bytes());
        String label = text(d.label(), 160, false);
        if (label.isEmpty())
            label =
                    analysis.sourceLabel()
                            .substring(0, Math.min(160, analysis.sourceLabel().length()));
        String note = text(d.note(), 600, false), expected = text(d.expectedJava(), 32, false);
        var conflicts = new ArrayList<String>();
        var declared =
                analysis.facts().stream()
                        .filter(f -> f.subject().equals("java.version") && !f.value().contains("$"))
                        .findFirst();
        if (!expected.isEmpty() && declared.isPresent() && !expected.equals(declared.get().value()))
            conflicts.add(
                    "导入者填写的 POM Java 版本 "
                            + expected
                            + " 与 "
                            + declared.get().source().path()
                            + ":"
                            + declared.get().source().line()
                            + " 的 "
                            + declared.get().value()
                            + " 不同，请核对。");
        var stackJava =
                java.util.regex.Pattern.compile(
                                "(?i)\\b(?:java|jdk)\\s*[-:=：]?\\s*(\\d+)(?:\\b|(?=[\\p{IsHan}]))")
                        .matcher(profile.technologyStack());
        if (declared.isPresent()
                && stackJava.find()
                && !stackJava.group(1).equals(declared.get().value().split("\\.")[0]))
            conflicts.add(
                    "档案修订 "
                            + profile.revision()
                            + " 中的原文「"
                            + stackJava.group()
                            + "」与工程 java.version「"
                            + declared.get().value()
                            + "」需核对用途。档案自述未必指构建目标。");
        var boot =
                java.util.regex.Pattern.compile(
                                "(?i)\\bspring["
                                    + " -]+boot\\s*[-:=：]?\\s*(\\d+\\.\\d+(?:\\.\\d+)?)(?=\\s|[/,，；;）)]|$)")
                        .matcher(profile.technologyStack());
        if (boot.find()) {
            String written = boot.group(1);
            analysis.facts().stream()
                    .filter(
                            f ->
                                    f.subject()
                                            .equals(
                                                    "父 POM"
                                                        + " org.springframework.boot:spring-boot-starter-parent"))
                    .filter(f -> f.value().matches("\\d+\\.\\d+(?:\\.\\d+)?"))
                    .filter(
                            f ->
                                    !f.value().equals(written)
                                            && !f.value().startsWith(written + ".")
                                            && !written.startsWith(f.value() + "."))
                    .findFirst()
                    .ifPresent(
                            f ->
                                    conflicts.add(
                                            "档案自述「"
                                                    + boot.group()
                                                    + "」与 "
                                                    + f.source().path()
                                                    + ":"
                                                    + f.source().line()
                                                    + " 声明的 Spring Boot 父版本「"
                                                    + f.value()
                                                    + "」需核对。父版本不能单独证明正在运行的框架版本。"));
        }
        var content =
                new Content(
                        label,
                        note,
                        expected,
                        profile,
                        analysis,
                        List.copyOf(conflicts),
                        name,
                        MavenBaselineArchive.hash(d.bytes()));
        return new Preview(content, digest(content));
    }

    @Transactional
    public Snapshot save(ActorContext actor, UUID system, UUID request, String token, Draft d) {
        if (request == null || token == null || !token.matches("[0-9a-f]{64}"))
            throw new IllegalArgumentException("保存前请先预览工程资料。");
        var profile = profile(actor, system, true);
        String fingerprint =
                digest(
                        List.of(
                                token,
                                filename(d.name()),
                                MavenBaselineArchive.hash(d.bytes()),
                                Objects.toString(d.label(), ""),
                                Objects.toString(d.note(), ""),
                                Objects.toString(d.expectedJava(), "")));
        var existing = store.request(actor, system, request);
        if (existing.isPresent()) {
            if (!existing.get().requestHash().equals(fingerprint))
                throw new ResponseStatusException(HttpStatus.CONFLICT, "此请求已用于其他基线内容。");
            return detail(actor, system, existing.get().id());
        }
        var preview = prepare(profile, d);
        if (!preview.token().equals(token))
            throw new ResponseStatusException(HttpStatus.CONFLICT, "档案或资料已变化，请重新预览并核对。");
        return store.append(actor, system, request, fingerprint, preview.content(), d.bytes());
    }

    public Download download(ActorContext actor, UUID system, UUID id, String path) {
        Content c = detail(actor, system, id).content();
        var bytes =
                store.original(actor, system, id).orElseThrow(EngineeringBaselineService::missing);
        if (!MavenBaselineArchive.hash(bytes).equals(c.originalSha256()))
            throw new IllegalStateException("保存的工程原件校验失败。");
        if (path == null) return new Download(c.originalName(), bytes);
        var file =
                c.evidence().files().stream()
                        .filter(f -> f.path().equals(path))
                        .findFirst()
                        .orElseThrow(EngineeringBaselineService::missing);
        var original = MavenBaselineArchive.entries(c.originalName(), bytes).get(path);
        if (original == null || !MavenBaselineArchive.hash(original).equals(file.sha256()))
            throw new IllegalStateException("工程原件校验失败。");
        return new Download(path.substring(path.lastIndexOf('/') + 1), original);
    }

    public void freeze(Map<String, Object> inputs, ActorContext actor, UUID system, UUID id) {
        if (id == null) return;
        if (system == null) throw new IllegalArgumentException("请先选择该工程基线所属的业务系统。");
        var snapshot = detail(actor, system, id);
        var meta = snapshot.baseline();
        inputs.put(
                "engineeringBaseline",
                Map.of(
                        "baseline",
                        Map.of(
                                "id",
                                meta.id().toString(),
                                "systemId",
                                meta.systemId().toString(),
                                "version",
                                meta.version(),
                                "label",
                                meta.label(),
                                "savedAt",
                                meta.savedAt().toString()),
                        "content",
                        json.convertValue(
                                snapshot.content(), new TypeReference<Map<String, Object>>() {})));
    }
}
