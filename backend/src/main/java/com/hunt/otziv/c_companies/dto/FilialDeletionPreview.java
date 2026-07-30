package com.hunt.otziv.c_companies.dto;

public record FilialDeletionPreview(
        Long filialId,
        long orderCount,
        long reviewCount,
        boolean willArchive
) {
}
