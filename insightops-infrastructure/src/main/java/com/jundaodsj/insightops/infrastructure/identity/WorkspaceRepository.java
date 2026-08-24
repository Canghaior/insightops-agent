package com.jundaodsj.insightops.infrastructure.identity;

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
public class WorkspaceRepository {
    private final JdbcClient jdbc;

    public WorkspaceRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    public List<WorkspaceRecord> listForUser(UUID userId) {
        return jdbc.sql("""
                select workspace.id,workspace.name,workspace.slug,workspace.description,
                       workspace.status,member.role,workspace.created_at,workspace.updated_at
                from workspace_member member
                join workspace on workspace.id = member.workspace_id
                where member.user_id = :userId
                order by case workspace.status when 'ACTIVE' then 0 else 1 end,
                         lower(workspace.name)
                """).param("userId", userId)
                .query((rs, row) -> workspace(rs)).list();
    }

    public Optional<WorkspaceRecord> findForUser(UUID userId, UUID workspaceId) {
        return jdbc.sql("""
                select workspace.id,workspace.name,workspace.slug,workspace.description,
                       workspace.status,member.role,workspace.created_at,workspace.updated_at
                from workspace_member member
                join workspace on workspace.id = member.workspace_id
                where member.user_id = :userId and workspace.id = :workspaceId
                """).param("userId", userId).param("workspaceId", workspaceId)
                .query((rs, row) -> workspace(rs)).optional();
    }

    @Transactional
    public WorkspaceRecord create(UUID workspaceId, UUID userId, String name, String slug,
                                  String description, Instant now) {
        jdbc.sql("""
                insert into workspace
                    (id,name,slug,description,status,created_by,created_at,updated_at)
                values (:id,:name,:slug,:description,'ACTIVE',:userId,:now,:now)
                """).param("id", workspaceId).param("name", name).param("slug", slug)
                .param("description", description).param("userId", userId)
                .param("now", timestamp(now)).update();
        jdbc.sql("""
                insert into workspace_member (workspace_id,user_id,role,created_at)
                values (:workspaceId,:userId,'OWNER',:now)
                """).param("workspaceId", workspaceId).param("userId", userId)
                .param("now", timestamp(now)).update();
        return findForUser(userId, workspaceId).orElseThrow();
    }

    public Optional<WorkspaceRecord> update(UUID userId, UUID workspaceId, String name,
                                            String description, Instant now) {
        int updated = jdbc.sql("""
                update workspace set name = :name, description = :description, updated_at = :now
                where id = :workspaceId and status = 'ACTIVE'
                  and exists (select 1 from workspace_member member
                    where member.workspace_id = workspace.id and member.user_id = :userId
                      and member.role = 'OWNER')
                """).param("name", name).param("description", description)
                .param("now", timestamp(now)).param("workspaceId", workspaceId)
                .param("userId", userId).update();
        return updated == 1 ? findForUser(userId, workspaceId) : Optional.empty();
    }

    public boolean archive(UUID userId, UUID workspaceId, Instant now) {
        return jdbc.sql("""
                update workspace set status = 'DISABLED', archived_at = :now, updated_at = :now
                where id = :workspaceId and status = 'ACTIVE'
                  and exists (select 1 from workspace_member member
                    where member.workspace_id = workspace.id and member.user_id = :userId
                      and member.role = 'OWNER')
                """).param("now", timestamp(now)).param("workspaceId", workspaceId)
                .param("userId", userId).update() == 1;
    }

    public boolean switchSession(String tokenHash, UUID userId, UUID workspaceId, Instant now) {
        return jdbc.sql("""
                update auth_session set active_workspace_id = :workspaceId, last_seen_at = :now
                where token_hash = :tokenHash and user_id = :userId and revoked_at is null
                  and expires_at > :now
                  and exists (select 1 from workspace_member member
                    join workspace on workspace.id = member.workspace_id
                    where member.user_id = :userId and member.workspace_id = :workspaceId
                      and workspace.status = 'ACTIVE')
                """).param("workspaceId", workspaceId).param("now", timestamp(now))
                .param("tokenHash", tokenHash).param("userId", userId).update() == 1;
    }

