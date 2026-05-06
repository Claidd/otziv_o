package com.hunt.otziv.u_users.services.service;

import com.hunt.otziv.u_users.model.Image;
import org.springframework.data.util.Pair;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.util.Map;

public interface ImageService {
    Map<String, Pair<Long, Long>>  getAllImages();

    Image saveCompressedProfileImage(MultipartFile file) throws IOException;
}
