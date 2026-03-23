package com.krishimitra.service;

import com.krishimitra.dto.AuthDTOs;
import com.krishimitra.exception.ApiException;
import com.krishimitra.model.entity.Farmer;
import com.krishimitra.model.entity.RefreshToken;
import com.krishimitra.repository.FarmerRepository;
import com.krishimitra.repository.RefreshTokenRepository;
import com.krishimitra.security.JwtService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.time.Instant;
import java.util.UUID;

@Service
@RequiredArgsConstructor
@Slf4j
public class AuthService {

    private final FarmerRepository farmerRepo;
    private final RefreshTokenRepository refreshRepo;
    private final PasswordEncoder encoder;
    private final JwtService jwtService;

    @Transactional
    public AuthDTOs.TokenResponse register(AuthDTOs.RegisterRequest req) {
        if (farmerRepo.existsByPhone(req.phone())) {
            throw ApiException.conflict("Phone number already registered: " + req.phone());
        }

        Farmer farmer = Farmer.builder()
                .phone(req.phone())
                .name(req.name())
                .email(req.email())
                .passwordHash(encoder.encode(req.password()))
                .village(req.village())
                .district(req.district())
                .state(req.state() != null ? req.state() : "Madhya Pradesh")
                .latitude(req.latitude())
                .longitude(req.longitude())
                .preferredLang(req.preferredLang() != null ? req.preferredLang() : "hi")
                .build();

        farmer = farmerRepo.save(farmer);
        log.info("Registered new farmer: {} ({})", farmer.getName(), farmer.getPhone());
        return issueTokens(farmer);
    }

    @Transactional
    public AuthDTOs.TokenResponse login(AuthDTOs.LoginRequest req) {
        Farmer farmer = farmerRepo.findByPhone(req.phone())
                .filter(Farmer::getIsActive)
                .orElseThrow(() -> ApiException.unauthorized("Invalid phone or password"));

        if (!encoder.matches(req.password(), farmer.getPasswordHash())) {
            throw ApiException.unauthorized("Invalid phone or password");
        }
        return issueTokens(farmer);
    }

    @Transactional
    public AuthDTOs.TokenResponse refresh(String refreshToken) {
        RefreshToken stored = refreshRepo.findByToken(refreshToken)
                .filter(rt -> !rt.getRevoked())
                .filter(rt -> rt.getExpiresAt().isAfter(Instant.now()))
                .orElseThrow(() -> ApiException.unauthorized("Invalid or expired refresh token"));

        Farmer farmer = stored.getFarmer();
        // Rotate: revoke old, issue new
        stored.setRevoked(true);
        refreshRepo.save(stored);
        return issueTokens(farmer);
    }

    @Transactional
    public void logout(UUID farmerId) {
        refreshRepo.revokeAllForFarmer(farmerId);
        log.info("Logged out farmer: {}", farmerId);
    }

    private AuthDTOs.TokenResponse issueTokens(Farmer farmer) {
        String access  = jwtService.generateAccessToken(farmer.getId(), farmer.getPhone());
        String refresh = jwtService.generateRefreshToken(farmer.getId());

        // Store refresh token
        RefreshToken rt = RefreshToken.builder()
                .farmer(farmer)
                .token(refresh)
                .expiresAt(jwtService.getExpiry(refresh))
                .build();
        refreshRepo.save(rt);

        return new AuthDTOs.TokenResponse(
                access, refresh,
                Duration.ofDays(1).toMillis(),
                farmer.getId(), farmer.getName()
        );
    }
}
