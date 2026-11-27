package com.jundaodsj.insightops.server.workflow;

import com.jundaodsj.insightops.identity.application.ActorContext;
import com.jundaodsj.insightops.knowledge.application.KnowledgeUploadStore;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;

@Service
public class ResearchMaterials {
    public record Attachment(UUID uploadId, String name) { }

    private final KnowledgeUploadStore uploads;

    public ResearchMaterials(KnowledgeUploadStore uploads) { this.uploads = uploads; }

    public List<KnowledgeUploadStore.UploadRecord> visible(ActorContext actor) {
        return uploads.listVisible(actor.workspaceId(), actor.userId(), false);
    }

    public List<Attachment> available(ActorContext actor) {
        return visible(actor).stream().filter(file -> "SUCCEEDED".equals(file.status()))
                .map(file -> new Attachment(file.uploadId(), file.originalName())).toList();
    }

    public List<Attachment> require(ActorContext actor, List<UUID> supplied) {
        if (supplied == null || supplied.isEmpty()) return List.of();
        List<UUID> ids = supplied.stream().distinct().toList();
        if (ids.size() > 10) throw new IllegalArgumentException("最多关联 10 份资料。");
        Map<UUID, KnowledgeUploadStore.UploadRecord> visible = visible(actor).stream()
                .collect(Collectors.toMap(KnowledgeUploadStore.UploadRecord::uploadId, Function.identity()));
        return ids.stream().map(id -> {
            var file = visible.get(id);
            if (file == null) throw new IllegalArgumentException("关联资料不存在或无权访问，请重新选择。");
            if (!"SUCCEEDED".equals(file.status())) throw new IllegalArgumentException("关联资料尚未处理完成。");
            return new Attachment(id, file.originalName());
        }).toList();
    }
}
