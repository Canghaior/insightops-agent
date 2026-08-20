package com.jundaodsj.insightops.infrastructure.persistence;

import com.jundaodsj.insightops.conversation.application.ResearchFeedbackStore;
import com.jundaodsj.insightops.identity.application.ActorContext;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.UUID;

@Repository
public class JdbcResearchFeedbackStore implements ResearchFeedbackStore {

    private final JdbcClient jdbc;
    public JdbcResearchFeedbackStore(JdbcClient jdbc) { this.jdbc = jdbc; }

    @Override
    public boolean saveAnswerFeedback(
            ActorContext actor, UUID runId, Boolean helpful, String reason, String comment, Instant now) {
        if (!owns(actor, runId)) return false;
        jdbc.sql("""
                insert into research_answer_feedback
                    (id,user_id,workspace_id,run_id,helpful,reason,comment,created_at,updated_at)
                values (:id,:userId,:workspaceId,:runId,:helpful,:reason,:comment,:now,:now)
                on conflict (user_id,run_id) do update set helpful=excluded.helpful,
                    reason=excluded.reason,comment=excluded.comment,review_status='PENDING',updated_at=excluded.updated_at
                """).param("id", UUID.randomUUID()).param("userId", actor.userId())
                .param("workspaceId", actor.workspaceId()).param("runId", runId).param("helpful", helpful)
                .param("reason", clean(reason,48)).param("comment", clean(comment,1000))
                .param("now", timestamp(now)).update();
        return true;
    }

    @Override
    public boolean saveCitationFeedback(
            ActorContext actor, UUID runId, String citationUrl, boolean correct, String comment, Instant now) {
        if (!owns(actor, runId)) return false;
        String url=clean(citationUrl,1024);
        if (!url.startsWith("https://")) throw new IllegalArgumentException("Citation URL must use HTTPS");
        jdbc.sql("""
                insert into research_citation_feedback
                    (id,user_id,workspace_id,run_id,citation_url,correct,comment,created_at,updated_at)
                values (:id,:userId,:workspaceId,:runId,:url,:correct,:comment,:now,:now)
                on conflict (user_id,run_id,citation_url) do update set correct=excluded.correct,
                    comment=excluded.comment,review_status='PENDING',updated_at=excluded.updated_at
                """).param("id",UUID.randomUUID()).param("userId",actor.userId())
                .param("workspaceId",actor.workspaceId()).param("runId",runId).param("url",url)
                .param("correct",correct).param("comment",clean(comment,1000)).param("now",timestamp(now)).update();
        return true;
    }

    private boolean owns(ActorContext actor, UUID runId) {
        return jdbc.sql("select count(*) from agent_run where id=:runId and owner_user_id=:userId and workspace_id=:workspaceId")
                .param("runId",runId).param("userId",actor.userId()).param("workspaceId",actor.workspaceId())
                .query(Long.class).single()==1;
    }
    private static String clean(String value,int max){String safe=value==null?null:value.replace("\u0000","").trim();return safe==null||safe.length()<=max?safe:safe.substring(0,max);}
    private static OffsetDateTime timestamp(Instant value){return OffsetDateTime.ofInstant(value, ZoneOffset.UTC);}
}
