package com.autoservicehub.controller;

import com.autoservicehub.entity.Customer;
import com.autoservicehub.entity.ServicePackage;
import com.autoservicehub.entity.Vehicle;
import com.autoservicehub.repository.CustomerRepository;
import com.autoservicehub.repository.ServicePackageRepository;
import com.autoservicehub.repository.VehicleRepository;
import com.jayway.jsonpath.JsonPath;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Full-stack test for applying a service package: the real application context,
 * the real Spring Security filter chain, the real controller, the real service,
 * the real JPA mapping and a real (embedded) database, exercised over HTTP.
 *
 * <p>Two things here cannot be checked by a mocked or repository-level test.
 * First, authorization: {@code @WithMockUser} supplies the principal a validated
 * JWT would, and the actual {@code @PreAuthorize} rules decide. Second,
 * persistence: the unique constraint and the snapshot copy are both database
 * behaviour, so every assertion here reads the row back.
 *
 * <p>Runs against its own embedded database rather than the shared one in
 * application-test.yml, whose {@code create-drop} would drop the schema out from
 * under another context.
 */
@SpringBootTest(properties = {
        "spring.datasource.url=jdbc:h2:mem:package-application-e2e;DB_CLOSE_DELAY=-1;DB_CLOSE_ON_EXIT=FALSE;NON_KEYWORDS=YEAR",
        "spring.datasource.driver-class-name=org.h2.Driver",
        "spring.datasource.username=sa",
        "spring.datasource.password=",
        "spring.jpa.hibernate.ddl-auto=create-drop",
        "spring.jpa.properties.hibernate.dialect=org.hibernate.dialect.H2Dialect"
})
@AutoConfigureMockMvc
@Transactional
class PackageApplicationEndToEndTest {

    private static final String PACKAGES = "/api/v1/service-packages";
    private static final String APPLICATIONS = "/api/v1/package-applications";
    private static final String CUSTOMERS = "/api/v1/customers";
    private static final String VEHICLES = "/api/v1/vehicles";

    @Autowired MockMvc                    mockMvc;
    @Autowired ServicePackageRepository  servicePackageRepository;
    @Autowired CustomerRepository        customerRepository;
    @Autowired VehicleRepository         vehicleRepository;

    /* ───────────────────────── arrange helpers ───────────────────────── */

    /** Creates an active package and returns its id. */
    private long createActivePackage() throws Exception {
        return createPackage(true, "10999.00");
    }

