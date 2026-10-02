package com.autoservicehub.repository;

import com.autoservicehub.entity.AiInsight;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.boot.test.autoconfigure.orm.jpa.TestEntityManager;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Repository-level tests for the FR-REP-9 AI Insights query.
 *
 * <p>This exists because a malformed JPQL query does not fail at compile time —
 * it fails when the data is fetched, so the query in
 * {@link AiInsightRepository#findCreatedInPeriod} would otherwise be covered only
 * by mocked unit tests that never exercise it. What is pinned here is that the
 * query parses, that its half-open window is applied correctly at the boundary,
 * and that it orders newest first.
 *
 * <p>Unlike {@code ReportRepositoryQueriesTest}, this class is NOT blocked by the
 * pre-existing H2 reserved-word fault. {@code ai_insights} has no foreign key to
 * {@code vehicles} — the table whose {@code year} column fails to create under
 * H2 — so this slice inserts only rows that can actually be created and runs
 * normally today.
 */
@DataJpaTest(properties = {
        "spring.datasource.url=jdbc:h2:mem:ai-insight-repo-test;DB_CLOSE_DELAY=-1;DB_CLOSE_ON_EXIT=FALSE;NON_KEYWORDS=YEAR",
        "spring.datasource.driver-class-name=org.h2.Driver",
        "spring.jpa.hibernate.ddl-auto=create-drop",
        "spring.jpa.properties.hibernate.dialect=org.hibernate.dialect.H2Dialect"
})
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
class AiInsightRepositoryQueriesTest {

    private static final LocalDate DAY = LocalDate.of(2026, 3, 10);
    private static final LocalDateTime FROM = DAY.atStartOfDay();
    /** Exclusive upper bound: the start of the next day. */
    private static final LocalDateTime TO   = DAY.plusDays(1).atStartOfDay();

    @Autowired AiInsightRepository repository;
    @Autowired TestEntityManager       entityManager;

    private void givenInsight(String featureType, LocalDateTime createdAt) {
        AiInsight insight = new AiInsight();
        insight.setFeatureType(featureType);
        insight.setInputRef("subject-" + featureType);
        insight.setResultJson("{\"predictedItems\":[]}");
        insight.setConfidence(new BigDecimal("0.80"));
        insight.setCreatedAt(createdAt);
        entityManager.persist(insight);
        // BaseEntity.onCreate() unconditionally stamps createdAt with now(), which
        // would place every row at "just now" and make the window untestable. The
        // column is also updatable = false, so it cannot be corrected through the
        // entity afterwards. It is therefore written directly, which is the only
        // way to put a row on a chosen side of the window boundary.
        // Test-fixture plumbing only — production timestamps still come from the
        // lifecycle callback.
        entityManager.getEntityManager()
                .createNativeQuery("UPDATE ai_insights SET created_at = ?1 WHERE id = ?2")
                .setParameter(1, createdAt)
                .setParameter(2, insight.getId())
                .executeUpdate();
        entityManager.clear();
    }

    @Test
    @DisplayName("AIQ1 insights in the window come back newest first")
    void returnsNewestFirst() {
        givenInsight("VEHICLE_DIAGNOSIS",     DAY.atTime(7, 0));
        givenInsight("MAINTENANCE_PREDICTION", DAY.atTime(9, 0));
        entityManager.flush();

        List<AiInsight> rows = repository.findCreatedInPeriod(FROM, TO);

        assertThat(rows).extracting(AiInsight::getFeatureType)
                .containsExactly("MAINTENANCE_PREDICTION", "VEHICLE_DIAGNOSIS");
    }

    @Test
    @DisplayName("AIQ2 the window is half-open: the first instant is included, the last is not")
    void windowIsHalfOpen() {
        // Exactly at the lower bound — included.
        givenInsight("DAMAGE_DETECTION", FROM);
        // Exactly at the exclusive upper bound — belongs to the next day, excluded.
        givenInsight("PARTS_PREDICTION", TO);
        entityManager.flush();

        List<AiInsight> rows = repository.findCreatedInPeriod(FROM, TO);

        assertThat(rows).extracting(AiInsight::getFeatureType).containsExactly("DAMAGE_DETECTION");
    }

    @Test
    @DisplayName("AIQ3 insights outside the window are excluded")
    void excludesOutsideWindow() {
        givenInsight("VEHICLE_DIAGNOSIS", DAY.minusDays(1).atTime(12, 0));
        givenInsight("MAINTENANCE_PREDICTION", DAY.plusDays(1).atTime(12, 0));
        givenInsight("DAMAGE_DETECTION", DAY.atTime(12, 0));
        entityManager.flush();

        List<AiInsight> rows = repository.findCreatedInPeriod(FROM, TO);

        assertThat(rows).extracting(AiInsight::getFeatureType).containsExactly("DAMAGE_DETECTION");
    }

    @Test
    @DisplayName("AIQ4 a window with no insights returns an empty list, never null")
    void emptyWindowReturnsEmptyList() {
        givenInsight("VEHICLE_DIAGNOSIS", DAY.minusDays(5).atTime(12, 0));
        entityManager.flush();

        List<AiInsight> rows = repository.findCreatedInPeriod(FROM, TO);

        assertThat(rows).isNotNull().isEmpty();
    }

    @Test
    @DisplayName("AIQ5 stored columns survive the round trip")
    void mapsStoredColumns() {
        givenInsight("MECHANIC_ASSIGNMENT", DAY.atTime(15, 30));
        entityManager.flush();
        entityManager.clear();

        List<AiInsight> rows = repository.findCreatedInPeriod(FROM, TO);

        assertThat(rows).hasSize(1);
        AiInsight row = rows.get(0);
        assertThat(row.getId()).isNotNull();
        assertThat(row.getFeatureType()).isEqualTo("MECHANIC_ASSIGNMENT");
        assertThat(row.getInputRef()).isEqualTo("subject-MECHANIC_ASSIGNMENT");
        assertThat(row.getResultJson()).isEqualTo("{\"predictedItems\":[]}");
        assertThat(row.getConfidence()).isEqualByComparingTo("0.80");
        assertThat(row.getCreatedAt()).isNotNull();
    }
}