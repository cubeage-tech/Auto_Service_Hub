package com.autoservicehub.security;

import com.autoservicehub.entity.Role;
import com.autoservicehub.entity.User;
import com.autoservicehub.repository.UserRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.core.userdetails.UserDetails;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class CustomUserDetailsServiceTest {

    @Mock
    private UserRepository userRepository;

    @InjectMocks
    private CustomUserDetailsService userDetailsService;

    @Test
    void loadUserByUsernameNormalizesStoredOwnerRoleToSpringAuthority() {
        assertOwnerAuthority("OWNER");
        assertOwnerAuthority("ROLE_OWNER");
        assertOwnerAuthority(" owner ");
        assertOwnerAuthority(" role_owner ");
        assertOwnerAuthority("GARAGE_OWNER");
        assertOwnerAuthority("ROLE_GARAGE_OWNER");

        verify(userRepository, times(6)).findByUsernameIgnoreCase("garage-owner");
        verify(userRepository, never()).findByEmailIgnoreCase("garage-owner");
    }

    private void assertOwnerAuthority(String storedRoleName) {
        Role role = new Role();
        role.setName(storedRoleName);

        User user = new User();
        user.setUsername("garage-owner");
        user.setPasswordHash("encoded-password");
        user.setActive(true);
        user.setRole(role);

        when(userRepository.findByUsernameIgnoreCase("garage-owner"))
                .thenReturn(Optional.of(user));

        UserDetails userDetails = userDetailsService.loadUserByUsername("garage-owner");

        assertThat(userDetails.getUsername()).isEqualTo("garage-owner");
        assertThat(userDetails.getAuthorities())
                .extracting("authority")
                .containsExactly("ROLE_OWNER");
    }
}
