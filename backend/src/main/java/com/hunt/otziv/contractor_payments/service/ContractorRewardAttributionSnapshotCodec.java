package com.hunt.otziv.contractor_payments.service;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Compact, versioned persistence format for the facts used to split a legacy
 * specialist reward. Only numeric identifiers and accounting inputs are
 * stored; no personal or bank data is included.
 */
public final class ContractorRewardAttributionSnapshotCodec {

    private static final String PREFIX = "v1|";

    private ContractorRewardAttributionSnapshotCodec() {
    }

    public static String encode(List<ContractorRewardAttributionService.SpecialistShare> shares) {
        if (shares == null || shares.isEmpty()) {
            return null;
        }
        List<ContractorRewardAttributionService.SpecialistShare> ordered = shares.stream()
                .filter(share -> share != null
                        && share.user() != null
                        && share.user().getId() != null
                        && share.workerId() != null)
                .sorted(Comparator.comparing(ContractorRewardAttributionService.SpecialistShare::workerId))
                .toList();
        if (ordered.isEmpty()) {
            return null;
        }
        String body = ordered.stream().map(share -> {
            BigDecimal gross = share.grossAmount() == null ? BigDecimal.ZERO : share.grossAmount();
            BigDecimal coefficient = share.user().getCoefficient() == null
                    ? BigDecimal.ZERO
                    : share.user().getCoefficient();
            return share.workerId()
                    + "," + share.user().getId()
                    + "," + gross.stripTrailingZeros().toPlainString()
                    + "," + Math.max(0, share.workUnits())
                    + "," + coefficient.stripTrailingZeros().toPlainString();
        }).collect(java.util.stream.Collectors.joining(";"));
        return PREFIX + body;
    }

    static List<SnapshotShare> decode(String encoded) {
        if (encoded == null || encoded.isBlank()) {
            return List.of();
        }
        if (!encoded.startsWith(PREFIX)) {
            throw new IllegalArgumentException("Unsupported contractor attribution snapshot version");
        }
        String body = encoded.substring(PREFIX.length());
        if (body.isBlank()) {
            return List.of();
        }
        List<SnapshotShare> result = new ArrayList<>();
        Set<Long> workerIds = new HashSet<>();
        for (String row : body.split(";", -1)) {
            String[] fields = row.split(",", -1);
            if (fields.length != 5) {
                throw new IllegalArgumentException("Malformed contractor attribution snapshot");
            }
            try {
                long workerId = Long.parseLong(fields[0]);
                long userId = Long.parseLong(fields[1]);
                BigDecimal gross = new BigDecimal(fields[2]);
                int workUnits = Integer.parseInt(fields[3]);
                BigDecimal coefficient = new BigDecimal(fields[4]);
                if (workerId <= 0 || userId <= 0 || gross.signum() < 0 || workUnits < 0
                        || coefficient.signum() < 0 || !workerIds.add(workerId)) {
                    throw new IllegalArgumentException("Invalid contractor attribution snapshot values");
                }
                result.add(new SnapshotShare(workerId, userId, gross, workUnits, coefficient));
            } catch (NumberFormatException exception) {
                throw new IllegalArgumentException("Malformed contractor attribution snapshot", exception);
            }
        }
        return List.copyOf(result);
    }

    record SnapshotShare(
            long workerId,
            long userId,
            BigDecimal grossAmount,
            int workUnits,
            BigDecimal coefficient
    ) {
    }
}
