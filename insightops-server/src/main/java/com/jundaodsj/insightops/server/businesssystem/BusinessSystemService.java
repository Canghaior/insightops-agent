package com.jundaodsj.insightops.server.businesssystem;

import com.jundaodsj.insightops.businesssystem.application.BusinessSystemStore;
import com.jundaodsj.insightops.businesssystem.application.BusinessSystemStore.Background;
import com.jundaodsj.insightops.identity.application.ActorContext;
import com.jundaodsj.insightops.server.workflow.ResearchMaterials;
import com.jundaodsj.insightops.server.workflow.ResearchMaterials.Attachment;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;

import static com.jundaodsj.insightops.server.workflow.ResearchProfiles.text;

@Service
public class BusinessSystemService {
    public record Draft(Background background, List<UUID> uploadIds, Integer expectedRevision) { }
    public record Detail(UUID id, int revision, Background background, List<Attachment> attachments) { }
    public record History(List<BusinessSystemStore.Run> items, boolean hasMore) { }

    private final BusinessSystemStore store;
    private final ResearchMaterials materials;

    public BusinessSystemService(BusinessSystemStore store, ResearchMaterials materials) {
        this.store = store;
        this.materials = materials;
    }

    public List<BusinessSystemStore.Summary> list(ActorContext actor) { return store.list(actor); }

    public List<Attachment> availableUploads(ActorContext actor) { return materials.available(actor); }

    private BusinessSystemStore.Profile profile(ActorContext actor, UUID id) {
        return store.find(actor, id).orElseThrow(() ->
                new ResponseStatusException(HttpStatus.NOT_FOUND, "未找到该业务系统。"));
    }

    public Detail detail(ActorContext actor, UUID id) {
        var profile = profile(actor, id);
        Map<UUID, String> names = profile.uploadIds().isEmpty() ? Map.of() : availableUploads(actor).stream()
                .collect(Collectors.toMap(Attachment::uploadId, Attachment::name));
        return new Detail(id, profile.revision(), profile.background(), profile.uploadIds().stream()
                .map(file -> new Attachment(file, names.get(file))).toList());
    }

    public Detail forAssessment(ActorContext actor, UUID id, Integer expectedRevision) {
        var profile = profile(actor, id);
        requireRevision(expectedRevision, profile.revision());
        return new Detail(id, profile.revision(), profile.background(), materials.require(actor, profile.uploadIds()));
    }

    @Transactional
    public Detail create(ActorContext actor, Draft draft) {
        Background background = validate(draft);
        var files = materials.require(actor, draft.uploadIds());
        var id = store.create(actor, background, files.stream().map(Attachment::uploadId).toList());
        return new Detail(id, 1, background, files);
    }

    @Transactional
    public Detail update(ActorContext actor, UUID id, Draft draft) {
        var current = profile(actor, id);
        Background background = validate(draft);
        requireRevision(draft.expectedRevision(), current.revision());
        var files = materials.require(actor, draft.uploadIds());
        if (!store.update(actor, id, current.revision(), background, files.stream().map(Attachment::uploadId).toList())) {
            throw conflict();
        }
        return new Detail(id, current.revision() + 1, background, files);
    }

    public History history(ActorContext actor, UUID id, int page) {
        profile(actor, id);
        if (page < 0) throw new IllegalArgumentException("页码无效。");
        int size = 20;
        var rows = store.runs(actor, id, size + 1, (long) page * size);
        return new History(rows.stream().limit(size).toList(), rows.size() > size);
    }

    private static Background validate(Draft draft) {
        if (draft == null || draft.background() == null) throw new IllegalArgumentException("请填写系统档案。");
        var b = draft.background();
        return new Background(text(b.name(), 120, true), text(b.purpose(), 1000, false),
                text(b.keyProcesses(), 1000, false), text(b.technologyStack(), 1000, false),
                text(b.currentProblems(), 1000, false), text(b.constraints(), 1000, false));
    }

    private static void requireRevision(Integer expected, int actual) {
        if (expected == null || expected < 1) throw new IllegalArgumentException("缺少有效的档案修订号。");
        if (expected != actual) throw conflict();
    }

    private static ResponseStatusException conflict() {
        return new ResponseStatusException(HttpStatus.CONFLICT, "档案已更新，请重新载入后核对。");
    }
}
