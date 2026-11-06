package com.autoservicehub.controller;

import com.autoservicehub.dto.ApiResponse;
import com.autoservicehub.dto.UserRequestDTO;
import com.autoservicehub.dto.UserResponseDTO;
import com.autoservicehub.service.UserService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/**
 * User account administration (SRS 8.2 High-Level Entities; SRS 13 Roles &amp;
 * Permissions; SRS 19 Security Requirements).
 *
 * <p>Base path: {@code /api/v1/users}. Every endpoint requires a valid JWT and
 * is further restricted by role (SRS 13), enforced by the method security
 * enabled in {@code SecurityConfig}. Both endpoints are held to {@code OWNER}:
 * this is the screen that hands out workshop logins, so the accounts it shows
 * are as sensitive as the ones it creates.
 *
 * <p>Creation and reading are exposed here. Deactivation and role changes are
 * deliberately absent: the SRS specifies creation of a staff login and nothing
 * more, and the roles handed out are limited to the workshop's own staff roles
 * by {@link UserService}.
 */
@RestController
@RequestMapping("/api/v1/users")
@RequiredArgsConstructor
public class UserController {

    private final UserService service;

    /**
     * Creates a new user account.
     *
     * <p>POST /api/v1/users — responds {@code 201 Created} with the standard
     * {@link ApiResponse} envelope wrapping a safe {@link UserResponseDTO}
     * projection, which carries no password or password-hash field.
     */
    @PostMapping
    @PreAuthorize("hasAnyRole('OWNER')")
    @ResponseStatus(HttpStatus.CREATED)
    public ApiResponse<UserResponseDTO> create(
            @Valid @RequestBody UserRequestDTO request) {
        return ApiResponse.ok("Created", service.createUser(request));
    }

    /**
     * Lists the user accounts, one page at a time.
     *
     * <p>GET /api/v1/users?page=0&amp;size=20&amp;sort=id,desc — responds
     * {@code 200 OK} with the standard {@link ApiResponse} envelope wrapping a
     * {@link Page} of the same safe {@link UserResponseDTO} the create endpoint
     * returns. That projection has no password or password-hash field, so the
     * read cannot expose a credential, and it writes nothing — no row, role or
     * assignment is touched by listing.
     *
     * <p>Restricted to {@code OWNER}, the same role the create endpoint requires:
     * whoever may hand out a login may see which logins exist.
     */
    @GetMapping
    @PreAuthorize("hasAnyRole('OWNER')")
    public ApiResponse<Page<UserResponseDTO>> list(Pageable pageable) {
        return ApiResponse.ok(service.listUsers(pageable));
    }
}