    public List<MemberRecord> listMembers(UUID workspaceId) {
        return jdbc.sql("""
                select user_account.id,user_account.username,user_account.display_name,
                       user_account.email,user_account.email_verified_at,user_account.status,
                       user_account.system_role,member.role,member.created_at
                from workspace_member member
                join app_user user_account on user_account.id = member.user_id
                where member.workspace_id = :workspaceId
                order by case member.role when 'OWNER' then 0 else 1 end,
                         lower(user_account.display_name)
                """).param("workspaceId", workspaceId)
                .query((rs, row) -> new MemberRecord(
                        rs.getObject("id", UUID.class), rs.getString("username"),
                        rs.getString("display_name"), rs.getString("email"),
                        instant(rs, "email_verified_at") != null, rs.getString("status"),
                        rs.getString("system_role"), rs.getString("role"),
                        instant(rs, "created_at"))).list();
    }

    public Optional<MemberRecord> findMember(UUID workspaceId, UUID userId) {
        return listMembers(workspaceId).stream().filter(member -> member.userId().equals(userId)).findFirst();
    }

    public long ownerCount(UUID workspaceId) {
        return jdbc.sql("""
                select count(*) from workspace_member
                where workspace_id = :workspaceId and role = 'OWNER'
                """).param("workspaceId", workspaceId).query(Long.class).single();
    }

    public int soleOwnedWorkspaceCount(UUID userId) {
        return jdbc.sql("""
                select count(*) from workspace_member mine
                join workspace on workspace.id = mine.workspace_id
                where mine.user_id = :userId and mine.role = 'OWNER'
                  and workspace.status = 'ACTIVE'
                  and not exists (select 1 from workspace_member other
                    where other.workspace_id = mine.workspace_id
                      and other.role = 'OWNER' and other.user_id <> :userId)
                """).param("userId", userId).query(Integer.class).single();
    }

    public boolean updateMemberRole(UUID workspaceId, UUID userId, String role, Instant now) {
        int updated = jdbc.sql("""
                update workspace_member set role = :role
                where workspace_id = :workspaceId and user_id = :userId
                """).param("role", role).param("workspaceId", workspaceId)
                .param("userId", userId).update();
        if (updated == 1) {
            jdbc.sql("update app_user set updated_at = :now where id = :userId")
                    .param("now", timestamp(now)).param("userId", userId).update();
        }
        return updated == 1;
    }

    @Transactional
    public boolean transferOwnership(UUID workspaceId, UUID actorId, UUID targetId, Instant now) {
        int promoted = jdbc.sql("""
                update workspace_member set role = 'OWNER'
                where workspace_id = :workspaceId and user_id = :targetId
                """).param("workspaceId", workspaceId).param("targetId", targetId).update();
        if (promoted != 1) return false;
        jdbc.sql("""
                update workspace_member set role = 'MEMBER'
                where workspace_id = :workspaceId and user_id = :actorId
                """).param("workspaceId", workspaceId).param("actorId", actorId).update();
        jdbc.sql("update workspace set updated_at = :now where id = :workspaceId")
                .param("now", timestamp(now)).param("workspaceId", workspaceId).update();
        return true;
    }

    public boolean removeMember(UUID workspaceId, UUID userId) {
        return jdbc.sql("""
                delete from workspace_member where workspace_id = :workspaceId and user_id = :userId
                """).param("workspaceId", workspaceId).param("userId", userId).update() == 1;
    }

