package com.jundaodsj.insightops.infrastructure.persistence;

import com.jundaodsj.insightops.conversation.application.ConversationManager;
import com.jundaodsj.insightops.identity.application.ActorContext;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
public class JdbcConversationManager implements ConversationManager {

    private final JdbcClient jdbcClient;

    public JdbcConversationManager(JdbcClient jdbcClient) {
        this.jdbcClient = jdbcClient;
    }

    @Override
    public List<ConversationSummary> list(ActorContext actor, boolean includeArchived) {
        String statusClause = includeArchived ? "" : " and session.status = 'ACTIVE'";
        return jdbcClient.sql("""
                select session.id, session.title, session.status,
                       count(message.id)::int as message_count,
                       session.created_at, session.updated_at
                from conversation_session session
                left join conversation_message message on message.session_id = session.id
                where session.owner_user_id = :userId
                  and session.workspace_id = :workspaceId
                """ + statusClause + """
                group by session.id
                order by session.updated_at desc
                limit 200
                """)
                .param("userId", actor.userId())
                .param("workspaceId", actor.workspaceId())
                .query((resultSet, rowNum) -> summary(resultSet))
                .list();
    }

    @Override
    public Optional<ConversationSummary> rename(
            ActorContext actor,
            UUID sessionId,
            String title,
            Instant now) {
        int updated = jdbcClient.sql("""
                update conversation_session
                set title = :title, updated_at = :now
                where id = :id and owner_user_id = :userId and workspace_id = :workspaceId
                """)
                .param("title", title)
                .param("now", timestamp(now))
                .param("id", sessionId)
                .param("userId", actor.userId())
                .param("workspaceId", actor.workspaceId())
                .update();
        return updated == 0 ? Optional.empty() : find(actor, sessionId);
    }

    @Override
    public Optional<ConversationSummary> archive(
            ActorContext actor,
            UUID sessionId,
            boolean archived,
            Instant now) {
        int updated = jdbcClient.sql("""
                update conversation_session
                set status = :status, updated_at = :now
                where id = :id and owner_user_id = :userId and workspace_id = :workspaceId
                """)
                .param("status", archived ? "ARCHIVED" : "ACTIVE")
                .param("now", timestamp(now))
                .param("id", sessionId)
                .param("userId", actor.userId())
                .param("workspaceId", actor.workspaceId())
                .update();
        return updated == 0 ? Optional.empty() : find(actor, sessionId);
    }

    @Override
    @Transactional
    public boolean delete(ActorContext actor, UUID sessionId) {
        Optional<ConversationSummary> existing = find(actor, sessionId);
        if (existing.isEmpty()) {
            return false;
        }
        jdbcClient.sql("update agent_run set session_id = null where session_id = :id")
                .param("id", sessionId)
                .update();
        return jdbcClient.sql("""
                delete from conversation_session
                where id = :id and owner_user_id = :userId and workspace_id = :workspaceId
                """)
                .param("id", sessionId)
                .param("userId", actor.userId())
                .param("workspaceId", actor.workspaceId())
                .update() == 1;
    }

    private Optional<ConversationSummary> find(ActorContext actor, UUID sessionId) {
        return jdbcClient.sql("""
                select session.id, session.title, session.status,
                       count(message.id)::int as message_count,
                       session.created_at, session.updated_at
                from conversation_session session
                left join conversation_message message on message.session_id = session.id
                where session.id = :id
                  and session.owner_user_id = :userId
                  and session.workspace_id = :workspaceId
                group by session.id
                """)
                .param("id", sessionId)
                .param("userId", actor.userId())
                .param("workspaceId", actor.workspaceId())
                .query((resultSet, rowNum) -> summary(resultSet))
                .optional();
    }

    private static ConversationSummary summary(java.sql.ResultSet resultSet)
            throws java.sql.SQLException {
        return new ConversationSummary(
                resultSet.getObject("id", UUID.class),
                resultSet.getString("title"),
                resultSet.getString("status"),
                resultSet.getInt("message_count"),
                resultSet.getObject("created_at", OffsetDateTime.class).toInstant(),
                resultSet.getObject("updated_at", OffsetDateTime.class).toInstant());
    }

    private static OffsetDateTime timestamp(Instant instant) {
        return OffsetDateTime.ofInstant(instant, ZoneOffset.UTC);
    }
}
