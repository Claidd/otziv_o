package com.hunt.otziv.performers.service;

import com.hunt.otziv.c_cities.model.City;
import com.hunt.otziv.config.settings.AppSettingService;
import com.hunt.otziv.p_products.model.Product;
import com.hunt.otziv.performers.dto.PerformerRolloutSettingsRequest;
import com.hunt.otziv.performers.dto.PerformerRolloutSettingsResponse;
import com.hunt.otziv.performers.model.ReviewPerformerAssignment;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

@Service
@RequiredArgsConstructor
public class PerformerRolloutService {

    private final AppSettingService appSettingService;

    @Transactional(readOnly = true)
    public PerformerRolloutSettingsResponse settings() {
        boolean enabled = appSettingService.getBoolean(AppSettingService.PERFORMERS_ROLLOUT_ENABLED, false);
        String cityIds = appSettingService.getStringAllowEmpty(AppSettingService.PERFORMERS_ROLLOUT_CITY_IDS, "");
        String productIds = appSettingService.getStringAllowEmpty(AppSettingService.PERFORMERS_ROLLOUT_PRODUCT_IDS, "");
        return new PerformerRolloutSettingsResponse(
                enabled,
                cityIds,
                productIds,
                parseIds(cityIds).stream().toList(),
                parseIds(productIds).stream().toList()
        );
    }

    @Transactional
    public PerformerRolloutSettingsResponse update(PerformerRolloutSettingsRequest request) {
        PerformerRolloutSettingsRequest safeRequest = request == null
                ? new PerformerRolloutSettingsRequest(null, null, null)
                : request;
        appSettingService.setBoolean(
                AppSettingService.PERFORMERS_ROLLOUT_ENABLED,
                Boolean.TRUE.equals(safeRequest.enabled())
        );
        appSettingService.setString(
                AppSettingService.PERFORMERS_ROLLOUT_CITY_IDS,
                normalizeIds(safeRequest.cityIds())
        );
        appSettingService.setString(
                AppSettingService.PERFORMERS_ROLLOUT_PRODUCT_IDS,
                normalizeIds(safeRequest.productIds())
        );
        return settings();
    }

    @Transactional(readOnly = true)
    public boolean isAllowed(Product product, City city) {
        PerformerRolloutSettingsResponse current = settings();
        if (!current.enabled()) {
            return false;
        }
        if (!containsOrEmpty(current.parsedProductIds(), product == null ? null : product.getId())) {
            return false;
        }
        return containsOrEmpty(current.parsedCityIds(), city == null ? null : city.getId());
    }

    @Transactional(readOnly = true)
    public boolean isAllowed(ReviewPerformerAssignment assignment) {
        PerformerRolloutSettingsResponse current = settings();
        if (!current.enabled()) {
            return false;
        }
        Long cityId = assignment == null || assignment.getCity() == null ? null : assignment.getCity().getId();
        Long productId = null;
        if (assignment != null && assignment.getReview() != null && assignment.getReview().getProduct() != null) {
            productId = assignment.getReview().getProduct().getId();
        }
        if (productId == null && assignment != null && assignment.getOrderDetails() != null && assignment.getOrderDetails().getProduct() != null) {
            productId = assignment.getOrderDetails().getProduct().getId();
        }
        return containsOrEmpty(current.parsedProductIds(), productId)
                && containsOrEmpty(current.parsedCityIds(), cityId);
    }

    private boolean containsOrEmpty(List<Long> allowedIds, Long value) {
        if (allowedIds == null || allowedIds.isEmpty()) {
            return true;
        }
        return value != null && allowedIds.contains(value);
    }

    private String normalizeIds(String value) {
        return parseIds(value).stream()
                .map(String::valueOf)
                .reduce((left, right) -> left + "," + right)
                .orElse("");
    }

    private Set<Long> parseIds(String value) {
        Set<Long> ids = new LinkedHashSet<>();
        if (!StringUtils.hasText(value)) {
            return ids;
        }
        String[] parts = value.toLowerCase(Locale.ROOT).split("[,;\\s]+");
        for (String part : parts) {
            if (!StringUtils.hasText(part)) {
                continue;
            }
            try {
                long id = Long.parseLong(part.trim());
                if (id > 0) {
                    ids.add(id);
                }
            } catch (NumberFormatException ignored) {
                // Ignore malformed rollout values so one typo does not stop the scheduler.
            }
        }
        return ids;
    }
}