    @Transactional
    public InvitationRecord createInvitation(UUID id, UUID workspaceId, String email,
                                              String normalizedEmail, String role, String tokenHash,
                                              UUID invitedBy, Instant expiresAt, Instant now) {
        jdbc.sql("""
                update workspace_invitation set status = 'REVOKED', revoked_at = :now
                where workspace_id = :workspaceId and email_normalized = :email
                  and status = 'PENDING'
                """).param("now", timestamp(now)).param("workspaceId", workspaceId)
                .param("email", normalizedEmail).update();
        jdbc.sql("""
                insert into workspace_invitation
                    (id,workspace_id,email,email_normalized,role,token_hash,status,
                     invited_by,expires_at,created_at)
                values (:id,:workspaceId,:email,:normalized,:role,:hash,'PENDING',
                        :invitedBy,:expiresAt,:now)
                """).param("id", id).param("workspaceId", workspaceId).param("email", email)
                .param("normalized", normalizedEmail).param("role", role).param("hash", tokenHash)
                .param("invitedBy", invitedBy).param("expiresAt", timestamp(expiresAt))
                .param("now", timestamp(now)).update();
        return findInvitation(id).orElseThrow();
    }

    public List<InvitationRecord> listInvitations(UUID workspaceId, Instant now) {
        expireInvitations(now);
        return jdbc.sql(invitationSelect() + " where invitation.workspace_id = :workspaceId order by invitation.created_at desc")
                .param("workspaceId", workspaceId)
                .query((rs, row) -> invitation(rs)).list();
    }

    public Optional<InvitationRecord> findInvitation(UUID id) {
        return jdbc.sql(invitationSelect() + " where invitation.id = :id")
                .param("id", id).query((rs, row) -> invitation(rs)).optional();
    }

    public Optional<InvitationRecord> findInvitationByToken(String tokenHash, Instant now) {
        expireInvitations(now);
        return jdbc.sql(invitationSelect() + " where invitation.token_hash = :hash")
                .param("hash", tokenHash).query((rs, row) -> invitation(rs)).optional();
    }

    public boolean revokeInvitation(UUID workspaceId, UUID invitationId, Instant now) {
        return jdbc.sql("""
                update workspace_invitation set status = 'REVOKED', revoked_at = :now
                where id = :id and workspace_id = :workspaceId and status = 'PENDING'
                """).param("now", timestamp(now)).param("id", invitationId)
                .param("workspaceId", workspaceId).update() == 1;
    }

    @Transactional
    public boolean acceptExistingInvitation(UUID invitationId, UUID userId, Instant now) {
        Optional<InvitationRecord> invitation = findInvitation(invitationId)
                .filter(value -> "PENDING".equals(value.status()) && value.expiresAt().isAfter(now));
        if (invitation.isEmpty()) return false;
        jdbc.sql("""
                insert into workspace_member (workspace_id,user_id,role,created_at)
                values (:workspaceId,:userId,:role,:now)
                on conflict (workspace_id,user_id) do update set role = excluded.role
                """).param("workspaceId", invitation.get().workspaceId()).param("userId", userId)
                .param("role", invitation.get().role()).param("now", timestamp(now)).update();
        return markAccepted(invitationId, userId, now);
    }

    @Transactional
    public UUID acceptNewInvitation(UUID invitationId, UUID userId, String username,
                                    String displayName, String email, String normalizedEmail,
                                    String passwordHash, Instant now) {
        InvitationRecord invitation = findInvitation(invitationId)
                .filter(value -> "PENDING".equals(value.status()) && value.expiresAt().isAfter(now))
                .orElseThrow();
        jdbc.sql("""
                insert into app_user
                    (id,username,display_name,email,email_normalized,email_verified_at,
                     status,system_role,created_at,updated_at)
                values (:id,:username,:displayName,:email,:normalized,:now,
                        'ACTIVE','USER',:now,:now)
                """).param("id", userId).param("username", username)
                .param("displayName", displayName).param("email", email)
                .param("normalized", normalizedEmail).param("now", timestamp(now)).update();
        jdbc.sql("""
                insert into user_credential
                    (user_id,password_hash,must_change_password,password_changed_at,updated_at)
                values (:userId,:passwordHash,false,:now,:now)
                """).param("userId", userId).param("passwordHash", passwordHash)
                .param("now", timestamp(now)).update();
        jdbc.sql("""
                insert into workspace_member (workspace_id,user_id,role,created_at)
                values (:workspaceId,:userId,:role,:now)
                """).param("workspaceId", invitation.workspaceId()).param("userId", userId)
                .param("role", invitation.role()).param("now", timestamp(now)).update();
        if (!markAccepted(invitationId, userId, now)) throw new IllegalStateException("Invitation was already consumed");
        return userId;
    }

