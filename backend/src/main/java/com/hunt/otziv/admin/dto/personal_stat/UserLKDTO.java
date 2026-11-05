package com.hunt.otziv.admin.dto.personal_stat;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.hunt.otziv.u_users.images.SignedImageUrls;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class UserLKDTO {

    private String username;
    private String role;
    private Long image;
    private int leadCount;
    private int reviewCount;

    /** Short-lived signed link to the photo, relative to the backend base URL. */
    @JsonProperty(value = "imageUrl", access = JsonProperty.Access.READ_ONLY)
    public String getImageUrl() {
        return SignedImageUrls.pathForOrDefault(image);
    }
}
