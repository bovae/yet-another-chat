package com.bovae.yac.integration;

import jakarta.persistence.EntityManagerFactory;
import org.hibernate.SessionFactory;
import org.hibernate.stat.Statistics;

/**
 * Test-only N+1 query detector backed by Hibernate {@link Statistics}.
 *
 * <p>Wraps an action and reports how many JDBC statements Hibernate prepared while it ran. Tests
 * use this to assert that query counts stay <em>constant</em> as the number of rows grows — a
 * linear growth in prepared statements is the tell-tale signature of an N+1 regression (e.g. a
 * dropped {@code JOIN FETCH} or a per-row lookup replacing a grouped query).
 */
final class HibernateQueryCounter {

    private final Statistics statistics;

    HibernateQueryCounter(EntityManagerFactory entityManagerFactory) {
        this.statistics = entityManagerFactory.unwrap(SessionFactory.class).getStatistics();
        this.statistics.setStatisticsEnabled(true);
    }

    /**
     * Runs {@code action} and returns the number of JDBC statements Hibernate prepared during it.
     * The internal counter is reset before the action runs, so the returned value reflects only the
     * statements issued by {@code action}.
     */
    long countPreparedStatements(Runnable action) {
        statistics.clear();
        action.run();
        return statistics.getPrepareStatementCount();
    }
}
