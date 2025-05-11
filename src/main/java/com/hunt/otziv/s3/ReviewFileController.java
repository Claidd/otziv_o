package com.hunt.otziv.s3;

import com.hunt.otziv.r_review.model.Review;
import com.hunt.otziv.r_review.repository.ReviewRepository;
import com.hunt.otziv.s3.service.S3UploadService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;

@Controller
@RequiredArgsConstructor
public class ReviewFileController {

    private final ReviewRepository reviewRepository;
    private final S3UploadService s3UploadService;

    @PostMapping("/reviews/{id}/upload-photo")
    public String uploadPhoto(@PathVariable Long id,
                              @RequestParam("file") MultipartFile file,
                              RedirectAttributes redirectAttributes) {

        Review review = reviewRepository.findById(id)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND));

        String newUrl = s3UploadService.uploadFile(file, "reviews", review.getUrl(), review.getId());
        review.setUrl(newUrl);
        reviewRepository.save(review);

        redirectAttributes.addFlashAttribute("saveSuccess", true);

        // Например, возвращаемся на страницу редактирования отзыва
        return "redirect:/review/editReview/" + id;
    }
}
