package com.autoservicehub.service;

import com.autoservicehub.dto.CustomerRequestDTO;
import com.autoservicehub.dto.CustomerResponseDTO;
import com.autoservicehub.entity.Customer;
import com.autoservicehub.repository.CustomerRepository;
import com.autoservicehub.service.impl.CustomerServiceImpl;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import jakarta.validation.Validation;
import jakarta.validation.Validator;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.context.annotation.Import;

import static org.assertj.core.api.Assertions.assertThat;

@DataJpaTest(properties = {
        "spring.datasource.url=jdbc:h2:mem:customer-loyalty-test;DB_CLOSE_DELAY=-1;DB_CLOSE_ON_EXIT=FALSE;NON_KEYWORDS=YEAR",
        "spring.datasource.driver-class-name=org.h2.Driver",
        "spring.jpa.hibernate.ddl-auto=create-drop",
        "spring.jpa.properties.hibernate.dialect=org.hibernate.dialect.H2Dialect"
})
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Import(CustomerServiceImpl.class)
class CustomerLoyaltyPersistenceTest {

    @Autowired
    private CustomerServiceImpl customerService;

    @Autowired
    private CustomerRepository customerRepository;

    @PersistenceContext
    private EntityManager entityManager;

    @Test
    void createsAndReloadsLoyaltyTierAndNotes() {
        CustomerRequestDTO request = customerRequest();
        request.setLoyaltyTier("gOlD");
        request.setNotes("Prefers weekend appointments.");

        CustomerResponseDTO created = customerService.create(request);
        customerRepository.flush();
        entityManager.clear();

        CustomerResponseDTO reloaded = customerService.getById(created.getId());

        assertThat(reloaded.getLoyaltyTier()).isEqualTo("Gold");
        assertThat(reloaded.getNotes()).isEqualTo("Prefers weekend appointments.");
    }

    @Test
    void missingOptionalFieldsUseSafeDefaultsAndUpdatesPreserveStoredValues() {
        CustomerRequestDTO enrichedRequest = customerRequest();
        enrichedRequest.setLoyaltyTier("Gold");
        enrichedRequest.setNotes("Call before scheduling.");
        CustomerResponseDTO created = customerService.create(enrichedRequest);

        CustomerRequestDTO legacyUpdate = customerRequest();
        legacyUpdate.setName("Updated Customer");
        CustomerResponseDTO updated = customerService.update(created.getId(), legacyUpdate);

        assertThat(updated.getLoyaltyTier()).isEqualTo("Gold");
        assertThat(updated.getNotes()).isEqualTo("Call before scheduling.");

        CustomerResponseDTO createdWithoutOptionalValues =
                customerService.create(customerRequestWithPhone("5559999999"));
        assertThat(createdWithoutOptionalValues.getLoyaltyTier()).isEqualTo("Bronze");
        assertThat(createdWithoutOptionalValues.getNotes()).isNull();
    }

    @Test
    void customerResponseMappingIncludesPersistedFields() {
        Customer customer = new Customer();
        customer.setName("Mapped Customer");
        customer.setPhone("5551234567");
        customer.setLoyaltyTier("Silver");
        customer.setNotes("Use synthetic oil.");
        customer = customerRepository.saveAndFlush(customer);

        CustomerResponseDTO response = customerService.getById(customer.getId());

        assertThat(response.getLoyaltyTier()).isEqualTo("Silver");
        assertThat(response.getNotes()).isEqualTo("Use synthetic oil.");
    }

    @Test
    void requestValidationAcceptsSupportedTierAndOptionalNotes() {
        CustomerRequestDTO request = customerRequest();
        request.setLoyaltyTier("Platinum");

        try (var factory = Validation.buildDefaultValidatorFactory()) {
            Validator validator = factory.getValidator();
            assertThat(validator.validate(request)).isEmpty();

            request.setLoyaltyTier("Diamond");
            assertThat(validator.validate(request))
                    .anySatisfy(violation ->
                            assertThat(violation.getPropertyPath().toString()).isEqualTo("loyaltyTier"));
        }
    }

    private CustomerRequestDTO customerRequest() {
        return customerRequestWithPhone("5551234567");
    }

    private CustomerRequestDTO customerRequestWithPhone(String phone) {
        CustomerRequestDTO request = new CustomerRequestDTO();
        request.setName("Test Customer");
        request.setPhone(phone);
        request.setEmail("customer@example.test");
        request.setAddress("Test Street");
        return request;
    }
}
