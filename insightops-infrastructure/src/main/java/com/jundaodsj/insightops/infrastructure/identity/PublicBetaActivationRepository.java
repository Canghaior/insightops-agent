package com.jundaodsj.insightops.infrastructure.identity;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;

@Repository
public class PublicBetaActivationRepository {
    private final JdbcClient jdbc;

    public PublicBetaActivationRepository(JdbcClient jdbc) { this.jdbc = jdbc; }

    @Transactional
    public int activateVerifiedRegistrations(Instant now) {
        int activated = jdbc.sql("""
                with verified as (
                    select registration.user_id, max(token.consumed_at) as verified_at
                    from public_registration registration
                    join identity_token token on token.user_id = registration.user_id
                    where registration.status = 'PENDING'
                      and registration.verification_expires_at > :now
                      and token.token_type = 'EMAIL_VERIFICATION'
                      and token.consumed_at is not null
                    group by registration.user_id
                ), activated_registration as (
                    update public_registration registration
                    set status = 'ACTIVE', activated_at = :now, updated_at = :now
                    from verified
                    where registration.user_id = verified.user_id
                      and registration.status = 'PENDING'
                      and registration.verification_expires_at > :now
                    returning registration.user_id, verified.verified_at
                )
                update app_user user_account
                set status = 'ACTIVE', email_verified_at = activated.verified_at, updated_at = :now
                from activated_registration activated
                where user_account.id = activated.user_id
                  and user_account.status = 'PENDING_VERIFICATION'
                """).param("now", timestamp(now)).update();
        return activated;
    }

    private static OffsetDateTime timestamp(Instant value) {
        return OffsetDateTime.ofInstant(value, ZoneOffset.UTC);
    }
}
