package com.hunt.otziv.common_billing.service;

import java.security.cert.CertPathBuilderException;
import java.security.cert.CertPathValidatorException;
import java.security.cert.CertificateException;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.Set;
import javax.net.ssl.SSLHandshakeException;
import org.springframework.web.client.RestClientResponseException;

/**
 * Classifies the narrow payment-init failure that is safe to retry because the
 * TLS certificate handshake failed before an HTTP response existed.
 */
public final class CommonPaymentInitFailureClassifier {

    public static final String TLS_BEFORE_HTTP_ERROR_CODE =
            "payment_init_tls_certificate_before_http";
    public static final String TLS_BEFORE_HTTP_REF_REASON =
            "tls_certificate_failure_before_http";
    public static final String LEGACY_TLS_BEFORE_HTTP_REF_REASON =
            "init_exception_before_response";

    private static final String EXACT_KNOWN_LEGACY_TLS_ERROR =
            "payment_init_exception: I/O error on POST request for "
                    + "\"https://securepay.tinkoff.ru/v2/Init\": (certificate_unknown) "
                    + "PKIX path building failed: "
                    + "sun.security.provider.certpath.SunCertPathBuilderException: "
                    + "unable to find valid certification path to requested target; "
                    + "проверьте банк вручную перед повторной оплатой";

    private CommonPaymentInitFailureClassifier() {
    }

    public static boolean isCertificateTlsFailureBeforeHttpResponse(Throwable failure) {
        if (failure == null) {
            return false;
        }
        boolean sslHandshake = false;
        boolean certificatePathFailure = false;
        Set<Throwable> visited = Collections.newSetFromMap(new IdentityHashMap<>());
        Throwable current = failure;
        while (current != null && visited.add(current)) {
            if (current instanceof RestClientResponseException) {
                return false;
            }
            if (current instanceof SSLHandshakeException) {
                sslHandshake = true;
            }
            if (current instanceof CertPathBuilderException
                    || current instanceof CertPathValidatorException
                    || current instanceof CertificateException) {
                certificatePathFailure = true;
            }
            current = current.getCause();
        }
        return sslHandshake && certificatePathFailure;
    }

    public static boolean isPersistedTlsBeforeHttpFailure(String error) {
        String normalized = normalize(error);
        return normalized.equals(TLS_BEFORE_HTTP_ERROR_CODE)
                || normalized.startsWith(TLS_BEFORE_HTTP_ERROR_CODE + ":")
                || isExactKnownLegacyTlsFailure(error);
    }

    public static boolean isExactKnownLegacyTlsFailure(String error) {
        return EXACT_KNOWN_LEGACY_TLS_ERROR.equals(normalize(error));
    }

    private static String normalize(String value) {
        return value == null ? "" : value.trim();
    }
}
