package com.krishimitra.service;

import com.krishimitra.dto.AlertPageResponse;
import com.krishimitra.dto.AlertResponse;
import com.krishimitra.model.entity.Alert;
import com.krishimitra.model.entity.Farmer;
import com.krishimitra.repository.AlertRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

@Service
@RequiredArgsConstructor
@Slf4j
public class AlertService {

    private final AlertRepository alertRepo;

    public AlertPageResponse getAlerts(UUID farmerId, int page, int size) {
        Pageable pageable = PageRequest.of(page, size, Sort.by("createdAt").descending());
        Page<Alert> alertPage = alertRepo.findForFarmer(farmerId, Instant.now(), pageable);
        long unread = alertRepo.countUnread(farmerId, Instant.now());

        List<AlertResponse> dtos = alertPage.getContent().stream()
                .map(this::toDto)
                .toList();

        return new AlertPageResponse(dtos, unread, page, alertPage.getTotalPages());
    }

    @Transactional
    public void markAllRead(UUID farmerId) {
        alertRepo.markAllRead(farmerId);
    }

    @Transactional
    public void markRead(UUID farmerId, UUID alertId) {
        alertRepo.findById(alertId).ifPresent(a -> {
            if (a.getFarmer() != null && a.getFarmer().getId().equals(farmerId)) {
                a.setIsRead(true);
                alertRepo.save(a);
            }
        });
    }

    @Transactional
    public Alert createAlert(UUID farmerId, String type, String severity,
                              String commodity, String mandi,
                              String title, String body, Map<String, Object> metadata) {
        Farmer farmer = farmerId != null
                ? Farmer.builder().id(farmerId).build()   // reference only
                : null;

        Alert alert = Alert.builder()
                .farmer(farmer)
                .type(type)
                .severity(severity)
                .commodity(commodity)
                .mandiName(mandi)
                .title(title)
                .body(body)
                .expiresAt(Instant.now().plus(Duration.ofDays(3)))
                .build();

        return alertRepo.save(alert);
    }

    private AlertResponse toDto(Alert a) {
        return new AlertResponse(
                a.getId(), a.getType(), a.getSeverity(),
                a.getCommodity(), a.getMandiName(),
                a.getTitle(), a.getBody(), a.getIsRead(),
                a.getExpiresAt() != null ? a.getExpiresAt().toString() : null,
                a.getCreatedAt().toString()
        );
    }
}
