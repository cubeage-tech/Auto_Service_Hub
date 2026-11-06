package com.autoservicehub.repository;

import com.autoservicehub.entity.Vehicle;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.data.domain.PageRequest;

import static org.assertj.core.api.Assertions.assertThat;

@DataJpaTest(properties = {
        "spring.datasource.url=jdbc:h2:mem:vehicle-filter-test;DB_CLOSE_DELAY=-1;DB_CLOSE_ON_EXIT=FALSE;NON_KEYWORDS=YEAR",
        "spring.datasource.driver-class-name=org.h2.Driver",
        "spring.jpa.hibernate.ddl-auto=create-drop",
        "spring.jpa.properties.hibernate.dialect=org.hibernate.dialect.H2Dialect"
})
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
class VehicleFilteringPersistenceTest {

    @Autowired
    private VehicleRepository vehicleRepository;

    @Test
    void filtersEachSupportedStatusByFuelTypeAndTheirCombinationWithPagination() {
        saveVehicle("REG-1", "PETROL", "IN_SERVICE");
        saveVehicle("REG-2", "DIESEL", "WAITING");
        saveVehicle("REG-3", "PETROL", "DELIVERED");
        saveVehicle("REG-4", null, null);

        assertThat(vehicleRepository.findFiltered(null, null, PageRequest.of(0, 2)).getTotalElements())
                .isEqualTo(4);
        assertThat(vehicleRepository.findFiltered("PETROL", null, PageRequest.of(0, 10))
                .getContent()).extracting(Vehicle::getRegistrationNo)
                .containsExactlyInAnyOrder("REG-1", "REG-3");
        assertThat(vehicleRepository.findFiltered(null, "waiting", PageRequest.of(0, 10))
                .getContent()).extracting(Vehicle::getRegistrationNo)
                .containsExactly("REG-2");
        assertThat(vehicleRepository.findFiltered(null, "DELIVERED", PageRequest.of(0, 10))
                .getContent()).extracting(Vehicle::getRegistrationNo)
                .containsExactly("REG-3");
        assertThat(vehicleRepository.findFiltered(null, "IN_SERVICE", PageRequest.of(0, 10))
                .getContent()).extracting(Vehicle::getRegistrationNo)
                .containsExactly("REG-1");
        assertThat(vehicleRepository.findFiltered("PETROL", "in_service", PageRequest.of(0, 10))
                .getContent()).extracting(Vehicle::getRegistrationNo)
                .containsExactly("REG-1");
    }

    private Vehicle saveVehicle(String registrationNo, String fuelType, String status) {
        Vehicle vehicle = new Vehicle();
        vehicle.setRegistrationNo(registrationNo);
        vehicle.setFuelType(fuelType);
        vehicle.setStatus(status);
        return vehicleRepository.saveAndFlush(vehicle);
    }
}
