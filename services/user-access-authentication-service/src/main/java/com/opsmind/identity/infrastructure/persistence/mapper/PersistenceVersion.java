package com.opsmind.identity.infrastructure.persistence.mapper;

/**
 * Every mutable-aggregate mapper rebuilds a brand-new, detached JPA entity
 * from an immutable domain object on every {@code save()} — the domain
 * object's own {@code version} is already post-transition (each domain
 * transition method returns {@code version + 1}). Passing that value
 * straight through as the entity's {@code @Version} field would make
 * Hibernate's optimistic {@code UPDATE ... WHERE version = ?} check look
 * for a row one version ahead of what is actually persisted — it would
 * never match, and every second {@code save()} would fail with a false
 * {@code ObjectOptimisticLockingFailureException}.
 *
 * <p>{@link #entityVersion} converts the domain's post-transition version
 * back to the pre-transition value the database currently holds (irrelevant
 * for a genuinely new row, since {@code persist()} never uses it in a
 * {@code WHERE} clause). Each entity's own {@code Persistable#isNew()} is a
 * separate, explicit signal — see {@code UserIdentityJpaEntity}'s own
 * javadoc for why the two cannot be derived from a single field.
 */
final class PersistenceVersion {

    private PersistenceVersion() {
    }

    static long entityVersion(long domainVersion) {
        return domainVersion == 0 ? 0 : domainVersion - 1;
    }
}