    private boolean markAccepted(UUID invitationId, UUID userId, Instant now) {
        return jdbc.sql("""
                update workspace_invitation set status = 'ACCEPTED', accepted_by = :userId,
                    accepted_at = :now
                where id = :id and status = 'PENDING' and expires_at > :now
                """).param("userId", userId).param("now", timestamp(now))
                .param("id", invitationId).update() == 1;
    }

    private void expireInvitations(Instant now) {
        jdbc.sql("""
                update workspace_invitation set status = 'EXPIRED'
                where status = 'PENDING' and expires_at <= :now
                """).param("now", timestamp(now)).update();
    }

    private static String invitationSelect() {
        return """
                select invitation.id,invitation.workspace_id,workspace.name as workspace_name,
                       invitation.email,invitation.email_normalized,invitation.role,
                       invitation.status,invitation.invited_by,inviter.display_name as inviter_name,
                       invitation.expires_at,invitation.accepted_by,invitation.accepted_at,
                       invitation.created_at,
                       exists(select 1 from app_user existing
                         where existing.email_normalized = invitation.email_normalized
                           and existing.status = 'ACTIVE') as existing_user
                from workspace_invitation invitation
                join workspace on workspace.id = invitation.workspace_id
                join app_user inviter on inviter.id = invitation.invited_by
                """;
    }

    private static WorkspaceRecord workspace(java.sql.ResultSet rs) throws java.sql.SQLException {
        return new WorkspaceRecord(rs.getObject("id", UUID.class), rs.getString("name"),
                rs.getString("slug"), rs.getString("description"), rs.getString("status"),
                rs.getString("role"), instant(rs, "created_at"), instant(rs, "updated_at"));
    }

    private static InvitationRecord invitation(java.sql.ResultSet rs) throws java.sql.SQLException {
        return new InvitationRecord(rs.getObject("id", UUID.class),
                rs.getObject("workspace_id", UUID.class), rs.getString("workspace_name"),
                rs.getString("email"), rs.getString("email_normalized"), rs.getString("role"),
                rs.getString("status"), rs.getObject("invited_by", UUID.class),
                rs.getString("inviter_name"), instant(rs, "expires_at"),
                rs.getObject("accepted_by", UUID.class), instant(rs, "accepted_at"),
                instant(rs, "created_at"), rs.getBoolean("existing_user"));
    }

    private static Instant instant(java.sql.ResultSet rs, String column) throws java.sql.SQLException {
        OffsetDateTime value = rs.getObject(column, OffsetDateTime.class);
        return value == null ? null : value.toInstant();
    }

    private static OffsetDateTime timestamp(Instant value) {
        return OffsetDateTime.ofInstant(value, ZoneOffset.UTC);
    }

    public record WorkspaceRecord(UUID id, String name, String slug, String description,
                                  String status, String role, Instant createdAt,
                                  Instant updatedAt) { }

    public record MemberRecord(UUID userId, String username, String displayName, String email,
                               boolean emailVerified, String status, String systemRole,
                               String role, Instant joinedAt) { }

    public record InvitationRecord(UUID id, UUID workspaceId, String workspaceName,
                                   String email, String normalizedEmail, String role,
                                   String status, UUID invitedBy, String inviterName,
                                   Instant expiresAt, UUID acceptedBy, Instant acceptedAt,
                                   Instant createdAt, boolean existingUser) { }
}
