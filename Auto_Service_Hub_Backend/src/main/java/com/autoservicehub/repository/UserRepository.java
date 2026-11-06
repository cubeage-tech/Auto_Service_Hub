package com.autoservicehub.repository;

import com.autoservicehub.entity.User;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;
import org.springframework.data.jpa.repository.EntityGraph;
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

    @EntityGraph(attributePaths = "role")
    Optional<User> findByEmailIgnoreCase(String email);

    @EntityGraph(attributePaths = "role")
    Optional<User> findByUsernameIgnoreCase(String username);

    /**
     * True when an account already uses this username, ignoring case.
     *
     * <p>Sign-in resolves the username case-insensitively (see
     * {@code CustomUserDetailsService}), so two rows differing only in case
     * would be one credential with two owners. Uniqueness is therefore checked
     * the same way it is matched.
     */
    boolean existsByUsernameIgnoreCase(String username);

    /**
     * True when an account already uses this email, ignoring case.
     *
     * <p>The email doubles as an alternative sign-in name and as the password
     * reset address, so it has to identify exactly one account.
     */
    boolean existsByEmailIgnoreCase(String email);

    /**
     * One page of accounts, with the role fetched alongside each row.
     *
     * <p>Overrides the inherited {@code findAll(Pageable)} only to add the
     * entity graph: the user-management list reports every row's role name, and
     * {@code users.role_id} is a lazy association, so without the graph a page
     * of accounts would cost one extra query per row. The read itself is
     * unchanged — same rows, same order, nothing written.
     */
    @EntityGraph(attributePaths = "role")
    Page<User> findAll(Pageable pageable);

    /**
     * Active users holding any of the given roles. Used to decide who a
     * due-follow-up notification should be addressed to.
     */
    @Query("SELECT u FROM User u WHERE u.active = true "
         + "AND UPPER(u.role.name) IN :roleNames ORDER BY u.id ASC")
    List<User> findActiveByRoleNameIn(@Param("roleNames") List<String> roleNames);
}
