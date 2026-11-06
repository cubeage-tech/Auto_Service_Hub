package com.autoservicehub.controller;

import com.autoservicehub.dto.PackageItemResponseDTO;
import com.autoservicehub.dto.ServicePackageResponseDTO;
import com.autoservicehub.entity.PackageItemType;
import com.autoservicehub.exception.BusinessRuleException;
import com.autoservicehub.exception.GlobalExceptionHandler;
import com.autoservicehub.exception.ResourceNotFoundException;
import com.autoservicehub.security.CustomUserDetailsService;
import com.autoservicehub.security.JwtAuthenticationFilter;
import com.autoservicehub.security.JwtTokenProvider;
import com.autoservicehub.service.ServicePackageService;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.test.context.support.WithAnonymousUser;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.test.web.servlet.MockMvc;

import java.io.IOException;
import java.math.BigDecimal;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Controller slice tests for {@link ServicePackageController}, covering the
 * fields added in V8: duration, GST rate, discount, colour, popular badge and
 * the bundled items.
 *
 * <p>Uses {@code @WebMvcTest} with the project's minimal inner security
 * configuration (method security on, JWT filter not registered), matching
 * {@code PurchasingControllerTest} and {@code PartControllerTest}.
 *
 * Test cases
 * ----------
 * PC1  POST full package       -> 201 with every field echoed back
 * PC2  POST                    -> the DTO reaches the service as sent
 * PC3  POST minimal package    -> 201 (older client, no new fields)
 * PC4  POST blank name         -> 400 VALIDATION_ERROR
 * PC5  POST no price           -> 400 VALIDATION_ERROR
 * PC6  POST discount 150       -> 400 VALIDATION_ERROR
 * PC7  POST unknown item type  -> 400 BAD_REQUEST
 * PC8  POST unsupported colour -> 409 BUSINESS_RULE_VIOLATION
 * PC9  POST unknown supplier-style 404 path: unknown id on PUT -> 404
 * PC10 GET by id               -> 200 with items
 * PC11 GET list                -> 200 paged
 * PC12 PUT                     -> 200
 * PC13 DELETE                  -> 204
 * PC14 POST as MECHANIC        -> 403
 * PC15 Anonymous               -> 401
 */
@WebMvcTest(controllers = ServicePackageController.class)
@Import({ServicePackageControllerTest.TestSecurityConfig.class, GlobalExceptionHandler.class})
class ServicePackageControllerTest {

    @EnableMethodSecurity
    static class TestSecurityConfig {
        @Bean
        SecurityFilterChain testFilterChain(HttpSecurity http) throws Exception {
            http
                .csrf(csrf -> csrf.disable())
                .sessionManagement(sm -> sm.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .exceptionHandling(ex -> ex
                    .authenticationEntryPoint((req, res, e) ->
                        res.sendError(jakarta.servlet.http.HttpServletResponse.SC_UNAUTHORIZED, "Unauthorized"))
                    .accessDeniedHandler((req, res, e) ->
                        res.sendError(jakarta.servlet.http.HttpServletResponse.SC_FORBIDDEN, "Forbidden")))
                .authorizeHttpRequests(auth -> auth.anyRequest().authenticated());
            return http.build();
        }
    }

    private static final String PACKAGES = "/api/v1/service-packages";

    @Autowired MockMvc    mockMvc;
    @Autowired ObjectMapper mapper;

    @MockBean ServicePackageService      servicePackageService;
    @MockBean JwtAuthenticationFilter    jwtAuthenticationFilter;
    @MockBean JwtTokenProvider           jwtTokenProvider;
    @MockBean CustomUserDetailsService   customUserDetailsService;

    @BeforeEach
    void configureFilterPassthrough() throws ServletException, IOException {
        org.mockito.Mockito.doAnswer(invocation -> {
            HttpServletRequest  req   = invocation.getArgument(0);
            HttpServletResponse res   = invocation.getArgument(1);
            FilterChain         chain = invocation.getArgument(2);
            chain.doFilter(req, res);
            return null;
        }).when(jwtAuthenticationFilter).doFilter(
            any(HttpServletRequest.class),
            any(HttpServletResponse.class),
            any(FilterChain.class));
    }

