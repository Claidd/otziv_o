package com.hunt.otziv.u_users.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.hunt.otziv.u_users.images.SignedImageUrls;
import com.hunt.otziv.u_users.model.Image;
import com.hunt.otziv.u_users.repository.ImageRepository;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.time.Duration;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.support.StaticListableBeanFactory;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

class ImageControllerTest {

    private static final String SIGNING_MATERIAL = "test-image-signing-material-0123456789abcdef";

    private final ImageRepository repository = mock(ImageRepository.class);
    private final MeterRegistry registry = new SimpleMeterRegistry();

    @Test
    void signedLinkServesThePhotoWithPrivateCacheBoundToTheSignature() throws Exception {
        SignedImageUrls signer = new SignedImageUrls(SIGNING_MATERIAL, Duration.ofHours(1), false);
        when(repository.findById(42L)).thenReturn(Optional.of(image(42L)));

        String cacheControl = mvc(signer).perform(get(signer.signedPath(42)))
                .andExpect(status().isOk())
                .andExpect(content().bytes(new byte[] {1, 2, 3}))
                .andReturn().getResponse().getHeader("Cache-Control");

        assertThat(cacheControl).contains("private").doesNotContain("public");
        long maxAge = Long.parseLong(cacheControl.replaceAll(".*max-age=(\\d+).*", "$1"));
        assertThat(maxAge).isBetween(3500L, 4500L);
        assertThat(registry.get("otziv.images.requests").tag("access", "signed").counter().count()).isEqualTo(1.0);
    }

    @Test
    void strictModeRejectsGuessedIdsBeforeReadingTheDatabase() throws Exception {
        SignedImageUrls signer = new SignedImageUrls(SIGNING_MATERIAL, Duration.ofHours(1), false);
        String signedForOtherImage = signer.signedPath(41).replace("/images/41", "/images/42");
        MockMvc mvc = mvc(signer);

        mvc.perform(get("/images/42"))
                .andExpect(status().isForbidden())
                .andExpect(header().string("Cache-Control", "no-store"));
        mvc.perform(get(signedForOtherImage)).andExpect(status().isForbidden());

        verify(repository, never()).findById(any());
        assertThat(registry.get("otziv.images.requests").tag("access", "denied").counter().count()).isEqualTo(2.0);
    }

    @Test
    void transitionModeKeepsInstalledClientsWorkingAndCountsThem() throws Exception {
        SignedImageUrls signer = new SignedImageUrls(SIGNING_MATERIAL, Duration.ofHours(1), true);
        when(repository.findById(42L)).thenReturn(Optional.of(image(42L)));
        MockMvc mvc = mvc(signer);

        mvc.perform(get("/images/42"))
                .andExpect(status().isOk())
                .andExpect(header().string("Cache-Control", "max-age=86400, private"));
        mvc.perform(get("/images/42").header("If-None-Match", "\"42-3\""))
                .andExpect(status().isNotModified());

        assertThat(registry.get("otziv.images.requests").tag("access", "unsigned").counter().count()).isEqualTo(2.0);
    }

    private MockMvc mvc(SignedImageUrls signer) {
        StaticListableBeanFactory beans = new StaticListableBeanFactory(Map.of("meterRegistry", registry));
        return MockMvcBuilders.standaloneSetup(
                new ImageController(repository, signer, beans.getBeanProvider(MeterRegistry.class))).build();
    }

    private static Image image(Long id) {
        Image image = new Image();
        image.setId(id);
        image.setBytes(new byte[] {1, 2, 3});
        image.setSize(3L);
        image.setContentType("image/png");
        image.setOriginalFileName("photo.png");
        return image;
    }
}
