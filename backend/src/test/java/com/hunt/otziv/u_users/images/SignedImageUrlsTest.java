package com.hunt.otziv.u_users.images;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.hunt.otziv.admin.dto.personal_stat.UserLKDTO;
import com.hunt.otziv.admin.dto.personal_stat.UserStatDTO;
import com.hunt.otziv.u_users.dto.AdminUserResponse;
import java.net.URI;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;

class SignedImageUrlsTest {

    private static final String SIGNING_MATERIAL = "test-image-signing-material-0123456789abcdef";
    private static final Pattern LINK = Pattern.compile("/images/(\\d+)\\?exp=(\\d+)&sig=([A-Za-z0-9_-]{43})");
    private static final Instant NOW = Instant.parse("2026-11-04T10:07:00Z");

    private SignedImageUrls published;

    @AfterEach
    void unpublish() {
        if (published != null) {
            published.unpublish();
        }
    }

    @Test
    void issuedLinkIsAcceptedUntilItsRoundedExpiry() {
        SignedImageUrls signer = signer(true, NOW);
        Link link = parse(signer.signedPath(42));

        assertThat(link.id()).isEqualTo(42);
        // 10:07 + 1h rounded up to the next quarter hour.
        assertThat(link.expiry()).isEqualTo(Instant.parse("2026-11-04T11:15:00Z").getEpochSecond());
        SignedImageUrls.Access access = signer.authorize(42, link.exp(), link.sig());
        assertThat(access.kind()).isEqualTo(SignedImageUrls.AccessKind.SIGNED);
        assertThat(signer.browserCacheFor(access)).isEqualTo(Duration.ofMinutes(68));

        SignedImageUrls later = signer(false, Instant.parse("2026-11-04T11:14:59Z"));
        assertThat(later.authorize(42, link.exp(), link.sig()).kind()).isEqualTo(SignedImageUrls.AccessKind.SIGNED);
        SignedImageUrls expired = signer(false, Instant.parse("2026-11-04T11:15:00Z"));
        assertThat(expired.authorize(42, link.exp(), link.sig()).kind()).isEqualTo(SignedImageUrls.AccessKind.DENIED);
    }

    @Test
    void linkStaysIdenticalInsideOneBucketSoBrowsersReuseTheCachedPhoto() {
        String first = signer(true, NOW).signedPath(7);
        String sameBucket = signer(true, NOW.plus(Duration.ofMinutes(7))).signedPath(7);
        String nextBucket = signer(true, NOW.plus(Duration.ofMinutes(9))).signedPath(7);

        assertThat(sameBucket).isEqualTo(first);
        assertThat(nextBucket).isNotEqualTo(first);
    }

    @Test
    void strictModeRejectsGuessedTamperedAndForeignLinks() {
        SignedImageUrls strict = signer(false, NOW);
        Link link = parse(strict.signedPath(42));

        assertThat(strict.authorize(42, null, null).kind()).isEqualTo(SignedImageUrls.AccessKind.DENIED);
        assertThat(strict.authorize(43, link.exp(), link.sig()).kind()).isEqualTo(SignedImageUrls.AccessKind.DENIED);
        assertThat(strict.authorize(42, String.valueOf(link.expiry() + 900), link.sig()).kind())
                .isEqualTo(SignedImageUrls.AccessKind.DENIED);
        String flipped = (link.sig().charAt(0) == 'A' ? "B" : "A") + link.sig().substring(1);
        assertThat(strict.authorize(42, link.exp(), flipped).kind()).isEqualTo(SignedImageUrls.AccessKind.DENIED);
        assertThat(strict.authorize(42, link.exp(), null).kind()).isEqualTo(SignedImageUrls.AccessKind.DENIED);
        assertThat(strict.authorize(42, "-1", link.sig()).kind()).isEqualTo(SignedImageUrls.AccessKind.DENIED);

        SignedImageUrls otherKey = new SignedImageUrls(SIGNING_MATERIAL.replace('t', 'x'), Duration.ofHours(1), false,
                Clock.fixed(NOW, ZoneOffset.UTC));
        assertThat(otherKey.authorize(42, link.exp(), link.sig()).kind()).isEqualTo(SignedImageUrls.AccessKind.DENIED);
    }