    // ── Helpers ──────────────────────────────────────────────────────────

    private ServicePackageResponseDTO packageResponse() {
        ServicePackageResponseDTO dto = new ServicePackageResponseDTO();
        dto.setId(1L);
        dto.setName("Premium Service");
        dto.setType("PREMIUM");
        dto.setPrice(new BigDecimal("10999.00"));
        dto.setActive(true);
        dto.setDuration("6–8 hrs");
        dto.setGstRate(new BigDecimal("18.00"));
        dto.setDiscountPercentage(new BigDecimal("5.00"));
        dto.setColor("purple");
        dto.setPopular(true);
        dto.setItems(List.of(
                new PackageItemResponseDTO(1L, "Spark plug replacement", PackageItemType.SERVICE),
                new PackageItemResponseDTO(2L, "Coolant 1L", PackageItemType.PART)));
        return dto;
    }

    /** Every field the Create Package form collects. */
    private String fullPackageJson() {
        return """
                {
                  "name": "Premium Service",
                  "type": "PREMIUM",
                  "price": 10999.00,
                  "duration": "6–8 hrs",
                  "gstRate": 18.00,
                  "discountPercentage": 5.00,
                  "color": "purple",
                  "popular": true,
                  "active": true,
                  "items": [
                    { "itemName": "Spark plug replacement", "itemType": "SERVICE" },
                    { "itemName": "Coolant 1L",              "itemType": "PART" }
                  ]
                }
                """;
    }

    /** The shape an older client sends — only the original four fields. */
    private String minimalPackageJson() {
        return """
                { "name": "Basic Service", "type": "BASIC", "price": 2499.00 }
                """;
    }

    // ── PC1..PC3: create ────────────────────────────────────────────────

    @Test
    @WithMockUser(roles = "SERVICE_ADVISOR")
    @DisplayName("PC1 — POST creates a package and echoes every field back")
    void pc1_create_returnsAllFields() throws Exception {
        when(servicePackageService.create(any())).thenReturn(packageResponse());

        mockMvc.perform(post(PACKAGES).contentType(MediaType.APPLICATION_JSON).content(fullPackageJson()))
               .andExpect(status().isCreated())
               .andExpect(jsonPath("$.success").value(true))
               .andExpect(jsonPath("$.data.name").value("Premium Service"))
               .andExpect(jsonPath("$.data.duration").value("6–8 hrs"))
               .andExpect(jsonPath("$.data.gstRate").value(18.00))
               .andExpect(jsonPath("$.data.discountPercentage").value(5.00))
               .andExpect(jsonPath("$.data.color").value("purple"))
               .andExpect(jsonPath("$.data.popular").value(true))
               .andExpect(jsonPath("$.data.items.length()").value(2))
               .andExpect(jsonPath("$.data.items[0].itemName").value("Spark plug replacement"))
               .andExpect(jsonPath("$.data.items[0].itemType").value("SERVICE"))
               .andExpect(jsonPath("$.data.items[1].itemType").value("PART"));
    }

    @Test
    @WithMockUser(roles = "SERVICE_ADVISOR")
    @DisplayName("PC2 — POST hands the new fields to the service exactly as sent")
    void pc2_create_passesFieldsToService() throws Exception {
        when(servicePackageService.create(any())).thenReturn(packageResponse());

        mockMvc.perform(post(PACKAGES).contentType(MediaType.APPLICATION_JSON).content(fullPackageJson()))
               .andExpect(status().isCreated());

        ArgumentCaptor<com.autoservicehub.dto.ServicePackageRequestDTO> captor =
                ArgumentCaptor.forClass(com.autoservicehub.dto.ServicePackageRequestDTO.class);
        verify(servicePackageService).create(captor.capture());
        var sent = captor.getValue();
        assertThat(sent.getDuration()).isEqualTo("6–8 hrs");
        assertThat(sent.getGstRate()).isEqualByComparingTo("18.00");
        assertThat(sent.getDiscountPercentage()).isEqualByComparingTo("5.00");
        assertThat(sent.getColor()).isEqualTo("purple");
        assertThat(sent.getPopular()).isTrue();
        assertThat(sent.getItems()).hasSize(2);
        assertThat(sent.getItems().get(0).getItemType()).isEqualTo(PackageItemType.SERVICE);
        assertThat(sent.getItems().get(1).getItemType()).isEqualTo(PackageItemType.PART);
    }

