package com.hunt.otziv.u_users.api;

import com.hunt.otziv.u_users.images.SignedImageUrls;

/** Public entry point for other modules that show user photos: signed {@code /images/{id}} links. */
public final class UserImageLinks {

    private UserImageLinks() {
    }

    /** Signed link to the photo, or to the default avatar when the user has none. */
    public static String pathForOrDefault(Long imageId) {
        return SignedImageUrls.pathForOrDefault(imageId);
    }
}