    @Test
    void validSignatureFarBeyondTheConfiguredLifetimeIsRejected() {
        SignedImageUrls longLived = new SignedImageUrls(SIGNING_MATERIAL, Duration.ofHours(12), false, Clock.fixed(NOW, ZoneOffset.UTC));
        Link link = parse(longLived.signedPath(42));

        assertThat(signer(false, NOW).authorize(42, link.exp(), link.sig()).kind())
                .isEqualTo(SignedImageUrls.AccessKind.DENIED);
    }

    @Test
    void transitionModeStillServesOldClientsAndStaleLinks() {
        SignedImageUrls transition = signer(true, NOW);
        Link link = parse(transition.signedPath(42));

        assertThat(transition.authorize(42, null, null).kind()).isEqualTo(SignedImageUrls.AccessKind.UNSIGNED);
        assertThat(transition.authorize(43, link.exp(), link.sig()).kind()).isEqualTo(SignedImageUrls.AccessKind.UNSIGNED);
        assertThat(transition.browserCacheFor(transition.authorize(42, null, null))).isEqualTo(Duration.ofDays(1));
    }

    @Test
    void configurationRejectsWeakSecretsAndUnboundedLifetimes() {
        assertThatThrownBy(() -> new SignedImageUrls("short", Duration.ofHours(1), false, Clock.systemUTC()))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new SignedImageUrls(SIGNING_MATERIAL, Duration.ofDays(2), false, Clock.systemUTC()))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> signer(false, NOW).signedPath(0)).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void withoutConfiguredSecretLinksStillVerifyInsideTheSameProcess() {
        SignedImageUrls first = new SignedImageUrls("", Duration.ofHours(1), false, Clock.fixed(NOW, ZoneOffset.UTC));
        SignedImageUrls second = new SignedImageUrls(" ", Duration.ofHours(1), false, Clock.fixed(NOW, ZoneOffset.UTC));
        Link link = parse(first.signedPath(5));

        assertThat(second.authorize(5, link.exp(), link.sig()).kind()).isEqualTo(SignedImageUrls.AccessKind.SIGNED);
    }

    @Test
    void dtoJsonCarriesSignedLinkNextToTheLegacyIdAndIgnoresItWhenRead() throws Exception {
        published = new SignedImageUrls(SIGNING_MATERIAL, Duration.ofHours(1), false, Clock.systemUTC());
        published.publish();
        JsonMapper mapper = JsonMapper.builder().build();

        Map<?, ?> profile = mapper.readValue(mapper.writeValueAsString(UserLKDTO.builder().image(9L).build()), Map.class);
        assertThat(profile.get("image")).isEqualTo(9);
        assertThat(parse((String) profile.get("imageUrl")).id()).isEqualTo(9);

        Map<?, ?> noPhoto = mapper.readValue(mapper.writeValueAsString(UserStatDTO.builder().build()), Map.class);
        assertThat(parse((String) noPhoto.get("imageUrl")).id()).isEqualTo(SignedImageUrls.DEFAULT_IMAGE_ID);

        Map<?, ?> adminUser = mapper.readValue(mapper.writeValueAsString(AdminUserResponse.builder().id(3L).build()), Map.class);
        assertThat(adminUser.containsKey("imageUrl")).isTrue();
        assertThat(adminUser.get("imageUrl")).isNull();

        UserStatDTO roundTrip = new com.fasterxml.jackson.databind.ObjectMapper().readValue(
                "{\"imageId\":4,\"imageUrl\":\"/images/4?exp=1&sig=x\"}", UserStatDTO.class);
        assertThat(roundTrip.getImageId()).isEqualTo(4L);
    }

    @Test
    void staticAccessorIsSilentWithoutARunningSigner() {
        assertThat(SignedImageUrls.pathFor(5L)).isNull();
        assertThat(SignedImageUrls.pathForOrDefault(null)).isNull();
    }

    private static SignedImageUrls signer(boolean unsignedAccess, Instant now) {
        return new SignedImageUrls(SIGNING_MATERIAL, Duration.ofHours(1), unsignedAccess, Clock.fixed(now, ZoneOffset.UTC));
    }

    private static Link parse(String path) {
        assertThat(URI.create(path).isAbsolute()).isFalse();
        Matcher matcher = LINK.matcher(path);
        assertThat(matcher.matches()).as(path).isTrue();
        return new Link(Long.parseLong(matcher.group(1)), matcher.group(2), matcher.group(3));
    }

    private record Link(long id, String exp, String sig) {
        long expiry() {
            return Long.parseLong(exp);
        }
    }
}