    @Test
    @WithMockUser(roles = "SERVICE_ADVISOR")
    @DisplayName("PC3 — the original four-field payload still creates a package")
    void pc3_createWithMinimalBody_stillCreated() throws Exception {
        ServicePackageResponseDTO response = packageResponse();
        response.setDuration(null);
        response.setGstRate(null);
        response.setDiscountPercentage(null);
        response.setColor(null);
        response.setItems(List.of());
        when(servicePackageService.create(any())).thenReturn(response);

        mockMvc.perform(post(PACKAGES).contentType(MediaType.APPLICATION_JSON).content(minimalPackageJson()))
               .andExpect(status().isCreated())
               .andExpect(jsonPath("$.data.name").value("Premium Service"))
               .andExpect(jsonPath("$.data.duration").doesNotExist())
               .andExpect(jsonPath("$.data.items.length()").value(0));
    }

    // ── PC4..PC8: rejection ──────────────────────────────────────────────

    @Test
    @WithMockUser(roles = "SERVICE_ADVISOR")
    @DisplayName("PC4 — a blank name is a 400 VALIDATION_ERROR")
    void pc4_createWithoutName_400() throws Exception {
        mockMvc.perform(post(PACKAGES).contentType(MediaType.APPLICATION_JSON)
                        .content("{ \"type\": \"BASIC\", \"price\": 2499.00 }"))
               .andExpect(status().isBadRequest())
               .andExpect(jsonPath("$.code").value("VALIDATION_ERROR"));
    }

    @Test
    @WithMockUser(roles = "SERVICE_ADVISOR")
    @DisplayName("PC5 — a missing price is a 400 VALIDATION_ERROR")
    void pc5_createWithoutPrice_400() throws Exception {
        mockMvc.perform(post(PACKAGES).contentType(MediaType.APPLICATION_JSON)
                        .content("{ \"name\": \"Basic Service\", \"type\": \"BASIC\" }"))
               .andExpect(status().isBadRequest())
               .andExpect(jsonPath("$.code").value("VALIDATION_ERROR"));
    }

    @Test
    @WithMockUser(roles = "SERVICE_ADVISOR")
    @DisplayName("PC6 — a discount above 100% is a 400 VALIDATION_ERROR")
    void pc6_createWithAbsurdDiscount_400() throws Exception {
        mockMvc.perform(post(PACKAGES).contentType(MediaType.APPLICATION_JSON).content("""
                        {
                          "name": "Basic Service", "type": "BASIC",
                          "price": 2499.00, "discountPercentage": 150
                        }
                        """))
               .andExpect(status().isBadRequest())
               .andExpect(jsonPath("$.code").value("VALIDATION_ERROR"))
               .andExpect(jsonPath("$.message").value(org.hamcrest.Matchers.containsString("discountPercentage")));
    }

    @Test
    @WithMockUser(roles = "SERVICE_ADVISOR")
    @DisplayName("PC7 — an unrecognised item type is a 400, not stored")
    void pc7_createWithUnknownItemType_400() throws Exception {
        mockMvc.perform(post(PACKAGES).contentType(MediaType.APPLICATION_JSON).content("""
                        {
                          "name": "Basic Service", "type": "BASIC", "price": 2499.00,
                          "items": [ { "itemName": "Something", "itemType": "GADGET" } ]
                        }
                        """))
               .andExpect(status().isBadRequest())
               .andExpect(jsonPath("$.code").value("BAD_REQUEST"));
    }

