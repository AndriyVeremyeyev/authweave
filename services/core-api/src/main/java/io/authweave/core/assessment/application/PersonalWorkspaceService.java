package io.authweave.core.assessment.application;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.UUID;

import org.jooq.DSLContext;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import static io.authweave.core.generated.jooq.tables.PersonalWorkspaces.PERSONAL_WORKSPACES;
import static io.authweave.core.generated.jooq.tables.Workspaces.WORKSPACES;

@Service
public class PersonalWorkspaceService {

    private final DSLContext dsl;

    public PersonalWorkspaceService(DSLContext dsl) {
        this.dsl = dsl;
    }

    @Transactional
    public UUID provision(String issuer, String subject) {
        // Lock this principal across concurrent first logins, including when no row exists yet.
        dsl.fetch("SELECT pg_advisory_xact_lock(?)", principalLock(issuer, subject));
        UUID existing = dsl.select(PERSONAL_WORKSPACES.WORKSPACE_ID)
                .from(PERSONAL_WORKSPACES)
                .where(PERSONAL_WORKSPACES.ISSUER.eq(issuer)
                        .and(PERSONAL_WORKSPACES.SUBJECT.eq(subject)))
                .fetchOne(PERSONAL_WORKSPACES.WORKSPACE_ID);
        if (existing != null) {
            return existing;
        }

        UUID workspaceId = UUID.randomUUID();
        dsl.insertInto(WORKSPACES).set(WORKSPACES.ID, workspaceId).execute();
        dsl.insertInto(PERSONAL_WORKSPACES)
                .set(PERSONAL_WORKSPACES.ISSUER, issuer)
                .set(PERSONAL_WORKSPACES.SUBJECT, subject)
                .set(PERSONAL_WORKSPACES.WORKSPACE_ID, workspaceId)
                .execute();
        return workspaceId;
    }

    @Transactional(readOnly = true)
    public boolean owns(String issuer, String subject, UUID workspaceId) {
        return dsl.fetchExists(dsl.selectOne().from(PERSONAL_WORKSPACES)
                .where(PERSONAL_WORKSPACES.ISSUER.eq(issuer)
                        .and(PERSONAL_WORKSPACES.SUBJECT.eq(subject))
                        .and(PERSONAL_WORKSPACES.WORKSPACE_ID.eq(workspaceId))));
    }

    private static long principalLock(String issuer, String subject) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            digest.update(issuer.getBytes(StandardCharsets.UTF_8));
            digest.update((byte) 0);
            digest.update(subject.getBytes(StandardCharsets.UTF_8));
            return ByteBuffer.wrap(digest.digest()).getLong();
        } catch (NoSuchAlgorithmException failure) {
            throw new IllegalStateException("SHA-256 is unavailable", failure);
        }
    }
}
