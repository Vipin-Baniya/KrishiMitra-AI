package com.krishimitra.security;

import io.jsonwebtoken.*;
import io.jsonwebtoken.io.Decoders;
import io.jsonwebtoken.security.Keys;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import javax.crypto.SecretKey;
import java.time.Instant;
import java.util.Date;
import java.util.UUID;

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
