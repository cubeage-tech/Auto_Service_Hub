package com.autoservicehub.security;

import com.autoservicehub.entity.User;
import com.autoservicehub.repository.UserRepository;
import com.autoservicehub.util.AppConstants;
import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.core.userdetails.UsernameNotFoundException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Locale;

@Service
@RequiredArgsConstructor
public class CustomUserDetailsService implements UserDetailsService {

    private static final Logger log = LoggerFactory.getLogger(CustomUserDetailsService.class);

    private final UserRepository userRepository;

    @Override
    @Transactional(readOnly = true)
    public UserDetails loadUserByUsername(String usernameOrEmail) {
        User user = userRepository.findByUsernameIgnoreCase(usernameOrEmail)
                .or(() -> userRepository.findByEmailIgnoreCase(usernameOrEmail))
                .orElseThrow(() -> new UsernameNotFoundException("User not found: " + usernameOrEmail));

        String roleName = user.getRole() != null ? user.getRole().getName() : null;
        if (roleName == null || roleName.isBlank()) {
            throw new UsernameNotFoundException("User role not assigned: " + usernameOrEmail);
        }

        String normalizedRoleName = roleName.trim().toUpperCase(Locale.ROOT);
        if (normalizedRoleName.startsWith("ROLE_")) {
            normalizedRoleName = normalizedRoleName.substring(5);
        }
        if ("GARAGE_OWNER".equals(normalizedRoleName)) {
            normalizedRoleName = AppConstants.ROLE_OWNER;
        }
        if (normalizedRoleName.isBlank()) {
            throw new UsernameNotFoundException("User role not assigned: " + usernameOrEmail);
        }
        String normalizedRole = "ROLE_" + normalizedRoleName;
        String lookupType = user.getUsername() != null
                && user.getUsername().equalsIgnoreCase(usernameOrEmail)
                ? "username"
                : user.getEmail() != null && user.getEmail().equalsIgnoreCase(usernameOrEmail)
                        ? "email"
                        : "unmatched";
        log.info("Security user resolved lookupType={} authority={} accountEnabled={}",
                lookupType, normalizedRole, !Boolean.FALSE.equals(user.getActive()));

        return org.springframework.security.core.userdetails.User
                .withUsername(user.getUsername())
                .password(user.getPasswordHash())
                .authorities(List.of(new SimpleGrantedAuthority(normalizedRole)))
                .disabled(Boolean.FALSE.equals(user.getActive()))
                .build();
    }
}