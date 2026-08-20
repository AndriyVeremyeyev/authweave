package io.authweave.core.assessment.persistence;

import org.jooq.DSLContext;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import io.authweave.core.assessment.domain.WorkspaceId;

import static io.authweave.core.generated.jooq.tables.Workspaces.WORKSPACES;

@Repository
public class JooqWorkspaceRepository implements WorkspaceRepository {

    private final DSLContext dsl;

    public JooqWorkspaceRepository(DSLContext dsl) {
        this.dsl = dsl;
    }

    @Override
    @Transactional
    public void insert(WorkspaceId workspaceId) {
        dsl.insertInto(WORKSPACES)
                .set(WORKSPACES.ID, workspaceId.value())
                .execute();
    }

    @Override
    @Transactional(readOnly = true)
    public boolean exists(WorkspaceId workspaceId) {
        return dsl.fetchExists(
                dsl.selectOne()
                        .from(WORKSPACES)
                        .where(WORKSPACES.ID.eq(workspaceId.value())));
    }
}