    /** Creates a package with the given active flag and price, returns its id. */
    private long createPackage(boolean active, String price) throws Exception {
        String body = mockMvc.perform(post(PACKAGES)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "name": "Premium Service",
                                  "type": "PREMIUM",
                                  "price": %s,
                                  "duration": "6–8 hrs",
                                  "gstRate": 18.00,
                                  "discountPercentage": 5.00,
                                  "color": "purple",
                                  "active": %s,
                                  "items": [
                                    { "itemName": "Spark plug replacement", "itemType": "SERVICE" },
                                    { "itemName": "Coolant 1L",              "itemType": "PART" }
                                  ]
                                }
                                """.formatted(price, active)))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        return ((Number) JsonPath.read(body, "$.data.id")).longValue();
    }

    private long createCustomer(String firstName, String phone) throws Exception {
        String body = mockMvc.perform(post(CUSTOMERS)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                { "name": "%s Mehta", "phone": "%s" }
                                """.formatted(firstName, phone)))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        return ((Number) JsonPath.read(body, "$.data.id")).longValue();
    }

    /** Registers a vehicle for a customer over HTTP and returns its new id. */
    private long createVehicle(String registrationNo, String make, Long customerId) throws Exception {
        String body = mockMvc.perform(post(VEHICLES)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "registrationNo": "%s",
                                  "make": "%s",
                                  "model": "City",
                                  "customerId": %d
                                }
                                """.formatted(registrationNo, make, customerId)))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        return ((Number) JsonPath.read(body, "$.data.id")).longValue();
    }

    private static String applyBody(Long packageId, Long customerId, Long vehicleId) {
        return """
                { "servicePackageId": %d, "customerId": %d, "vehicleId": %d }
                """.formatted(packageId, customerId, vehicleId);
    }

    /* ───────────────────────── the happy path ───────────────────────── */

    @Test
    @WithMockUser(roles = "SERVICE_ADVISOR")
    @DisplayName("APPLY1 — applying returns the stored snapshot and the row really persists")
    void apply1_persistsSnapshot() throws Exception {
        long packageId = createActivePackage();
        long customerId = createCustomer("Arjun", "9876543210");
        long vehicle = createVehicle("MH12AB1234", "Honda", customerId);

        String body = mockMvc.perform(post(APPLICATIONS)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(applyBody(packageId, customerId, vehicle)))
                .andExpect(status().isCreated())
                // The snapshot is copied from the package server-side, never echoed
                // back from the request, so all terms come through.
                .andExpect(jsonPath("$.data.packageName").value("Premium Service"))
                .andExpect(jsonPath("$.data.packageType").value("PREMIUM"))
                .andExpect(jsonPath("$.data.packagePrice").value(10999.00))
                .andExpect(jsonPath("$.data.packageGstRate").value(18.00))
                .andExpect(jsonPath("$.data.packageDiscountPercentage").value(5.00))
                .andExpect(jsonPath("$.data.packageDuration").value("6–8 hrs"))
                // Owner and vehicle read live through their relationships.
                .andExpect(jsonPath("$.data.customerId").value(customerId))
                .andExpect(jsonPath("$.data.customerName").value("Arjun Mehta"))
                .andExpect(jsonPath("$.data.vehicleId").value(vehicle))
                .andExpect(jsonPath("$.data.vehicleRegistrationNo").value("MH12AB1234"))
                .andExpect(jsonPath("$.data.servicePackageId").value(packageId))
                .andExpect(jsonPath("$.data.appliedAt").isNotEmpty())
                .andReturn().getResponse().getContentAsString();

        long applicationId = ((Number) JsonPath.read(body, "$.data.id")).longValue();

        // It reads back over HTTP from a fresh request, not just from the create
        // response, so persistence is proven rather than assumed.
        mockMvc.perform(get(APPLICATIONS + "/" + applicationId))
               .andExpect(status().isOk())
               .andExpect(jsonPath("$.data.packagePrice").value(10999.00))
               .andExpect(jsonPath("$.data.vehicleRegistrationNo").value("MH12AB1234"));

        // And it appears in both list endpoints the page reads from.
        mockMvc.perform(get(APPLICATIONS).param("page", "0").param("size", "20"))
               .andExpect(status().isOk())
               .andExpect(jsonPath("$.data.content.length()").value(1));

        mockMvc.perform(get(APPLICATIONS + "/package/" + packageId).param("page", "0").param("size", "20"))
               .andExpect(status().isOk())
               .andExpect(jsonPath("$.data.content.length()").value(1))
               .andExpect(jsonPath("$.data.content[0].packageName").value("Premium Service"));
    }

    /**
     * The reason the snapshot exists: repricing the package must not restate what
     * an already-applied customer agreed to.
     */
    @Test
    @WithMockUser(roles = "SERVICE_ADVISOR")
    @DisplayName("APPLY2 — repricing the package does not change an existing application")
    void apply2_snapshotSurvivesReprice() throws Exception {
        long packageId = createActivePackage();
        long customerId = createCustomer("Priya", "9876543120");
        long vehicle = createVehicle("DL01CD2345", "Maruti", customerId);

        String body = mockMvc.perform(post(APPLICATIONS)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(applyBody(packageId, customerId, vehicle)))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        long applicationId = ((Number) JsonPath.read(body, "$.data.id")).longValue();

        // The workshop raises the catalogue price.
        mockMvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders
                        .put(PACKAGES + "/" + packageId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "name": "Premium Service", "type": "PREMIUM",
                                  "price": 14999.00, "gstRate": 18.00,
                                  "items": [
                                    { "itemName": "Spark plug replacement", "itemType": "SERVICE" }
                                  ]
                                }
                                """))
               .andExpect(status().isOk())
               .andExpect(jsonPath("$.data.price").value(14999.00));

        // The application still shows what the customer agreed to.
        mockMvc.perform(get(APPLICATIONS + "/" + applicationId))
               .andExpect(jsonPath("$.data.packagePrice").value(10999.00));
    }

    /* ───────────────────────── business rules ───────────────────────── */

    @Test
    @WithMockUser(roles = "SERVICE_ADVISOR")
    @DisplayName("APPLY3 — an inactive package is refused for a new application")
    void apply3_inactiveRefused() throws Exception {
        long packageId = createPackage(false, "10999.00");
        long customerId = createCustomer("Rohit", "9876543130");
        long vehicle = createVehicle("GJ02EF3456", "Tata", customerId);

        mockMvc.perform(post(APPLICATIONS)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(applyBody(packageId, customerId, vehicle)))
               .andExpect(status().isConflict())
               .andExpect(jsonPath("$.code").value("BUSINESS_RULE_VIOLATION"))
               .andExpect(jsonPath("$.message").value(
                       org.hamcrest.Matchers.containsString("inactive")));

        // Nothing was written.
        mockMvc.perform(get(APPLICATIONS).param("page", "0").param("size", "20"))
               .andExpect(jsonPath("$.data.content.length()").value(0));

        // ...and it becomes applicable once activated.
        mockMvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders
                        .put(PACKAGES + "/" + packageId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                { "name": "Premium Service", "type": "PREMIUM",
                                  "price": 10999.00, "active": true }
                                """))
               .andExpect(status().isOk());

        mockMvc.perform(post(APPLICATIONS)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(applyBody(packageId, customerId, vehicle)))
               .andExpect(status().isCreated());
    }

    @Test
    @WithMockUser(roles = "SERVICE_ADVISOR")
    @DisplayName("APPLY4 — applying the same package to the same vehicle twice is refused")
    void apply4_duplicateRefused() throws Exception {
        long packageId = createActivePackage();
        long customerId = createCustomer("Neha", "9876543140");
        long vehicle = createVehicle("UP03GH4567", "Hyundai", customerId);

        mockMvc.perform(post(APPLICATIONS)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(applyBody(packageId, customerId, vehicle)))
               .andExpect(status().isCreated());

        mockMvc.perform(post(APPLICATIONS)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(applyBody(packageId, customerId, vehicle)))
               .andExpect(status().isConflict())
               .andExpect(jsonPath("$.code").value("BUSINESS_RULE_VIOLATION"))
               .andExpect(jsonPath("$.message").value(
                       org.hamcrest.Matchers.containsString("already been applied")));

        assertThat(countApplications()).isEqualTo(1);
    }

    /** The same package may go to a customer's second vehicle — the rule is per vehicle. */
    @Test
    @WithMockUser(roles = "SERVICE_ADVISOR")
    @DisplayName("APPLY5 — the same package may be applied to a customer's other vehicle")
    void apply5_samePackageDifferentVehicleAllowed() throws Exception {
        long packageId = createActivePackage();
        long customerId = createCustomer("Vikram", "9876543150");
        long first = createVehicle("MH04IJ5678", "Ford", customerId);
        long second = createVehicle("MH05IJ6789", "Ford", customerId);

        mockMvc.perform(post(APPLICATIONS)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(applyBody(packageId, customerId, first)))
               .andExpect(status().isCreated());

        mockMvc.perform(post(APPLICATIONS)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(applyBody(packageId, customerId, second)))
               .andExpect(status().isCreated());

        assertThat(countApplications()).isEqualTo(2);
    }

    @Test
    @WithMockUser(roles = "SERVICE_ADVISOR")
    @DisplayName("APPLY6 — a vehicle owned by somebody else is refused")
    void apply6_wrongOwnerRefused() throws Exception {
        long packageId = createActivePackage();
        long ownerId = createCustomer("Kavita", "9876543160");
        long otherId = createCustomer("Sameer", "9876543170");
        long vehicle = createVehicle("KA06KL7890", "Toyota", ownerId);

        mockMvc.perform(post(APPLICATIONS)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(applyBody(packageId, otherId, vehicle)))
               .andExpect(status().isConflict())
               .andExpect(jsonPath("$.message").value(
                       org.hamcrest.Matchers.containsString("does not belong")));

        assertThat(countApplications()).isZero();
    }

    /* ───────────────────────── validation & 404s ───────────────────────── */

    @Test
    @WithMockUser(roles = "SERVICE_ADVISOR")
    @DisplayName("APPLY7 — a body missing required ids is a 400")
    void apply7_missingIds400() throws Exception {
        mockMvc.perform(post(APPLICATIONS)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                { "servicePackageId": 1 }
                                """))
               .andExpect(status().isBadRequest())
               .andExpect(jsonPath("$.code").value("VALIDATION_ERROR"));
    }

    @Test
    @WithMockUser(roles = "SERVICE_ADVISOR")
    @DisplayName("APPLY8 — unknown package, customer or vehicle is a 404")
    void apply8_unknownIds404() throws Exception {
        long customerId = createCustomer("Neel", "9876543180");
        long vehicle = createVehicle("TN07LM8901", "Ford", customerId);

        mockMvc.perform(post(APPLICATIONS)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(applyBody(999999L, customerId, vehicle)))
               .andExpect(status().isNotFound())
               .andExpect(jsonPath("$.code").value("NOT_FOUND"));

        long packageId = createActivePackage();

        mockMvc.perform(post(APPLICATIONS)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(applyBody(packageId, 999999L, vehicle)))
               .andExpect(status().isNotFound());

        mockMvc.perform(post(APPLICATIONS)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(applyBody(packageId, customerId, 999999L)))
               .andExpect(status().isNotFound());
    }

    /* ───────────────────────── authorization ───────────────────────── */

    @Test
    @WithMockUser(roles = "MECHANIC")
    @DisplayName("APPLY9 — a MECHANIC may read applications but not create one")
    void apply9_mechanicCannotApply() throws Exception {
        // Arranged through the repositories rather than over HTTP: the package and
        // customer endpoints also refuse a MECHANIC, so creating the fixtures that
        // way would fail on the wrong assertion before the one under test ran.
        ServicePackage servicePackage = new ServicePackage();
        servicePackage.setName("Premium Service");
        servicePackage.setType("PREMIUM");
        servicePackage.setPrice(new BigDecimal("10999.00"));
        servicePackage.setActive(true);
        long packageId = servicePackageRepository.save(servicePackage).getId();

        Customer customer = new Customer();
        customer.setName("Ravi Kumar");
        customer.setPhone("9876543190");
        long customerId = customerRepository.save(customer).getId();

        Vehicle vehicle = new Vehicle();
        vehicle.setRegistrationNo("KL08MN9012");
        vehicle.setMake("Kia");
        vehicle.setCustomer(customer);
        long vehicleId = vehicleRepository.save(vehicle).getId();

        mockMvc.perform(post(APPLICATIONS)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(applyBody(packageId, customerId, vehicleId)))
               .andExpect(status().isForbidden());

        // The read side is deliberately open to them.
        mockMvc.perform(get(APPLICATIONS).param("page", "0").param("size", "20"))
               .andExpect(status().isOk());

        // And the denial wrote nothing.
        assertThat(countApplications()).isZero();
    }

    @Test
    @WithMockUser(roles = "SERVICE_ADVISOR")
    @DisplayName("APPLY10 — filtering by an unknown package is a 404, not an empty page")
    void apply10_unknownPackageFilter404() throws Exception {
        mockMvc.perform(get(APPLICATIONS + "/package/999999").param("page", "0").param("size", "20"))
               .andExpect(status().isNotFound());
    }

    private int countApplications() throws Exception {
        String body = mockMvc.perform(get(APPLICATIONS).param("page", "0").param("size", "50"))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        return ((Number) JsonPath.read(body, "$.data.totalElements")).intValue();
    }
}
