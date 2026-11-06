package com.autoservicehub.controller;

import com.autoservicehub.repository.PackageItemRepository;
import com.jayway.jsonpath.JsonPath;
import com.autoservicehub.repository.ServicePackageRepository;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.annotation.Transactional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Full-stack test for the service package endpoints: the real application
 * context, the real Spring Security filter chain, the real controllers, the
 * real JPA mapping and a real (embedded) database, exercised through the HTTP
 * layer.
 *
 * <p>This sits above {@link ServicePackageControllerTest}, which stubs the JWT
 * filter and the service, and {@code ServicePackagePersistenceTest}, which skips
 * HTTP entirely. What only this test can catch is a mismatch between the two —
 * for example a response shape the page would misread, or a field that persists
 * but is not serialised back out.
 *
 * <p>Authorization is real: {@code @WithMockUser} supplies the authenticated
 * principal the way a validated JWT would, and the actual {@code @PreAuthorize}
 * rules from {@code ServicePackageController} decide whether the request is
 * allowed. Nothing is bypassed.
 *
 * <p>Runs against its own embedded database rather than the shared one in
 * application-test.yml, whose {@code create-drop} would drop the schema out from
 * under another context.
 */
@SpringBootTest(properties = {
        "spring.datasource.url=jdbc:h2:mem:service-package-e2e;DB_CLOSE_DELAY=-1;DB_CLOSE_ON_EXIT=FALSE;NON_KEYWORDS=YEAR",
        "spring.datasource.driver-class-name=org.h2.Driver",
        "spring.datasource.username=sa",
        "spring.datasource.password=",
        "spring.jpa.hibernate.ddl-auto=create-drop",
        "spring.jpa.properties.hibernate.dialect=org.hibernate.dialect.H2Dialect"
})
@AutoConfigureMockMvc
@Transactional
class ServicePackageEndToEndTest {

    private static final String PACKAGES = "/api/v1/service-packages";

    @Autowired MockMvc                    mockMvc;
    @Autowired ServicePackageRepository  packageRepository;
    @Autowired PackageItemRepository     itemRepository;

    private static final String FULL_PACKAGE = """
            {
              "name": "Premium Service",
              "type": "PREMIUM",
              "price": 10999.00,
              "duration": "6–8 hrs",
              "gstRate": 18.00,
              "discountPercentage": 5.00,
              "color": "purple",
              "popular": true,
              "items": [
                { "itemName": "Spark plug replacement", "itemType": "SERVICE" },
                { "itemName": "Coolant 1L",              "itemType": "PART" }
              ]
            }
            """;

    @Test
    @WithMockUser(roles = "SERVICE_ADVISOR")
    @DisplayName("E2E1 — create then read back a package through the real stack")
    void e2e1_createThenRead() throws Exception {
        String body = mockMvc.perform(post(PACKAGES)
                        .contentType(MediaType.APPLICATION_JSON).content(FULL_PACKAGE))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.data.name").value("Premium Service"))
                .andExpect(jsonPath("$.data.duration").value("6–8 hrs"))
                .andExpect(jsonPath("$.data.color").value("purple"))
                .andExpect(jsonPath("$.data.popular").value(true))
                .andExpect(jsonPath("$.data.active").value(true))
                .andExpect(jsonPath("$.data.items.length()").value(2))
                .andReturn().getResponse().getContentAsString();

        long id = ((Number) JsonPath.read(body, "$.data.id")).longValue();

        // The row is really in the database, with its items.
        assertThat(packageRepository.findById(id)).isPresent();
        assertThat(itemRepository.findAll()).hasSize(2);

        // And it reads back over HTTP with every field intact.
        mockMvc.perform(get(PACKAGES + "/" + id))
               .andExpect(status().isOk())
               .andExpect(jsonPath("$.data.duration").value("6–8 hrs"))
               .andExpect(jsonPath("$.data.gstRate").value(18.00))
               .andExpect(jsonPath("$.data.discountPercentage").value(5.00))
               .andExpect(jsonPath("$.data.items.length()").value(2))
               .andExpect(jsonPath("$.data.items[1].itemType").value("PART"));

        // It also appears in the paged list the page loads from.
        mockMvc.perform(get(PACKAGES).param("page", "0").param("size", "20"))
               .andExpect(status().isOk())
               .andExpect(jsonPath("$.data.content[?(@.id==" + id + ")].name")
                       .value("Premium Service"));
    }

    @Test
    @WithMockUser(roles = "SERVICE_ADVISOR")
    @DisplayName("E2E2 — update replaces the item list and the dropped line is gone")
    void e2e2_updateReplacesItems() throws Exception {
        long id = createPackage();

        mockMvc.perform(put(PACKAGES + "/" + id)
                        .contentType(MediaType.APPLICATION_JSON).content("""
                                {
                                  "name": "Premium Service",
                                  "type": "PREMIUM",
                                  "price": 9999.00,
                                  "items": [
                                    { "itemName": "Only this now", "itemType": "SERVICE" }
                                  ]
                                }
                                """))
               .andExpect(status().isOk())
               .andExpect(jsonPath("$.data.items.length()").value(1))
               .andExpect(jsonPath("$.data.items[0].itemName").value("Only this now"));

        assertThat(itemRepository.findAll()).hasSize(1);
    }

    @Test
    @WithMockUser(roles = "SERVICE_ADVISOR")
    @DisplayName("E2E3 — deactivating via PUT survives a re-read")
    void e2e3_deactivate() throws Exception {
        long id = createPackage();

        mockMvc.perform(put(PACKAGES + "/" + id)
                        .contentType(MediaType.APPLICATION_JSON).content("""
                                {
                                  "name": "Premium Service", "type": "PREMIUM",
                                  "price": 10999.00, "active": false,
                                  "items": [
                                    { "itemName": "Spark plug replacement", "itemType": "SERVICE" },
                                    { "itemName": "Coolant 1L",              "itemType": "PART" }
                                  ]
                                }
                                """))
               .andExpect(status().isOk())
               .andExpect(jsonPath("$.data.active").value(false));

        mockMvc.perform(get(PACKAGES + "/" + id))
               .andExpect(jsonPath("$.data.active").value(false));
    }

    @Test
    @WithMockUser(roles = "SERVICE_ADVISOR")
    @DisplayName("E2E4 — delete removes the package and its items")
    void e2e4_delete() throws Exception {
        long id = createPackage();

        mockMvc.perform(delete(PACKAGES + "/" + id))
               .andExpect(status().isNoContent());

        mockMvc.perform(get(PACKAGES + "/" + id))
               .andExpect(status().isNotFound());

        assertThat(packageRepository.findById(id)).isEmpty();
        assertThat(itemRepository.findAll()).isEmpty();
    }

    @Test
    @WithMockUser(roles = "MECHANIC")
    @DisplayName("E2E5 — the real @PreAuthorize rules still deny a MECHANIC a write")
    void e2e5_mechanicForbidden() throws Exception {
        // Proves authorization is enforced by the production security config,
        // not only by the stubbed one in ServicePackageControllerTest.
        mockMvc.perform(post(PACKAGES)
                        .contentType(MediaType.APPLICATION_JSON).content(FULL_PACKAGE))
               .andExpect(status().isForbidden());
    }

    /** Creates one package with two items and returns its id. */
    private long createPackage() throws Exception {
        String body = mockMvc.perform(post(PACKAGES)
                        .contentType(MediaType.APPLICATION_JSON).content(FULL_PACKAGE))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        return ((Number) JsonPath.read(body, "$.data.id")).longValue();
    }
}