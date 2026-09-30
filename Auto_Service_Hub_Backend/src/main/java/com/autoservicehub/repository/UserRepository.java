package com.autoservicehub.repository;

import com.autoservicehub.entity.User;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

/**
 * Spring Data JPA repository for User.
 * Extends JpaSpecificationExecutor so list/report endpoints
 * can apply dynamic filters.
 */
@Repository
public interface UserRepository extends JpaRepository<User, Long>, JpaSpecificationExecutor<User> {

    Optional<User> findByEmailIgnoreCase(String email);

    Optional<User> findByUsernameIgnoreCase(String username);

    /**
     * Active users holding any of the given roles. Used to decide who a
     * due-follow-up notification should be addressed to.
     */
    @Query("SELECT u FROM User u WHERE u.active = true "
         + "AND UPPER(u.role.name) IN :roleNames ORDER BY u.id ASC")
    List<User> findActiveByRoleNameIn(@Param("roleNames") List<String> roleNames);
}
