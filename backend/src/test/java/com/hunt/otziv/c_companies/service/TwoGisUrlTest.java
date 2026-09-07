package com.hunt.otziv.c_companies.service;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import static org.assertj.core.api.Assertions.*;

class TwoGisUrlTest {
    @ParameterizedTest
    @ValueSource(strings = {
            "https://2gis.ru/magnitogorsk/geo/3659702978432070",
            " https://2gis.ru/magnitogorsk/geo/3659702978432070/ ",
            "https://2gis.ru/magnitogorsk/firm/3659702978432070?m=1%2C2",
            "http://2gis.kz/firm/3659702978432070/tab/reviews#reviews",
            "https://2gis.ru/magnitogorsk/search/climate/firm/3659702978432070/tab/reviews",
            "https://2gis.ru/magnitogorsk/inside/123456/firm/3659702978432070",
            "https://2gis.ru/magnitogorsk/branches/123456/firm/3659702978432070",
            "https://2gis.ru/reviews/3659702978432070/addReview?utm_source=lk"
    })
    void cardVariantsHaveOneIdentity(String url) {
        assertThat(TwoGisUrl.cardId(TwoGisUrl.parse(url))).contains("3659702978432070");
    }

    @ParameterizedTest
    @ValueSource(strings = {"https://2gis.ru.evil.test/firm/12345", "https://2gis.ru@localhost/firm/12345",
            "http://127.0.0.1/firm/12345", "file:///firm/12345", "https://2gis.ru:8443/firm/12345"})
    void unsafeRedirectTargetsAreRejected(String url) {
        assertThatThrownBy(() -> TwoGisUrl.parse(url)).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void shortKeyPreservesCaseAndIgnoresTracking() {
        assertThat(TwoGisUrl.shortKey(TwoGisUrl.parse("https://go.2gis.com/h8eeD/?utm=1")))
                .isEqualTo("https://go.2gis.com/h8eeD");
        assertThat(TwoGisUrl.cardId(TwoGisUrl.parse("https://2gis.ru/search/12345"))).isEmpty();
    }
}
