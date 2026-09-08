package com.hunt.otziv.s3.controller;

import com.hunt.otziv.config.legacy.LegacyMvc;
import com.hunt.otziv.s3.api.ReviewPhotoUploads;
import lombok.RequiredArgsConstructor;
import org.springframework.security.core.Authentication;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;

@Controller
@LegacyMvc
@RequiredArgsConstructor
public class ReviewFileController {

    private final ReviewPhotoUploads photoUploads;

    @PostMapping("/reviews/{id}/upload-photo")
    public String uploadPhoto(@PathVariable Long id,
                              @RequestParam("file") MultipartFile file,
                              Authentication authentication,
                              RedirectAttributes redirectAttributes) {
        try {
            photoUploads.replace(id, null, file, authentication);
        } catch (com.hunt.otziv.p_products.api.ReviewPhotoRecords.Unavailable unavailable) {
            throw new org.springframework.web.server.ResponseStatusException(
                    org.springframework.http.HttpStatus.NOT_FOUND, unavailable.getMessage(), unavailable);
        }

        redirectAttributes.addFlashAttribute("saveSuccess", true);

        return "redirect:/review/editReview/" + id;
    }
}
