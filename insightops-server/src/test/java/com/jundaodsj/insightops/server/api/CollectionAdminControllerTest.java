package com.jundaodsj.insightops.server.api;

import com.jundaodsj.insightops.identity.application.AccountWorkspaceStore;
import com.jundaodsj.insightops.identity.application.ActorContext;
import com.jundaodsj.insightops.project.application.ProjectUpdateStore;
import com.jundaodsj.insightops.server.auth.CurrentAccount;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.web.server.ResponseStatusException;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class CollectionAdminControllerTest {
    private static final UUID USER_ID = UUID.fromString("00000000-0000-0000-0000-000000000101");
    private static final UUID WORKSPACE_ID = UUID.fromString("00000000-0000-0000-0000-000000000001");
    private static final UUID PROJECT_ID = UUID.fromString("00000000-0000-0000-0000-000000000201");

    @Test
    void shouldAllowSystemAdministratorToInspectAndRequestCollection() {
        RecordingStore store = new RecordingStore();
        CollectionAdminController controller = new CollectionAdminController(store);

        var response = controller.status(request("SYSTEM_ADMIN"));
        controller.sync(PROJECT_ID, request("SYSTEM_ADMIN"));

        assertThat(response.data()).hasSize(1);
        assertThat(store.workspaceId).isEqualTo(WORKSPACE_ID);
        assertThat(store.projectId).isEqualTo(PROJECT_ID);
    }

    @Test
    void shouldRejectOrdinaryUserFromCollectionAdministration() {
        CollectionAdminController controller = new CollectionAdminController(new RecordingStore());

        assertThatThrownBy(() -> controller.status(request("USER")))
                .isInstanceOf(ResponseStatusException.class)
                .hasMessageContaining("403 FORBIDDEN");
    }

    private static MockHttpServletRequest request(String systemRole) {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.setAttribute(TraceIdFilter.TRACE_ID_ATTRIBUTE, "trace-collection");
        request.setAttribute(CurrentAccount.ATTRIBUTE, new AccountWorkspaceStore.AccountRecord(
                USER_ID, "alpha-owner", "Alpha Owner", WORKSPACE_ID, "Alpha Workspace",
                systemRole, "OWNER", "hash", false));
        return request;
    }

    private static final class RecordingStore implements ProjectUpdateStore {
        private UUID workspaceId;
        private UUID projectId;

        @Override
        public List<CollectionStatus> collectionStatus(UUID workspaceId) {
            this.workspaceId = workspaceId;
            return List.of(new CollectionStatus(PROJECT_ID, "Spring AI", "spring-projects",
                    "SUCCEEDED", Instant.EPOCH, Instant.EPOCH, 0, null));
        }

        @Override
        public boolean requestSync(UUID workspaceId, UUID projectId, Instant now) {
            this.workspaceId = workspaceId;
            this.projectId = projectId;
            return true;
        }

        @Override public List<TrackedProject> claimDueProjects(Instant now, Duration lockDuration, int limit) { return List.of(); }
        @Override public SyncResult completeSuccessfulSync(TrackedProject project, List<com.jundaodsj.insightops.tool.application.github.GitHubRelease> releases, Instant fetchedAt, Instant nextSyncAt) { throw new UnsupportedOperationException(); }
        @Override public void completeFailedSync(TrackedProject project, String errorCode, String errorMessage, Instant failedAt, Instant nextRetryAt) { throw new UnsupportedOperationException(); }
        @Override public UpdatePage listUpdates(ActorContext actor, int page, int size, UUID projectId, boolean unreadOnly) { throw new UnsupportedOperationException(); }
        @Override public long unreadCount(ActorContext actor) { throw new UnsupportedOperationException(); }
        @Override public boolean markRead(ActorContext actor, UUID eventId, Instant readAt) { throw new UnsupportedOperationException(); }
        @Override public int markAllRead(ActorContext actor, Instant readAt) { throw new UnsupportedOperationException(); }
    }
}
