package com.krishimitra.security;

import com.krishimitra.exception.ApiException;
import com.krishimitra.repository.FarmerRepository;
import io.jsonwebtoken.*;
import io.jsonwebtoken.io.Decoders;
import io.jsonwebtoken.security.Keys;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.config.annotation.authentication.configuration.AuthenticationConfiguration;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.core.userdetails.*;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;
import org.springframework.stereotype.Component;
import org.springframework.stereotype.Service;
import org.springframework.web.filter.OncePerRequestFilter;

import javax.crypto.SecretKey;
import java.io.IOException;
import java.time.Instant;
import java.util.Date;
import java.util.List;
import java.util.UUID;

// ─────────────────────────────────────────────────────────────
// JWT SERVICE
// ─────────────────────────────────────────────────────────────

@Component
@Slf4j
public class JwtService {

    private final SecretKey secretKey;
    private final long expiryMs;
    private final long refreshExpiryMs;

    public JwtService(
            @Value("${krishimitra.jwt.secret}") String secret,
            @Value("${krishimitra.jwt.expiry-ms}") long expiryMs,
            @Value("${krishimitra.jwt.refresh-expiry-ms}") long refreshExpiryMs) {
        this.secretKey       = Keys.hmacShaKeyFor(Decoders.BASE64.decode(
                java.util.Base64.getEncoder().encodeToString(secret.getBytes())));
        this.expiryMs        = expiryMs;
        this.refreshExpiryMs = refreshExpiryMs;
    }

    public String generateAccessToken(UUID farmerId, String phone) {
        return Jwts.builder()
                .subject(farmerId.toString())
                .claim("phone", phone)
                .claim("type", "access")
                .issuedAt(new Date())
                .expiration(new Date(System.currentTimeMillis() + expiryMs))
                .signWith(secretKey)
                .compact();
    }

    public String generateRefreshToken(UUID farmerId) {
        return Jwts.builder()
                .subject(farmerId.toString())
                .claim("type", "refresh")
                .issuedAt(new Date())
                .expiration(new Date(System.currentTimeMillis() + refreshExpiryMs))
                .signWith(secretKey)
                .compact();
    }

    public Claims validateAndExtract(String token) {
        return Jwts.parser()
                .verifyWith(secretKey)
                .build()
                .parseSignedClaims(token)
                .getPayload();
    }

    public UUID extractFarmerId(String token) {
        return UUID.fromString(validateAndExtract(token).getSubject());
    }

    public boolean isTokenValid(String token) {
        try {
            Claims claims = validateAndExtract(token);
            return claims.getExpiration().after(new Date());
        } catch (JwtException | IllegalArgumentException e) {
            return false;
        }
    }

    public Instant getExpiry(String token) {
        return validateAndExtract(token).getExpiration().toInstant();
    }
}

// ─────────────────────────────────────────────────────────────
// FARMER PRINCIPAL
// ─────────────────────────────────────────────────────────────

record FarmerPrincipal(UUID farmerId, String phone) implements UserDetails {
    @Override public String getUsername()  { return phone; }
    @Override public String getPassword()  { return ""; }
    @Override public boolean isEnabled()   { return true; }
    @Override public boolean isAccountNonExpired()   { return true; }
    @Override public boolean isAccountNonLocked()    { return true; }
    @Override public boolean isCredentialsNonExpired() { return true; }
    @Override public java.util.Collection<? extends org.springframework.security.core.GrantedAuthority> getAuthorities() {
        return List.of(() -> "ROLE_FARMER");
    }
}

// ─────────────────────────────────────────────────────────────
// FARMER USER DETAILS SERVICE
// ─────────────────────────────────────────────────────────────

@Service
@RequiredArgsConstructor
class FarmerUserDetailsService implements UserDetailsService {

    private final FarmerRepository farmerRepository;

    @Override
    public UserDetails loadUserByUsername(String phone) throws UsernameNotFoundException {
        return farmerRepository.findByPhone(phone)
                .map(f -> new FarmerPrincipal(f.getId(), f.getPhone()))
                .orElseThrow(() -> new UsernameNotFoundException("Farmer not found: " + phone));
    }
}

// ─────────────────────────────────────────────────────────────
// JWT AUTHENTICATION FILTER
// ─────────────────────────────────────────────────────────────

@Component
@RequiredArgsConstructor
@Slf4j
class JwtAuthenticationFilter extends OncePerRequestFilter {

    private final JwtService jwtService;
    private final FarmerUserDetailsService userDetailsService;

    @Override
    protected void doFilterInternal(
            HttpServletRequest request,
            HttpServletResponse response,
            FilterChain filterChain) throws ServletException, IOException {

        String authHeader = request.getHeader("Authorization");
        if (authHeader == null || !authHeader.startsWith("Bearer ")) {
            filterChain.doFilter(request, response);
            return;
        }

        String token = authHeader.substring(7);
        try {
            if (jwtService.isTokenValid(token)) {
                UUID farmerId = jwtService.extractFarmerId(token);
                Claims claims = jwtService.validateAndExtract(token);
                String phone  = claims.get("phone", String.class);

                if ("access".equals(claims.get("type", String.class))
                        && SecurityContextHolder.getContext().getAuthentication() == null) {

                    FarmerPrincipal principal = new FarmerPrincipal(farmerId, phone);
                    var auth = new UsernamePasswordAuthenticationToken(
                            principal, null, principal.getAuthorities());
                    auth.setDetails(new org.springframework.security.web.authentication
                            .WebAuthenticationDetailsSource().buildDetails(request));
                    SecurityContextHolder.getContext().setAuthentication(auth);
                }
            }
        } catch (Exception e) {
            log.debug("JWT validation failed: {}", e.getMessage());
        }

        filterChain.doFilter(request, response);
    }
}

// ─────────────────────────────────────────────────────────────
// SECURITY CONFIGURATION
// ─────────────────────────────────────────────────────────────

@Configuration
@EnableWebSecurity
@EnableMethodSecurity
@RequiredArgsConstructor
class SecurityConfig {

    private final JwtAuthenticationFilter jwtFilter;

    private static final String[] PUBLIC_PATHS = {
            "/api/v1/auth/**",
            "/api/v1/prices/live",          // public price data
            "/api/v1/prices/mandis",
            "/actuator/health",
            "/actuator/info",
            "/v3/api-docs/**",
            "/swagger-ui/**",
            "/swagger-ui.html",
    };

    @Bean
    public SecurityFilterChain filterChain(HttpSecurity http) throws Exception {
        return http
                .csrf(AbstractHttpConfigurer::disable)
                .sessionManagement(s -> s.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .authorizeHttpRequests(auth -> auth
                        .requestMatchers(PUBLIC_PATHS).permitAll()
                        .requestMatchers(HttpMethod.GET, "/api/v1/prices/**").permitAll()
                        .anyRequest().authenticated()
                )
                .addFilterBefore(jwtFilter, UsernamePasswordAuthenticationFilter.class)
                .exceptionHandling(ex -> ex
                        .authenticationEntryPoint((req, res, e) -> {
                            res.setStatus(HttpStatus.UNAUTHORIZED.value());
                            res.setContentType("application/json");
                            res.getWriter().write("{\"error\":\"Unauthorized\",\"status\":401}");
                        })
                )
                .build();
    }

    @Bean
    public PasswordEncoder passwordEncoder() {
        return new BCryptPasswordEncoder(12);
    }

    @Bean
    public AuthenticationManager authenticationManager(AuthenticationConfiguration config) throws Exception {
        return config.getAuthenticationManager();
    }
}
