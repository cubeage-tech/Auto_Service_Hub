package com.autoservicehub.security;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.security.web.authentication.WebAuthenticationDetailsSource;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.stream.Collectors;

/**
 * Extracts and validates the JWT bearer token on every request, populating the
 * SecurityContext so downstream @PreAuthorize / RBAC checks work (SRS 19).
 */
@Component
@RequiredArgsConstructor
public class JwtAuthenticationFilter extends OncePerRequestFilter {

    private static final Logger log = LoggerFactory.getLogger(JwtAuthenticationFilter.class);

    private final JwtTokenProvider tokenProvider;
    private final CustomUserDetailsService userDetailsService;

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response,
                                     FilterChain filterChain) throws ServletException, IOException {
        String header = request.getHeader("Authorization");
        String path = request.getRequestURI();
        boolean diagnoseRequest = path.startsWith("/api/v1/vehicles")
                || path.startsWith("/api/v1/customers");
        if (diagnoseRequest) {
            log.info("JWT diagnostic path={} authorizationHeaderPresent={} bearerScheme={}",
                    path, header != null, header != null && header.startsWith("Bearer "));
        }
        if (header != null && header.startsWith("Bearer ")) {
            String token = header.substring(7);
            boolean valid = tokenProvider.validateToken(token);
            if (diagnoseRequest) {
                log.info("JWT diagnostic path={} accessTokenAccepted={}", path, valid);
            }
            if (valid) {
                String username = tokenProvider.getUsername(token);
                UserDetails userDetails = userDetailsService.loadUserByUsername(username);
                UsernamePasswordAuthenticationToken auth =
                        new UsernamePasswordAuthenticationToken(userDetails, null, userDetails.getAuthorities());
                auth.setDetails(new WebAuthenticationDetailsSource().buildDetails(request));
                SecurityContextHolder.getContext().setAuthentication(auth);
                if (diagnoseRequest) {
                    log.info("JWT authentication established path={} subjectMatchesPrincipal={} authorities={}",
                            path,
                            username.equalsIgnoreCase(userDetails.getUsername()),
                            auth.getAuthorities().stream()
                                    .map(authority -> authority.getAuthority())
                                    .sorted()
                                    .collect(Collectors.joining(",")));
                }
            }
        }
        filterChain.doFilter(request, response);
    }
}
