package com.autoservicehub.repository;

import com.autoservicehub.entity.Customer;
import com.autoservicehub.entity.Feedback;
import com.autoservicehub.entity.Invoice;
import com.autoservicehub.entity.JobCard;
import com.autoservicehub.entity.Mechanic;
import com.autoservicehub.entity.Part;
import com.autoservicehub.entity.Payment;
import com.autoservicehub.entity.StockMovement;
import com.autoservicehub.entity.Vehicle;
import com.autoservicehub.projection.DateCountProjection;
import com.autoservicehub.projection.DailyRevenueProjection;
import com.autoservicehub.projection.InvoiceStatusTotalProjection;
import com.autoservicehub.projection.MechanicJobCountProjection;
import com.autoservicehub.projection.PartUsageProjection;
import com.autoservicehub.projection.PaymentModeTotalProjection;
import com.autoservicehub.projection.StatusCountProjection;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
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
 * Repository-level tests for the FR-REP report queries.
 *
 * <p>These exist because a malformed JPQL query or a mis-typed projection
 * accessor does not fail at compile time — it fails when the data is fetched. In
 * particular {@link MechanicJobCountProjection#getEmployeeCode()} returns a
 * String because {@code Mechanic.employeeCode} is a String column; declared as
 * Long it would compile but throw at runtime, and only for rows with a value.
 *
 * <p>Every query is exercised against real rows, including the empty case: a
 * report over a window with no activity must return an empty list or a zero,
 * never null and never a fabricated figure.
 *
 * <p>=============================================================================
 * BLOCKED: this class cannot currently run — a PRE-EXISTING test-infrastructure
 * problem, unrelated to the report queries it covers.
 * ============================================================================
 *
 * <p>{@code @DataJpaTest} builds the schema with {@code ddl-auto=create-drop},
 * and that fails in this project. Every {@code @DataJpaTest} slice is affected;
 * the existing ones pass only because none of them touch the tables that fail to
 * create. The observed chain, in order:
 *
 * <ol>
 *   <li>{@code Vehicle} declares a column named {@code year}. YEAR is a reserved
 *       word in H2, so {@code create table vehicles} raises a syntax error and the
 *       table is never created.</li>
 *   <li>{@code job_cards} declares {@code vehicle_id REFERENCES vehicles}, so its
 *       creation fails too — and with it the schema is incomplete.</li>
 * </ol>
 *
 * <p>The same reserved-word DDL problem affects {@code appointments}, so the
 * cascade continues even past that point.
 *
 * <p>Why no workaround is applied here: {@code NON_RESERVED_KEYS} was removed from
 * H2 2.x (confirmed absent from h2-2.2.224), {@code MODE=MySQL} does not
 * un-reserve YEAR, {@code globally_quoted_identifiers} breaks case sensitivity for
 * every other table, and the proper fix — renaming the column — is a change to
 * the production MySQL schema and is out of scope for a test. That belongs to a
 * separate, explicitly-approved change.
 *
 * <p>The tests below are therefore kept intact and unweakened. They compile, and
 * they are written against real repository queries; they will run unchanged once
 * the schema can be created. They are excluded from the default build so a
 * pre-existing infrastructure fault does not mask the health of the rest of the
 * suite — run them explicitly with:
 *
 * <pre>{@code mvn test -Dtest=ReportRepositoryQueriesTest -DfailIfNoSpecifiedTests=false}</pre>
 *
 * <p>Properties below deliberately mirror the sibling persistence slices exactly:
 * no MODE, no NON_RESERVED_KEYS, no quoted identifiers, no global dialect change.
 */
@DataJpaTest(properties = {
        "spring.datasource.url=jdbc:h2:mem:report-repo-test;DB_CLOSE_DELAY=-1;DB_CLOSE_ON_EXIT=FALSE",
        "spring.datasource.driver-class-name=org.h2.Driver",
        "spring.jpa.hibernate.ddl-auto=create-drop",
        "spring.jpa.properties.hibernate.dialect=org.hibernate.dialect.H2Dialect"
})
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
class ReportRepositoryQueriesTest {

    private static final String OUT        = "OUT";
    private static final String IN         = "IN";
    private static final String ADJUSTMENT = "ADJUSTMENT";
    private static final String DELIVERED  = "DELIVERED";
    private static final String SUCCESS    = "SUCCESS";

    /** A fixed day so boundary assertions do not drift with the clock. */
    private static final LocalDate DAY = LocalDate.of(2026, 3, 10);

    @Autowired TestEntityManager       em;
    @Autowired JobCardRepository       jobCards;
    @Autowired CustomerRepository      customers;
    @Autowired MechanicRepository      mechanics;
    @Autowired PartRepository          parts;
    @Autowired StockMovementRepository movements;
    @Autowired InvoiceRepository       invoices;
    @Autowired PaymentRepository       payments;
    @Autowired VehicleRepository       vehicles;
    @Autowired FeedbackRepository      feedback;

    // ── Fixtures ───────────────────────────────────────────────────────────

    private Customer givenCustomer(String name) {
        Customer c = new Customer();
        c.setName(name);
        c.setPhone("+91" + Math.abs(name.hashCode() % 100000000));
        c.setStatus("ACTIVE");
        return customers.save(c);
    }

    private Mechanic givenMechanic(String name, String code) {
        Mechanic m = new Mechanic();
        m.setName(name);
        m.setEmployeeCode(code);
        m.setStatus("ACTIVE");
        return mechanics.save(m);
    }

    private Vehicle givenVehicle(Customer owner) {
        Vehicle v = new Vehicle();
        v.setCustomer(owner);
        v.setRegistrationNo("REG-" + Math.abs(owner.getName().hashCode() % 100000));
        v.setMake("Maruti");
        v.setModel("Swift");
        return vehicles.save(v);
    }

    private Part givenPart(String sku, String name, String purchasePrice) {
        Part p = new Part();
        p.setSku(sku);
        p.setName(name);
        p.setUnit("PCS");
        p.setStockQty(100);
        p.setMinStock(10);
        p.setPurchasePrice(purchasePrice == null ? null : new BigDecimal(purchasePrice));
        return parts.save(p);
    }

    private JobCard givenJobCard(Customer customer, Vehicle vehicle, Mechanic mechanic,
                                 String status, String serviceType, LocalDateTime assigned) {
        JobCard jc = new JobCard();
        jc.setCustomer(customer);
        jc.setVehicle(vehicle);
        jc.setMechanic(mechanic);
        jc.setStatus(status);
        jc.setServiceType(serviceType);
        jc.setAssignedDate(assigned);
        jc.setJobCardNumber("JC-" + Math.abs((assigned == null ? 0L : assigned.hashCode())
                + (status == null ? 0 : status.hashCode())));
        return jobCards.save(jc);
    }

    /**
     * Records a stock movement.
     *
     * <p>No timestamp is taken: {@code created_at} is stamped by @PrePersist, so a
     * movement cannot be back-dated and lands on today. The parts-usage queries are
     * therefore filtered with {@link #todayFrom()}/{@link #todayTo()}, never around
     * the fixed {@link #DAY} — a historical window would silently match nothing.
     */
    private StockMovement givenMovement(Part part, JobCard jobCard, String type, int quantity) {
        StockMovement m = new StockMovement();
        m.setPart(part);
        m.setJobCard(jobCard);
        m.setMovementType(type);
        m.setQuantity(quantity);
        m.setStockBefore(100);
        m.setStockAfter(100 - quantity);
        return movements.save(m);
    }

    private Invoice givenInvoice(JobCard jobCard, String status, LocalDate date, String total) {
        Invoice i = new Invoice();
        i.setJobCard(jobCard);
        i.setStatus(status);
        i.setInvoiceDate(date);
        i.setTotal(new BigDecimal(total));
        i.setSubtotal(new BigDecimal(total));
        return invoices.save(i);
    }

    private Payment givenPayment(Invoice invoice, String status, String amount,
                                 LocalDateTime paidAt, String mode) {
        Payment p = new Payment();
        p.setInvoice(invoice);
        p.setStatus(status);
        p.setAmount(new BigDecimal(amount));
        p.setPaidAt(paidAt);
        p.setMode(mode);
        return payments.save(p);
    }

    private Feedback givenFeedback(Customer customer, JobCard jobCard, int rating) {
        Feedback f = new Feedback();
        f.setCustomer(customer);
        f.setJobCard(jobCard);
        f.setRating(rating);
        return f;
    }

    /** Half-open window covering exactly DAY. */
    private LocalDateTime from() {
        return DAY.atStartOfDay();
    }

    private LocalDateTime to() {
        return DAY.plusDays(1).atStartOfDay();
    }

    /** Half-open window covering today, for columns stamped at persist time. */
    private LocalDateTime todayFrom() {
        return LocalDate.now().atStartOfDay();
    }

    private LocalDateTime todayTo() {
        return LocalDate.now().plusDays(1).atStartOfDay();
    }

    // ── Parts usage (FR-REP-3) ────────────────────────────────────────────

    @Nested
    @DisplayName("Parts usage")
    class PartsUsage {

        @Test
        @DisplayName("RQ1 only OUT movements count as consumption")
        void onlyOutMovementsAreCounted() {
            Customer c = givenCustomer("Ravi");
            Vehicle v = givenVehicle(c);
            Mechanic m = givenMechanic("Anil", "MECH-1");
            JobCard jc = givenJobCard(c, v, m, DELIVERED, "SERVICE", from().plusHours(9));
            Part brake = givenPart("BRK-1", "Brake Pad", "500.00");

            givenMovement(brake, jc, OUT, 4);
            givenMovement(brake, jc, OUT, 2);
            // Neither of these is consumption.
            givenMovement(brake, null, IN, 50);
            givenMovement(brake, null, ADJUSTMENT, 3);
            em.flush();
            em.clear();

            List<PartUsageProjection> usage =
                    movements.sumUsageGroupedByPart(OUT, todayFrom(), todayTo());

            assertThat(usage).hasSize(1);
            PartUsageProjection row = usage.get(0);
            assertThat(row.getSku()).isEqualTo("BRK-1");
            assertThat(row.getQuantity()).isEqualTo(6L);
            assertThat(row.getMovementCount()).isEqualTo(2L);
        }

        @Test
        @DisplayName("RQ2 the OUT total ignores IN and ADJUSTMENT quantities")
        void outTotalExcludesOtherTypes() {
            Part brake = givenPart("BRK-1", "Brake Pad", "500.00");
            givenMovement(brake, null, OUT, 4);
            givenMovement(brake, null, IN, 100);
            givenMovement(brake, null, ADJUSTMENT, 7);
            em.flush();

            assertThat(movements.sumQuantityByTypeAndCreatedAtBetween(OUT, todayFrom(), todayTo()))
                    .isEqualTo(4L);
            assertThat(movements.countByMovementTypeAndCreatedAtGreaterThanEqualAndCreatedAtLessThan(
                    OUT, todayFrom(), todayTo())).isEqualTo(1L);
        }

        @Test
        @DisplayName("RQ3 consumption can be scoped to one job card")
        void usageForOneJobCard() {
            Customer c = givenCustomer("Ravi");
            Vehicle v = givenVehicle(c);
            Mechanic m = givenMechanic("Anil", "MECH-1");
            JobCard first  = givenJobCard(c, v, m, DELIVERED, "SERVICE", from().plusHours(9));
            JobCard second = givenJobCard(c, v, m, DELIVERED, "SERVICE", from().plusHours(11));
            Part brake = givenPart("BRK-1", "Brake Pad", "500.00");

            givenMovement(brake, first, OUT, 4);
            givenMovement(brake, second, OUT, 9);
            em.flush();
            em.clear();

            List<PartUsageProjection> usage = movements
                    .sumUsageGroupedByPartForJobCard(OUT, first.getId(), todayFrom(), todayTo());

            assertThat(usage).hasSize(1);
            assertThat(usage.get(0).getQuantity()).isEqualTo(4L);
        }

        @Test
        @DisplayName("RQ4 consumption can be scoped to one mechanic via the job card")
        void usageForOneMechanic() {
            Customer c = givenCustomer("Ravi");
            Vehicle v = givenVehicle(c);
            Mechanic anil = givenMechanic("Anil", "MECH-1");
            Mechanic sur  = givenMechanic("Sur", "MECH-2");
            JobCard anilJob = givenJobCard(c, v, anil, DELIVERED, "SERVICE", from().plusHours(9));
            JobCard surJob  = givenJobCard(c, v, sur,  DELIVERED, "SERVICE", from().plusHours(11));
            Part brake = givenPart("BRK-1", "Brake Pad", "500.00");

            givenMovement(brake, anilJob, OUT, 4);
            givenMovement(brake, surJob,  OUT, 8);
            em.flush();
            em.clear();

            List<PartUsageProjection> anilUsage = movements
                    .sumUsageGroupedByPartForMechanic(OUT, anil.getId(), todayFrom(), todayTo());

            assertThat(anilUsage).hasSize(1);
            assertThat(anilUsage.get(0).getQuantity()).isEqualTo(4L);
        }

        @Test
        @DisplayName("RQ5 the estimated parts cost uses the part's own purchase price")
        void estimatedCostUsesPurchasePrice() {
            Part brake = givenPart("BRK-1", "Brake Pad", "500.00");
            givenMovement(brake, null, OUT, 4);
            em.flush();

            // 4 x 500.00
            assertThat(movements.sumEstimatedCostByTypeAndCreatedAtBetween(
                    OUT, todayFrom(), todayTo())).isEqualByComparingTo("2000.00");
        }

        @Test
        @DisplayName("RQ6 a part with no purchase price is skipped, not counted as free")
        void partWithoutPurchasePriceIsExcludedFromCost() {
            Part unknown = givenPart("MYS-1", "Mystery Part", null);
            givenMovement(unknown, null, OUT, 5);
            em.flush();

            // The quantity is real and reported…
            assertThat(movements.sumQuantityByTypeAndCreatedAtBetween(OUT, todayFrom(), todayTo()))
                    .isEqualTo(5L);
            // …but no cost is invented for it.
            assertThat(movements.sumEstimatedCostByTypeAndCreatedAtBetween(
                    OUT, todayFrom(), todayTo())).isEqualByComparingTo("0");
        }

        @Test
        @DisplayName("RQ7 no OUT movements yields an empty result, not null")
        void noMovementsYieldsEmptyList() {
            em.flush();

            assertThat(movements.sumUsageGroupedByPart(OUT, todayFrom(), todayTo())).isEmpty();
            assertThat(movements.sumQuantityByTypeAndCreatedAtBetween(
                    OUT, todayFrom(), todayTo())).isZero();
        }
    }

    // ── Status breakdown and the report filters (FR-REP-1, FR-REP-7) ──────

    @Nested
    @DisplayName("Status breakdown and filters")
    class StatusAndFilters {

        /** Three jobs on DAY across two statuses. */
        private void givenMixedJobs() {
            Customer c = givenCustomer("Ravi");
            Vehicle v = givenVehicle(c);
            Mechanic m = givenMechanic("Anil", "MECH-1");
            givenJobCard(c, v, m, DELIVERED,   "SERVICE", from().plusHours(9));
            givenJobCard(c, v, m, DELIVERED,   "SERVICE", from().plusHours(10));
            givenJobCard(c, v, m, "IN_REPAIR", "SERVICE", from().plusHours(11));
            em.flush();
        }

        @Test
        @DisplayName("RQ8 the status breakdown counts each status in the window")
        void statusBreakdownCountsEachStatus() {
            givenMixedJobs();

            List<StatusCountProjection> rows = jobCards.countGroupedByStatus(from(), to());

            assertThat(rows).hasSize(2);
            assertThat(rows).extracting(StatusCountProjection::getStatus)
                    .containsExactly("DELIVERED", "IN_REPAIR");
            assertThat(rows).extracting(StatusCountProjection::getStatusCount)
                    .containsExactly(2L, 1L);
        }

        @Test
        @DisplayName("RQ9 the breakdown totals equal the overall job count")
        void breakdownTotalsMatchTheWhole() {
            givenMixedJobs();

            long summed = jobCards.countGroupedByStatus(from(), to()).stream()
                    .mapToLong(StatusCountProjection::getStatusCount).sum();

            assertThat(summed).isEqualTo(jobCards.countByAssignedDateBetween(from(), to()));
        }

        @Test
        @DisplayName("RQ10 the date window excludes jobs on either side of it")
        void dateWindowExcludesOutsideJobs() {
            Customer c = givenCustomer("Ravi");
            Vehicle v = givenVehicle(c);
            Mechanic m = givenMechanic("Anil", "MECH-1");
            givenJobCard(c, v, m, DELIVERED, "SERVICE", from().minusDays(1).plusHours(9));
            givenJobCard(c, v, m, DELIVERED, "SERVICE", from().plusHours(9));
            givenJobCard(c, v, m, DELIVERED, "SERVICE", to().plusDays(1));
            em.flush();

            assertThat(jobCards.countByAssignedDateBetween(from(), to())).isEqualTo(1L);
        }

        @Test
        @DisplayName("RQ11 jobs exactly on the boundary belong to the right day")
        void boundaryJobsAreHandledByTheHalfOpenWindow() {
            Customer c = givenCustomer("Ravi");
            Vehicle v = givenVehicle(c);
            Mechanic m = givenMechanic("Anil", "MECH-1");
            givenJobCard(c, v, m, DELIVERED, "SERVICE", from());   // 00:00:00, included
            givenJobCard(c, v, m, DELIVERED, "SERVICE", to());     // next 00:00, excluded
            em.flush();

            assertThat(jobCards.countByAssignedDateBetween(from(), to())).isEqualTo(1L);
            assertThat(jobCards.countByAssignedDateBetween(to(), to().plusDays(1))).isEqualTo(1L);
        }

        @Test
        @DisplayName("RQ12 the non-delivered count is the complement of completed")
        void nonDeliveredIsComplementOfDelivered() {
            givenMixedJobs();

            long total = jobCards.countByAssignedDateBetween(from(), to());
            long delivered = jobCards.countByStatusAndAssignedDateBetween(DELIVERED, from(), to());
            long notDelivered = jobCards.countByAssignedDateRangeAndStatusNot(
                    from(), to(), DELIVERED);

            assertThat(delivered).isEqualTo(2L);
            assertThat(notDelivered).isEqualTo(1L);
            assertThat(delivered + notDelivered).isEqualTo(total);
        }

        @Test
        @DisplayName("RQ13 the status filter narrows the result set")
        void statusFilterNarrowsResults() {
            givenMixedJobs();

            List<JobCard> onlyDelivered = jobCards.findForReport(
                    from(), to(), null, null, null, DELIVERED);

            assertThat(onlyDelivered).hasSize(2);
            assertThat(onlyDelivered).allMatch(j -> DELIVERED.equals(j.getStatus()));
        }

        @Test
        @DisplayName("RQ14 the service filter matches case-insensitively")
        void serviceFilterIsCaseInsensitive() {
            Customer c = givenCustomer("Ravi");
            Vehicle v = givenVehicle(c);
            Mechanic m = givenMechanic("Anil", "MECH-1");
            givenJobCard(c, v, m, DELIVERED, "SERVICE",     from().plusHours(9));
            givenJobCard(c, v, m, DELIVERED, "body_repair", from().plusHours(10));
            em.flush();

            assertThat(jobCards.findForReport(from(), to(), null, null, "service", null)).hasSize(1);
            assertThat(jobCards.findForReport(from(), to(), null, null, "BODY_REPAIR", null)).hasSize(1);
            assertThat(jobCards.findForReport(from(), to(), null, null, "TYRES", null)).isEmpty();
        }

        @Test
        @DisplayName("RQ15 the mechanic filter returns only that mechanic's jobs")
        void mechanicFilterNarrowsResults() {
            Customer c = givenCustomer("Ravi");
            Vehicle v = givenVehicle(c);
            Mechanic anil = givenMechanic("Anil", "MECH-1");
            Mechanic sur  = givenMechanic("Sur", "MECH-2");
            givenJobCard(c, v, anil, DELIVERED, "SERVICE", from().plusHours(9));
            givenJobCard(c, v, sur,  DELIVERED, "SERVICE", from().plusHours(10));
            em.flush();

            List<JobCard> anilJobs = jobCards.findForReport(
                    from(), to(), anil.getId(), null, null, null);

            assertThat(anilJobs).hasSize(1);
            assertThat(anilJobs.get(0).getMechanic().getId()).isEqualTo(anil.getId());
        }

        @Test
        @DisplayName("RQ16 the vehicle filter returns only that vehicle's jobs")
        void vehicleFilterNarrowsResults() {
            Customer ravi = givenCustomer("Ravi");
            Customer neha = givenCustomer("Neha");
            Vehicle raviCar = givenVehicle(ravi);
            Vehicle nehaCar = givenVehicle(neha);
            Mechanic m = givenMechanic("Anil", "MECH-1");
            givenJobCard(ravi, raviCar, m, DELIVERED, "SERVICE", from().plusHours(9));
            givenJobCard(neha, nehaCar, m, DELIVERED, "SERVICE", from().plusHours(10));
            em.flush();

            List<JobCard> raviJobs = jobCards.findForReport(
                    from(), to(), null, raviCar.getId(), null, null);

            assertThat(raviJobs).hasSize(1);
            assertThat(raviJobs.get(0).getVehicle().getId()).isEqualTo(raviCar.getId());
        }

        @Test
        @DisplayName("RQ17 combined filters intersect rather than union")
        void combinedFiltersIntersect() {
            Customer c = givenCustomer("Ravi");
            Vehicle v = givenVehicle(c);
            Mechanic anil = givenMechanic("Anil", "MECH-1");
            Mechanic sur  = givenMechanic("Sur", "MECH-2");
            givenJobCard(c, v, anil, DELIVERED,   "SERVICE",     from().plusHours(9));
            givenJobCard(c, v, anil, "IN_REPAIR", "SERVICE",     from().plusHours(10));
            givenJobCard(c, v, sur,  DELIVERED,   "SERVICE",     from().plusHours(11));
            givenJobCard(c, v, sur,  DELIVERED,   "body_repair", from().plusHours(12));
            em.flush();

            List<JobCard> matching = jobCards.findForReport(
                    from(), to(), anil.getId(), v.getId(), "SERVICE", DELIVERED);

            assertThat(matching).hasSize(1);
            assertThat(matching.get(0).getMechanic().getId()).isEqualTo(anil.getId());
        }

        @Test
        @DisplayName("RQ18 an empty window returns empty lists, never null")
        void emptyWindowReturnsEmptyResults() {
            em.flush();

            assertThat(jobCards.findForReport(from(), to(), null, null, null, null)).isEmpty();
            assertThat(jobCards.countForReport(from(), to(), null, null, null, null)).isZero();
            assertThat(jobCards.countGroupedByStatus(from(), to())).isEmpty();
            assertThat(jobCards.countByAssignedDateRange(from(), to())).isZero();
        }

        @Test
        @DisplayName("RQ19 the filter dropdown sources list real values only")
        void distinctValueSourcesExcludeNulls() {
            Customer c = givenCustomer("Ravi");
            Vehicle v = givenVehicle(c);
            Mechanic m = givenMechanic("Anil", "MECH-1");
            givenJobCard(c, v, m, DELIVERED, "SERVICE", from());
            givenJobCard(c, v, m, "IN_REPAIR", "TYRES", from().plusHours(1));
            em.flush();

            assertThat(jobCards.findDistinctServiceTypes()).containsExactly("SERVICE", "TYRES");
            assertThat(jobCards.findDistinctStatuses()).containsExactly("IN_REPAIR", "DELIVERED");
        }
    }

    // ── Mechanic performance (FR-REP-2) ───────────────────────────────────

    @Nested
    @DisplayName("Mechanic performance")
    class MechanicPerformance {

        @Test
        @DisplayName("RQ20 the mechanic projection maps employeeCode as a String")
        void employeeCodeMapsAsString() {
            Mechanic anil = givenMechanic("Anil", "MECH-001");
            Customer c = givenCustomer("Ravi");
            Vehicle v = givenVehicle(c);
            givenJobCard(c, v, anil, DELIVERED, "SERVICE", from().plusHours(9));
            em.flush();
            em.clear();

            MechanicJobCountProjection row = jobCards
                    .countJobsGroupedByMechanic(from(), to(), null, DELIVERED).stream()
                    .filter(r -> r.getMechanicId().equals(anil.getId()))
                    .findFirst().orElseThrow();

            // The column holds a String like 'MECH-001'. Declaring this accessor as
            // Long would compile but fail to map at runtime.
            assertThat(row.getEmployeeCode()).isEqualTo("MECH-001");
            assertThat(row.getMechanicName()).isEqualTo("Anil");
            assertThat(row.getAssignedJobs()).isEqualTo(1L);
            assertThat(row.getCompletedJobs()).isEqualTo(1L);
        }

        @Test
        @DisplayName("RQ21 assigned and completed counts are reported separately")
        void assignedAndCompletedAreDistinct() {
            Customer c = givenCustomer("Ravi");
            Vehicle v = givenVehicle(c);
            Mechanic anil = givenMechanic("Anil", "MECH-1");
            givenJobCard(c, v, anil, DELIVERED,   "SERVICE", from().plusHours(9));
            givenJobCard(c, v, anil, DELIVERED,   "SERVICE", from().plusHours(10));
            givenJobCard(c, v, anil, "IN_REPAIR", "SERVICE", from().plusHours(11));
            em.flush();
            em.clear();

            MechanicJobCountProjection row = jobCards
                    .countJobsGroupedByMechanic(from(), to(), anil.getId(), DELIVERED).get(0);

            assertThat(row.getAssignedJobs()).isEqualTo(3L);
            assertThat(row.getCompletedJobs()).isEqualTo(2L);
        }

        @Test
        @DisplayName("RQ22 a mechanic with no jobs still appears, with zero counts")
        void idleMechanicAppearsWithZeroCounts() {
            Mechanic idle = givenMechanic("Sur", "MECH-2");
            em.flush();
            em.clear();

            MechanicJobCountProjection row = jobCards
                    .countJobsGroupedByMechanic(from(), to(), null, DELIVERED).stream()
                    .filter(r -> r.getMechanicId().equals(idle.getId()))
                    .findFirst().orElseThrow();

            // Being omitted would read as "not measured" rather than "did nothing".
            assertThat(row.getAssignedJobs()).isZero();
            assertThat(row.getCompletedJobs()).isZero();
        }

        @Test
        @DisplayName("RQ23 the mechanic filter restricts the aggregation to one mechanic")
        void mechanicFilterRestrictsAggregation() {
            Customer c = givenCustomer("Ravi");
            Vehicle v = givenVehicle(c);
            Mechanic anil = givenMechanic("Anil", "MECH-1");
            Mechanic sur  = givenMechanic("Sur", "MECH-2");
            givenJobCard(c, v, anil, DELIVERED, "SERVICE", from().plusHours(9));
            givenJobCard(c, v, sur,  DELIVERED, "SERVICE", from().plusHours(10));
            em.flush();
            em.clear();

            List<MechanicJobCountProjection> rows =
                    jobCards.countJobsGroupedByMechanic(from(), to(), anil.getId(), DELIVERED);

            assertThat(rows).hasSize(1);
            assertThat(rows.get(0).getMechanicId()).isEqualTo(anil.getId());
        }

        @Test
        @DisplayName("RQ24 jobs outside the window are not attributed to the mechanic")
        void mechanicAggregationRespectsTheWindow() {
            Customer c = givenCustomer("Ravi");
            Vehicle v = givenVehicle(c);
            Mechanic anil = givenMechanic("Anil", "MECH-1");
            givenJobCard(c, v, anil, DELIVERED, "SERVICE", from().minusDays(2));
            em.flush();
            em.clear();

            MechanicJobCountProjection row = jobCards
                    .countJobsGroupedByMechanic(from(), to(), anil.getId(), DELIVERED).get(0);

            assertThat(row.getAssignedJobs()).isZero();
        }

        @Test
        @DisplayName("RQ25 a mechanic's average rating is null, not zero, when unrated")
        void averageRatingIsNullWhenUnrated() {
            Mechanic anil = givenMechanic("Anil", "MECH-1");
            em.flush();
            em.clear();

            // "Nobody rated me" must not look like "rated zero".
            assertThat(feedback.findAverageRatingByMechanicId(anil.getId())).isNull();
            assertThat(feedback.countByJobCardMechanicId(anil.getId())).isZero();
        }

        @Test
        @DisplayName("RQ26 customer ratings are attributed via the job card")
        void ratingsComeFromFeedback() {
            Customer c = givenCustomer("Ravi");
            Vehicle v = givenVehicle(c);
            Mechanic anil = givenMechanic("Anil", "MECH-1");
            JobCard jc = givenJobCard(c, v, anil, DELIVERED, "SERVICE", from().plusHours(9));
            em.flush();

            feedback.save(givenFeedback(c, jc, 5));
            feedback.save(givenFeedback(c, jc, 2));
            em.flush();
            em.clear();

            assertThat(feedback.findAverageRatingByMechanicId(anil.getId())).isEqualTo(3.5);
            assertThat(feedback.countByJobCardMechanicId(anil.getId())).isEqualTo(2L);
            assertThat(feedback.findAverageRatingByMechanicIdAndDateRange(
                    anil.getId(), from(), to())).isEqualTo(3.5);
        }
    }

    // ── Revenue and payments (FR-REP-4) ───────────────────────────────────

    @Nested
    @DisplayName("Revenue and payments")
    class RevenueAndPayments {

        @Test
        @DisplayName("RQ27 revenue totals are summed over the invoice date range")
        void revenueTotalsOverRange() {
            Customer c = givenCustomer("Ravi");
            Vehicle v = givenVehicle(c);
            Mechanic m = givenMechanic("Anil", "MECH-1");
            JobCard jc = givenJobCard(c, v, m, DELIVERED, "SERVICE", from().plusHours(9));
            givenInvoice(jc, "PAID",   DAY,            "1000.00");
            givenInvoice(jc, "UNPAID", DAY,            "500.00");
            givenInvoice(jc, "PAID",   DAY.plusDays(5), "200.00");
            em.flush();

            assertThat(invoices.sumTotalByInvoiceDateBetween(DAY, DAY))
                    .isEqualByComparingTo("1500.00");
            assertThat(invoices.countByInvoiceDateBetween(DAY, DAY)).isEqualTo(2L);
            assertThat(invoices.sumTotalByInvoiceDateBetween(DAY, DAY.plusDays(7)))
                    .isEqualByComparingTo("1700.00");
        }

        @Test
        @DisplayName("RQ28 the invoice-status breakdown reports count and value per status")
        void invoiceStatusBreakdown() {
            Customer c = givenCustomer("Ravi");
            Vehicle v = givenVehicle(c);
            Mechanic m = givenMechanic("Anil", "MECH-1");
            JobCard jc = givenJobCard(c, v, m, DELIVERED, "SERVICE", from().plusHours(9));
            givenInvoice(jc, "PAID",   DAY, "1000.00");
            givenInvoice(jc, "PAID",   DAY, "500.00");
            givenInvoice(jc, "UNPAID", DAY, "250.00");
            em.flush();
            em.clear();

            List<InvoiceStatusTotalProjection> rows =
                    invoices.countAndTotalGroupedByStatus(DAY, DAY);

            assertThat(rows).hasSize(2);
            InvoiceStatusTotalProjection paid = rows.stream()
                    .filter(r -> "PAID".equals(r.getStatus())).findFirst().orElseThrow();
            assertThat(paid.getInvoiceCount()).isEqualTo(2L);
            assertThat(paid.getTotal()).isEqualByComparingTo("1500.00");
        }

        @Test
        @DisplayName("RQ29 the daily revenue trend groups by invoice date")
        void dailyRevenueTrend() {
            Customer c = givenCustomer("Ravi");
            Vehicle v = givenVehicle(c);
            Mechanic m = givenMechanic("Anil", "MECH-1");
            JobCard jc = givenJobCard(c, v, m, DELIVERED, "SERVICE", from().plusHours(9));
            givenInvoice(jc, "PAID", DAY,            "1000.00");
            givenInvoice(jc, "PAID", DAY,            "500.00");
            givenInvoice(jc, "PAID", DAY.plusDays(1), "250.00");
            em.flush();
            em.clear();

            List<DailyRevenueProjection> rows =
                    invoices.sumGroupedByInvoiceDate(DAY, DAY.plusDays(1));

            assertThat(rows).hasSize(2);
            DailyRevenueProjection first = rows.get(0);
            assertThat(first.getInvoiceDate()).isEqualTo(DAY);
            assertThat(first.getInvoiceCount()).isEqualTo(2L);
            assertThat(first.getTotal()).isEqualByComparingTo("1500.00");
        }

        @Test
        @DisplayName("RQ30 only SUCCESS payments count as collected")
        void onlySuccessfulPaymentsCount() {
            Customer c = givenCustomer("Ravi");
            Vehicle v = givenVehicle(c);
            Mechanic m = givenMechanic("Anil", "MECH-1");
            JobCard jc = givenJobCard(c, v, m, DELIVERED, "SERVICE", from().plusHours(9));
            Invoice inv = givenInvoice(jc, "PARTIALLY_PAID", DAY, "1000.00");

            givenPayment(inv, SUCCESS,    "400.00", from().plusHours(10), "CASH");
            givenPayment(inv, SUCCESS,    "200.00", from().plusHours(11), "CARD");
            givenPayment(inv, "FAILED",   "999.00", from().plusHours(12), "CASH");
            givenPayment(inv, "REFUNDED", "500.00", from().plusHours(13), "CARD");
            em.flush();

            assertThat(payments.sumAmountByStatusAndPaidAtBetween(SUCCESS, from(), to()))
                    .isEqualByComparingTo("600.00");
            assertThat(payments.countByStatusIgnoreCaseAndPaidAtGreaterThanEqualAndPaidAtLessThan(
                    SUCCESS, from(), to())).isEqualTo(2L);
        }

        @Test
        @DisplayName("RQ31 payment status matching is case-insensitive")
        void paymentStatusMatchingIsCaseInsensitive() {
            Customer c = givenCustomer("Ravi");
            Vehicle v = givenVehicle(c);
            Mechanic m = givenMechanic("Anil", "MECH-1");
            JobCard jc = givenJobCard(c, v, m, DELIVERED, "SERVICE", from().plusHours(9));
            Invoice inv = givenInvoice(jc, "PAID", DAY, "1000.00");
            givenPayment(inv, "success", "300.00", from().plusHours(10), "CASH");
            em.flush();

            assertThat(payments.sumAmountByStatusAndPaidAtBetween(SUCCESS, from(), to()))
                    .isEqualByComparingTo("300.00");
        }

        @Test
        @DisplayName("RQ32 payments outside the window are excluded")
        void paymentsRespectTheWindow() {
            Customer c = givenCustomer("Ravi");
            Vehicle v = givenVehicle(c);
            Mechanic m = givenMechanic("Anil", "MECH-1");
            JobCard jc = givenJobCard(c, v, m, DELIVERED, "SERVICE", from().plusHours(9));
            Invoice inv = givenInvoice(jc, "PAID", DAY, "1000.00");
            givenPayment(inv, SUCCESS, "400.00", from().minusDays(1), "CASH");
            givenPayment(inv, SUCCESS, "600.00", from().plusHours(10), "CASH");
            em.flush();

            assertThat(payments.sumAmountByStatusAndPaidAtBetween(SUCCESS, from(), to()))
                    .isEqualByComparingTo("600.00");
        }

        @Test
        @DisplayName("RQ33 payments can be grouped by mode")
        void paymentsGroupedByMode() {
            Customer c = givenCustomer("Ravi");
            Vehicle v = givenVehicle(c);
            Mechanic m = givenMechanic("Anil", "MECH-1");
            JobCard jc = givenJobCard(c, v, m, DELIVERED, "SERVICE", from().plusHours(9));
            Invoice inv = givenInvoice(jc, "PAID", DAY, "1000.00");
            givenPayment(inv, SUCCESS, "700.00", from().plusHours(10), "CASH");
            givenPayment(inv, SUCCESS, "300.00", from().plusHours(11), "CARD");
            em.flush();
            em.clear();

            List<PaymentModeTotalProjection> rows = payments.sumGroupedByMode(SUCCESS, from(), to());

            assertThat(rows).hasSize(2);
            PaymentModeTotalProjection cash = rows.stream()
                    .filter(r -> "CASH".equals(r.getMode())).findFirst().orElseThrow();
            assertThat(cash.getPaymentCount()).isEqualTo(1L);
            assertThat(cash.getTotal()).isEqualByComparingTo("700.00");
        }

        @Test
        @DisplayName("RQ34 no payments yields zero and empty lists, never null")
        void noPaymentsYieldsZero() {
            em.flush();

            assertThat(payments.sumAmountByStatusAndPaidAtBetween(SUCCESS, from(), to()))
                    .isEqualByComparingTo("0");
            assertThat(payments.sumGroupedByMode(SUCCESS, from(), to())).isEmpty();
        }

        @Test
        @DisplayName("RQ35 revenue can be filtered by job status and mechanic")
        void revenueRespectsJobFilters() {
            Customer c = givenCustomer("Ravi");
            Vehicle v = givenVehicle(c);
            Mechanic anil = givenMechanic("Anil", "MECH-1");
            Mechanic sur  = givenMechanic("Sur", "MECH-2");
            JobCard anilDone = givenJobCard(c, v, anil, DELIVERED, "SERVICE", from().plusHours(9));
            JobCard surOpen  = givenJobCard(c, v, sur,  "IN_REPAIR", "SERVICE", from().plusHours(10));
            givenInvoice(anilDone, "PAID", DAY, "1000.00");
            givenInvoice(surOpen,  "PAID", DAY, "800.00");
            em.flush();

            assertThat(invoices.sumTotalByDateRangeWithJobFilters(DAY, DAY, DELIVERED, null, null))
                    .isEqualByComparingTo("1000.00");
            assertThat(invoices.sumTotalByDateRangeWithJobFilters(DAY, DAY, null, sur.getId(), null))
                    .isEqualByComparingTo("800.00");
            assertThat(invoices.countByDateRangeWithJobFilters(DAY, DAY, null, anil.getId(), null))
                    .isEqualTo(1L);
            assertThat(invoices.findByDateRangeWithJobFilters(DAY, DAY, DELIVERED, null, null))
                    .hasSize(1);
        }

        @Test
        @DisplayName("RQ36 the daily workshop revenue is keyed off the job's assigned date")
        void dailyWorkshopRevenueUsesAssignedDate() {
            Customer c = givenCustomer("Ravi");
            Vehicle v = givenVehicle(c);
            Mechanic m = givenMechanic("Anil", "MECH-1");
            JobCard onDay = givenJobCard(c, v, m, DELIVERED, "SERVICE", from().plusHours(9));
            JobCard other = givenJobCard(c, v, m, DELIVERED, "SERVICE", from().minusDays(3));
            givenInvoice(onDay, "PAID", DAY.plusDays(1), "1000.00");
            givenInvoice(other, "PAID", DAY.plusDays(1), "500.00");
            em.flush();

            assertThat(invoices.sumTotalByJobAssignedDateBetween(from(), to()))
                    .isEqualByComparingTo("1000.00");
        }
    }

    // ── Customer growth and repeat customers (FR-REP-5) ───────────────────

    @Nested
    @DisplayName("Customer growth")
    class CustomerGrowth {

        @Test
        @DisplayName("RQ37 new customers are counted by their registration date")
        void newCustomersCountedInWindow() {
            // created_at is stamped at persist time, so these land today.
            givenCustomer("Ravi");
            givenCustomer("Neha");
            em.flush();

            assertThat(customers.countByCreatedAtGreaterThanEqualAndCreatedAtLessThan(
                    todayFrom(), todayTo())).isEqualTo(2L);
            // A window that closed before today cannot see them.
            assertThat(customers.countByCreatedAtGreaterThanEqualAndCreatedAtLessThan(
                    todayFrom().minusYears(1), todayFrom())).isZero();
        }

        @Test
        @DisplayName("RQ38 customers on record are counted up to the end of the window")
        void totalCustomersAtEndOfPeriod() {
            givenCustomer("Ravi");
            givenCustomer("Neha");
            em.flush();

            assertThat(customers.countByCreatedAtLessThan(todayTo())).isEqualTo(2L);
            assertThat(customers.countByCreatedAtLessThan(todayFrom())).isZero();
        }

        @Test
        @DisplayName("RQ39 the new-customer time series sums to the headline count")
        void newCustomerSeriesSumsToHeadline() {
            givenCustomer("Ravi");
            givenCustomer("Neha");
            givenCustomer("Amit");
            em.flush();

            List<DateCountProjection> series =
                    customers.countNewCustomersGroupedByCreatedAt(todayFrom(), todayTo());

            long summed = series.stream().mapToLong(DateCountProjection::getRowCount).sum();
            assertThat(summed).isEqualTo(
                    customers.countByCreatedAtGreaterThanEqualAndCreatedAtLessThan(
                            todayFrom(), todayTo()));
            assertThat(series).allSatisfy(r -> assertThat(r.getCreatedAt()).isNotNull());
        }

        @Test
        @DisplayName("RQ40 served customers are counted distinctly, not per job")
        void servedCustomersAreDistinct() {
            Customer ravi = givenCustomer("Ravi");
            Vehicle car = givenVehicle(ravi);
            Mechanic m = givenMechanic("Anil", "MECH-1");
            // One customer, three jobs.
            givenJobCard(ravi, car, m, DELIVERED, "SERVICE", from().plusHours(9));
            givenJobCard(ravi, car, m, DELIVERED, "SERVICE", from().plusHours(10));
            givenJobCard(ravi, car, m, DELIVERED, "SERVICE", from().plusHours(11));
            em.flush();

            assertThat(jobCards.countDistinctCustomersWithJobs(from(), to())).isEqualTo(1L);
            assertThat(jobCards.countByAssignedDateRange(from(), to())).isEqualTo(3L);
        }

        @Test
        @DisplayName("RQ41 a job with no customer does not inflate the served count")
        void jobsWithoutACustomerAreIgnored() {
            Mechanic m = givenMechanic("Anil", "MECH-1");
            givenJobCard(null, null, m, DELIVERED, "SERVICE", from().plusHours(9));
            em.flush();

            assertThat(jobCards.countDistinctCustomersWithJobs(from(), to())).isZero();
            // The job itself is still counted, because it is a real job.
            assertThat(jobCards.countByAssignedDateRange(from(), to())).isEqualTo(1L);
        }

        /**
         * Repeat customers require BOTH a customer that existed before the window
         * AND a job inside it.
         *
         * <p>{@code created_at} is stamped at persist time and cannot be
         * back-dated, so today's window is the one in which a customer's
         * {@code created_at} genuinely falls inside it — which is exactly what
         * makes the two halves of the filter separable here.
         */
        @Test
        @DisplayName("RQ42 an in-window customer counts as new, not as repeat")
        void inWindowCustomerIsNewNotRepeat() {
            Customer ravi = givenCustomer("Ravi");
            Vehicle raviCar = givenVehicle(ravi);
            Mechanic m = givenMechanic("Anil", "MECH-1");
            givenJobCard(ravi, raviCar, m, DELIVERED, "SERVICE", from().plusHours(9));
            // Signed up today but never came back.
            givenCustomer("Neha");
            em.flush();

            // Both customers are inside the window, so both are new business…
            assertThat(customers.countByCreatedAtGreaterThanEqualAndCreatedAtLessThan(
                    todayFrom(), todayTo())).isEqualTo(2L);
            // …and neither existed before it, so neither is repeat business.
            assertThat(jobCards.countRepeatCustomersInPeriod(todayFrom(), todayTo())).isZero();
        }

        /**
         * The counterpart: with the window moved into the future, a customer
         * created today genuinely pre-exists it, and a job dated inside that
         * window makes them repeat business.
         *
         * <p>Without this the repeat query is only ever observed returning zero,
         * which would equally be true if it were simply broken.
         */
        @Test
        @DisplayName("RQ42b a customer created before the window and returning in it is repeat")
        void customerReturningInAFutureWindowCountsAsRepeat() {
            Customer ravi = givenCustomer("Ravi");
            Vehicle raviCar = givenVehicle(ravi);
            Mechanic m = givenMechanic("Anil", "MECH-1");

            LocalDateTime futureFrom = todayTo().plusDays(1);
            givenJobCard(ravi, raviCar, m, DELIVERED, "SERVICE", futureFrom.plusHours(9));
            em.flush();

            // created_at is today, which is before tomorrow's window opens.
            assertThat(jobCards.countRepeatCustomersInPeriod(
                    futureFrom, futureFrom.plusDays(1))).isEqualTo(1L);

            // A window with no job in it still returns nothing, so the count above
            // is driven by the job and not by the customer merely existing.
            assertThat(jobCards.countRepeatCustomersInPeriod(
                    futureFrom.plusDays(10), futureFrom.plusDays(11))).isZero();
        }

        @Test
        @DisplayName("RQ43 a returning customer with no job in the window is not repeat")
        void repeatCustomersNeedAJobInTheWindow() {
            Customer ravi = givenCustomer("Ravi");
            Vehicle car = givenVehicle(ravi);
            Mechanic m = givenMechanic("Anil", "MECH-1");
            // Job is OUTSIDE the window.
            givenJobCard(ravi, car, m, DELIVERED, "SERVICE", from().minusDays(5));
            em.flush();

            assertThat(jobCards.countDistinctCustomersWithJobs(from(), to())).isZero();
            assertThat(jobCards.countRepeatCustomersInPeriod(from(), to())).isZero();
        }

        @Test
        @DisplayName("RQ44 no customers and no jobs yields zeroes, never null")
        void emptyGrowthFigures() {
            em.flush();

            assertThat(customers.countByCreatedAtGreaterThanEqualAndCreatedAtLessThan(
                    from(), to())).isZero();
            assertThat(customers.countNewCustomersGroupedByCreatedAt(from(), to())).isEmpty();
            assertThat(jobCards.countDistinctCustomersWithJobs(from(), to())).isZero();
            assertThat(jobCards.countRepeatCustomersInPeriod(from(), to())).isZero();
        }
    }
}