    @Test
    @WithMockUser(roles = "SERVICE_ADVISOR")
    @DisplayName("PC8 — an unsupported colour surfaces the service's 409")
    void pc8_createWithUnsupportedColour_409() throws Exception {
        when(servicePackageService.create(any()))
                .thenThrow(new BusinessRuleException("Unsupported package colour 'chartreuse'."));

        mockMvc.perform(post(PACKAGES).contentType(MediaType.APPLICATION_JSON).content("""
                        {
                          "name": "Basic Service", "type": "BASIC",
                          "price": 2499.00, "color": "chartreuse"
                        }
                        """))
               .andExpect(status().isConflict())
               .andExpect(jsonPath("$.code").value("BUSINESS_RULE_VIOLATION"))
               .andExpect(jsonPath("$.message").value(org.hamcrest.Matchers.containsString("Unsupported package colour")));
    }

    // ── PC9..PC13: read, update, delete ─────────────────────────────────

    @Test
    @WithMockUser(roles = "SERVICE_ADVISOR")
    @DisplayName("PC9 — updating an unknown package surfaces a 404")
    void pc9_updateUnknown_404() throws Exception {
        when(servicePackageService.update(any(Long.class), any()))
                .thenThrow(new ResourceNotFoundException("ServicePackage not found: 99"));

        mockMvc.perform(put(PACKAGES + "/99").contentType(MediaType.APPLICATION_JSON).content(fullPackageJson()))
               .andExpect(status().isNotFound())
               .andExpect(jsonPath("$.code").value("NOT_FOUND"));
    }

    @Test
    @WithMockUser(roles = "SERVICE_ADVISOR")
    @DisplayName("PC10 — GET returns the package with its items")
    void pc10_getById_returnsItems() throws Exception {
        when(servicePackageService.getById(1L)).thenReturn(packageResponse());

        mockMvc.perform(get(PACKAGES + "/1"))
               .andExpect(status().isOk())
               .andExpect(jsonPath("$.data.duration").value("6–8 hrs"))
               .andExpect(jsonPath("$.data.color").value("purple"))
               .andExpect(jsonPath("$.data.items.length()").value(2));
    }

    @Test
    @WithMockUser(roles = "MECHANIC")
    @DisplayName("PC11 — the paged list is readable by a MECHANIC")
    void pc11_list_isPagedAndReadable() throws Exception {
        when(servicePackageService.list(any()))
                .thenReturn(new org.springframework.data.domain.PageImpl<>(List.of(packageResponse())));

        mockMvc.perform(get(PACKAGES).param("page", "0").param("size", "20"))
               .andExpect(status().isOk())
               .andExpect(jsonPath("$.data.content.length()").value(1))
               .andExpect(jsonPath("$.data.content[0].name").value("Premium Service"))
               .andExpect(jsonPath("$.data.totalElements").value(1));
    }

    @Test
    @WithMockUser(roles = "SERVICE_ADVISOR")
    @DisplayName("PC12 — PUT updates the package")
    void pc12_update_returnsUpdated() throws Exception {
        when(servicePackageService.update(any(Long.class), any())).thenReturn(packageResponse());

        mockMvc.perform(put(PACKAGES + "/1").contentType(MediaType.APPLICATION_JSON).content(fullPackageJson()))
               .andExpect(status().isOk())
               .andExpect(jsonPath("$.data.discountPercentage").value(5.00))
               .andExpect(jsonPath("$.data.popular").value(true));
    }

    @Test
    @WithMockUser(roles = "SERVICE_ADVISOR")
    @DisplayName("PC13 — DELETE returns 204")
    void pc13_delete_204() throws Exception {
        mockMvc.perform(delete(PACKAGES + "/1"))
               .andExpect(status().isNoContent());

        verify(servicePackageService).delete(1L);
    }

    // ── PC14..PC15: authorization ────────────────────────────────────────

    @Test
    @WithMockUser(roles = "MECHANIC")
    @DisplayName("PC14 — a MECHANIC cannot create a package")
    void pc14_createAsMechanic_403() throws Exception {
        mockMvc.perform(post(PACKAGES).contentType(MediaType.APPLICATION_JSON).content(fullPackageJson()))
               .andExpect(status().isForbidden());
    }

    @Test
    @WithAnonymousUser
    @DisplayName("PC15 — an anonymous create is 401")
    void pc15_createAnonymous_401() throws Exception {
        mockMvc.perform(post(PACKAGES).contentType(MediaType.APPLICATION_JSON).content(fullPackageJson()))
               .andExpect(status().isUnauthorized());
    }
}