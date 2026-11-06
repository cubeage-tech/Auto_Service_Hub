package com.autoservicehub.service;

import com.autoservicehub.dto.UserRequestDTO;
import com.autoservicehub.dto.UserResponseDTO;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;

/**
 * User account administration (SRS 8.2 High-Level Entities; SRS 13 Roles &
 * Permissions; SRS 19 Security Requirements).
 *
 * <p>This is the <em>administrative</em> account-creation flow: an already
 * authorised member of the workshop staff creates a login for a colleague.
 * It is deliberately not a self-registration flow, so the roles it hands out
 * are drawn from the workshop's own roles rather than from the customer
 * portal's {@code CUSTOMER} role — see
 * {@link com.autoservicehub.service.impl.UserServiceImpl}.
 *
 * <p>Two things a caller can rely on: a password sent here is hashed before it
 * is stored and never appears in a response, and an account is only created if
 * the requested username and email are both free. The read below returns the
 * same credential-free projection, so a listed account never carries a password
 * either.
 */
public interface UserService {

    /**
     * Creates one user account.
     *
     * <p>A missing {@code status} means {@code Active}; {@code Inactive} creates
     * the account without allowing it to sign in yet. The password is hashed
     * with the application's {@code PasswordEncoder} before the row is written.
     *
     * @return the created account, without any credential field
     * @throws com.autoservicehub.exception.BusinessRuleException the username or
     *         email is already taken (case-insensitive), the requested role is
     *         not an assignable staff role, the role has no row in the
     *         {@code roles} table, or the status is not
     *         {@code Active} / {@code Inactive}
     */
    UserResponseDTO createUser(UserRequestDTO request);

    /**
     * Lists user accounts, one page at a time.
     *
     * <p>A read-only view for the administration screen that creates the
     * accounts: the same safe {@link UserResponseDTO} the create flow returns,
     * so no credential is selected and {@code users.password_hash} cannot reach
     * the response. Nothing is written and no audit entry is recorded — listing
     * accounts changes none of them.
     *
     * <p>Every account in {@code users} is listed, including the ones another
     * flow created: the customer portal's {@code CUSTOMER} logins live in the
     * same table, and a screen that can create an account but not show it would
     * misreport what the workshop holds.
     *
     * @param pageable page, size and sort for the read; the screen asks for
     *                 {@code id,desc} so a newly created account is on top
     * @return one page of accounts, each with its stored role name and status
     */
    Page<UserResponseDTO> listUsers(Pageable pageable);
}
