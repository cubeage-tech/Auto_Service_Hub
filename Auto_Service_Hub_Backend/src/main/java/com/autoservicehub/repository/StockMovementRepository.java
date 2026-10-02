package com.autoservicehub.repository;

import com.autoservicehub.entity.StockMovement;
import com.autoservicehub.projection.PartUsageProjection;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.time.LocalDateTime;
import java.util.List;

/**
 * Spring Data JPA repository for StockMovement. Extends JpaSpecificationExecutor so
 * list/report endpoints (SRS 9, 17) can apply dynamic filters.
 */
@Repository
public interface StockMovementRepository extends JpaRepository<StockMovement, Long>, JpaSpecificationExecutor<StockMovement> {

    /** Movement history for one part, newest first. */
    List<StockMovement> findByPartIdOrderByCreatedAtDescIdDesc(Long partId);

    /** Paged movement history for one part, newest first. */
    Page<StockMovement> findByPartIdOrderByCreatedAtDescIdDesc(Long partId, Pageable pageable);

    /** Parts consumed against one job card, oldest first. */
    List<StockMovement> findByJobCardIdOrderByIdAsc(Long jobCardId);

    /** Paged parts consumed against one job card, oldest first. */
    Page<StockMovement> findByJobCardIdOrderByIdAsc(Long jobCardId, Pageable pageable);

    /** Movements of a given type for one part, newest first. */
    List<StockMovement> findByPartIdAndMovementTypeOrderByIdDesc(Long partId, String movementType);

    // ══════════════════════════════════════════════════════════════════════
    // FR-REP-3: Parts usage
    //
    // Every query here filters to movement_type = 'OUT' and nothing else.
    // IN (goods received) and ADJUSTMENT (a stock correction, possibly negative)
    // are not consumption: counting them would either invent usage that never
    // happened or net a return against a genuine issue. The type is a parameter
    // rather than a literal so the calling service states the rule once, where
    // it can be seen.
    //
    // Windows are half-open, [from, to), and applied to created_at — the moment
    // the movement was recorded — since StockMovement has no date column of its
    // own.
    // ══════════════════════════════════════════════════════════════════════

    /** OUT movements in the window, oldest first. */
    List<StockMovement> findByMovementTypeAndCreatedAtGreaterThanEqualAndCreatedAtLessThanOrderByIdAsc(
            String movementType, LocalDateTime from, LocalDateTime to);

    /** Count of OUT movements in the window. */
    long countByMovementTypeAndCreatedAtGreaterThanEqualAndCreatedAtLessThan(
            String movementType, LocalDateTime from, LocalDateTime to);

    /** Total units consumed by OUT movements in the window. */
    @Query("SELECT COALESCE(SUM(m.quantity), 0) FROM StockMovement m " +
           "WHERE m.movementType = :movementType " +
           "AND m.createdAt >= :from AND m.createdAt < :to")
    long sumQuantityByTypeAndCreatedAtBetween(@Param("movementType") String         movementType,
                                              @Param("from")        LocalDateTime from,
                                              @Param("to")          LocalDateTime to);

    /**
     * Consumption rolled up per part (FR-REP-3).
     *
     * <p>Groups in the database so a report over a long window does not pull
     * every movement row into memory. {@code purchasePrice} is the part's
     * CURRENT price: no historical cost is stored, so any cost derived from it
     * is an approximation and must be labelled as one.
     */
    @Query("SELECT m.part.id AS partId, m.part.sku AS sku, m.part.name AS name, " +
           "       m.part.unit AS unit, SUM(m.quantity) AS quantity, " +
           "       COUNT(m) AS movementCount, m.part.purchasePrice AS purchasePrice " +
           "FROM StockMovement m " +
           "WHERE m.movementType = :movementType " +
           "AND m.createdAt >= :from AND m.createdAt < :to " +
           "GROUP BY m.part.id, m.part.sku, m.part.name, m.part.unit, m.part.purchasePrice " +
           "ORDER BY SUM(m.quantity) DESC, m.part.sku ASC")
    List<PartUsageProjection> sumUsageGroupedByPart(
            @Param("movementType") String         movementType,
            @Param("from")         LocalDateTime from,
            @Param("to")           LocalDateTime to);

    /** As above, restricted to one job card — for a single job's parts sheet. */
    @Query("SELECT m.part.id AS partId, m.part.sku AS sku, m.part.name AS name, " +
           "       m.part.unit AS unit, SUM(m.quantity) AS quantity, " +
           "       COUNT(m) AS movementCount, m.part.purchasePrice AS purchasePrice " +
           "FROM StockMovement m " +
           "WHERE m.movementType = :movementType " +
           "AND m.jobCard.id = :jobCardId " +
           "AND m.createdAt >= :from AND m.createdAt < :to " +
           "GROUP BY m.part.id, m.part.sku, m.part.name, m.part.unit, m.part.purchasePrice " +
           "ORDER BY SUM(m.quantity) DESC, m.part.sku ASC")
    List<PartUsageProjection> sumUsageGroupedByPartForJobCard(
            @Param("movementType") String         movementType,
            @Param("jobCardId")    Long           jobCardId,
            @Param("from")         LocalDateTime from,
            @Param("to")           LocalDateTime to);

    /**
     * As above, restricted to the work of one mechanic (via the job card).
     *
     * <p>The mechanic filter goes through {@code m.jobCard.mechanic}, which is
     * why a movement with no job card attached can never be attributed to a
     * mechanic — it is unassignable rather than being counted for everyone.
     */
    @Query("SELECT m.part.id AS partId, m.part.sku AS sku, m.part.name AS name, " +
           "       m.part.unit AS unit, SUM(m.quantity) AS quantity, " +
           "       COUNT(m) AS movementCount, m.part.purchasePrice AS purchasePrice " +
           "FROM StockMovement m " +
           "WHERE m.movementType = :movementType " +
           "AND m.jobCard.mechanic.id = :mechanicId " +
           "AND m.createdAt >= :from AND m.createdAt < :to " +
           "GROUP BY m.part.id, m.part.sku, m.part.name, m.part.unit, m.part.purchasePrice " +
           "ORDER BY SUM(m.quantity) DESC, m.part.sku ASC")
    List<PartUsageProjection> sumUsageGroupedByPartForMechanic(
            @Param("movementType") String         movementType,
            @Param("mechanicId")   Long           mechanicId,
            @Param("from")         LocalDateTime from,
            @Param("to")           LocalDateTime to);

    /**
     * Consumption per part across the whole window, unfiltered by part — used
     * for the cost side of profit analysis.
     */
    @Query("SELECT COALESCE(SUM(m.quantity * m.part.purchasePrice), 0) FROM StockMovement m " +
           "WHERE m.movementType = :movementType " +
           "AND m.part.purchasePrice IS NOT NULL " +
           "AND m.createdAt >= :from AND m.createdAt < :to")
    java.math.BigDecimal sumEstimatedCostByTypeAndCreatedAtBetween(
            @Param("movementType") String         movementType,
            @Param("from")         LocalDateTime from,
            @Param("to")           LocalDateTime to);
}
