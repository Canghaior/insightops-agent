package com.jundaodsj.insightops.server.auth;

import com.jundaodsj.insightops.identity.application.AccountWorkspaceStore;
import com.jundaodsj.insightops.infrastructure.identity.PublicAccountDeletionRepository;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

import java.time.Clock;
import java.time.Instant;
import java.time.temporal.ChronoUnit;

@Service
public class PublicAccountDeletionService {
    private final PublicAccountDeletionRepository repository;
    private final AuthService auth;
    private final TotpService totp;
    private final IdentityProperties properties;
    private final Clock clock = Clock.systemUTC();
    public PublicAccountDeletionService(PublicAccountDeletionRepository repository, AuthService auth,
                                        TotpService totp, IdentityProperties properties) {
        this.repository = repository; this.auth = auth; this.totp = totp; this.properties = properties;
    }
    public Instant request(AccountWorkspaceStore.AccountRecord actor, String password, String mfaCode) {
        if (!auth.passwordMatches(actor, password)) throw badRequest("Current password is incorrect");
        if (totp.enabled(actor.userId()) && !totp.verify(actor.userId(), mfaCode)) {
            throw badRequest("MFA confirmation is required");
        }
        if (repository.personalWorkspace(actor.userId()).isEmpty()) {
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                    "This endpoint only deletes an unshared public Beta personal Workspace");
        }
        Instant now = clock.instant();
        Instant scheduledAt = now.plus(Math.max(1, properties.getDeletionGraceDays()), ChronoUnit.DAYS);
        repository.request(actor.userId(), now, scheduledAt);
        return scheduledAt;
    }
    private static ResponseStatusException badRequest(String message) {
        return new ResponseStatusException(HttpStatus.BAD_REQUEST, message);
    }
}
