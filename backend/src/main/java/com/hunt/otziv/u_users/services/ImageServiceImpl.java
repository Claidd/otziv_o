package com.hunt.otziv.u_users.services;

import com.hunt.otziv.u_users.model.Image;
import com.hunt.otziv.u_users.repository.ImageRepository;
import com.hunt.otziv.u_users.services.service.ImageService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import net.coobird.thumbnailator.Thumbnails;
import net.coobird.thumbnailator.geometry.Positions;
import org.springframework.data.util.Pair;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.Map;
import java.util.stream.Collectors;

@Service
@Slf4j
@RequiredArgsConstructor
public class ImageServiceImpl implements ImageService {

    private final ImageRepository imageRepository;


    @Override
    public Map<String, Pair<Long, Long>> getAllImages() {
        return imageRepository.findAllToScore().stream()
                .collect(Collectors.toMap(
                        obj -> (String) obj[0],  // Ключ: u.fio (String)
                        obj -> Pair.of((Long) obj[1], (Long) obj[2]),  // Значение: Pair<Long, Long> (id изображения, id пользователя)
                        (existing, replacement) -> existing // Если ключ уже есть, не меняем значение
                ));
    }

    @Override
    public Image saveCompressedProfileImage(MultipartFile file) throws IOException {
        ByteArrayOutputStream baos = new ByteArrayOutputStream();

        Thumbnails.of(file.getInputStream())
                .size(512, 512)
                .crop(Positions.CENTER)
                .outputFormat("jpg")
                .outputQuality(0.7)
                .toOutputStream(baos);

        Image image = new Image();
        image.setName(file.getName());
        image.setOriginalFileName(file.getOriginalFilename());
        image.setContentType(MediaType.IMAGE_JPEG_VALUE);
        image.setSize((long) baos.size());
        image.setBytes(baos.toByteArray());

        return imageRepository.save(image);
    }
}
