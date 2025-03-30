package com.hunt.otziv.u_users.services.service;

import org.springframework.data.util.Pair;

import java.util.Map;

public interface ImageService {
    Map<String, Pair<Long, Long>>  getAllImages();
}
