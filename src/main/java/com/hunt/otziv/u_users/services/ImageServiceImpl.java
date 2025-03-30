package com.hunt.otziv.u_users.services;

import com.hunt.otziv.u_users.model.Image;
import com.hunt.otziv.u_users.repository.ImageRepository;
import com.hunt.otziv.u_users.services.service.ImageService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.util.Pair;
import org.springframework.stereotype.Service;

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

}
