package com.hunt.otziv.l_lead.service;

import com.hunt.otziv.config.jwt.service.JwtService;
import com.hunt.otziv.config.jwt.service.LeadIntegrationHeaders;
import com.hunt.otziv.l_lead.repository.LeadCommandRepository;
import java.time.Duration;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.*;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClientResponseException;
import org.springframework.web.client.RestTemplate;

@Service
public class LeadCommandWorker {
    private final LeadCommandRepository repository;
    private final LeadCommandCodec codec;
    private final JwtService jwtService;
    private final RestTemplate transport;
    @Value("${lead.commands.dispatch-enabled:false}") private boolean enabled;
    @Value("${lead.vps.url}") private String syncUrl;
    @Value("${lead.update.url}") private String updateUrl;
    @Value("${lead.transfer.url}") private String importUrl;
    @Value("${lead.commands.producer-source-id:}") private String activatedSourceId;
    @Value("${lead.vps.retry.maxAttempts:20}") private int maxAttempts;
    @Value("${lead.vps.retry.batchSize:100}") private int batchSize;

    @Autowired
    public LeadCommandWorker(LeadCommandRepository repository, LeadCommandCodec codec, JwtService jwtService) {
        this(repository, codec, jwtService, boundedTransport());
    }

    LeadCommandWorker(LeadCommandRepository repository, LeadCommandCodec codec, JwtService jwtService, RestTemplate transport) {
        this.repository = repository;
        this.codec = codec;
        this.jwtService = jwtService;
        this.transport = transport;
    }

    private static RestTemplate boundedTransport() {
        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(Duration.ofSeconds(5));
        factory.setReadTimeout(Duration.ofSeconds(30));
        return new RestTemplate(factory);
    }

    @Scheduled(fixedDelayString = "${lead.commands.poll-delay-ms:5000}")
    public void dispatch() {
        if (!enabled) return;
        if (activatedSourceId == null || activatedSourceId.isBlank() || !activatedSourceId.equals(repository.sourceIdentity())) {
            throw new IllegalStateException("Lead sender requires an explicitly activated persisted source identity; clones must remain disabled");
        }
        if (maxAttempts < 1 || maxAttempts > 100 || batchSize < 1 || batchSize > 100) {
            throw new IllegalStateException("Lead command dispatch limits are invalid");
        }
        repository.recoverExpired(maxAttempts);
        for (int i = 0; i < batchSize; i++) {
            var claim = repository.claim(maxAttempts);
            if (claim.isEmpty()) break;
            deliver(claim.get());
        }
    }

    void deliver(LeadCommandRepository.Claim claim) {
        Object payload;
        try {
            payload = codec.decode(claim.kind(), claim.version(), claim.json());
            var identity=LeadCommandProtocol.identity(payload);
            if(identity==null || identity.protocolVersion()!=1 || !identity.operationId().equals(claim.commandId())
                    || !identity.kind().equals(claim.kind()) || !identity.sourceId().equals(claim.sourceId())
                    || !java.util.Objects.equals(identity.entityVersion(),claim.entityVersion())
                    || !jwtService.generateChecksum(payload).equals(claim.hash())) {
                throw new IllegalArgumentException("LEAD_LEGACY_OR_IDENTITY_UNVERIFIED");
            }
        } catch (IllegalArgumentException error) {
            repository.complete(claim, "QUARANTINED", error.getMessage(), 0);
            return;
        }
        String scope = "POST:/api/leads/"+claim.kind().toLowerCase(java.util.Locale.ROOT);
        String target = switch(claim.kind()) {case "UPDATE" -> updateUrl;case "IMPORT" -> importUrl;default -> syncUrl;};
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        headers.set("Idempotency-Key", claim.commandId());
        String token = "IMPORT".equals(claim.kind()) ? jwtService.generateToken((com.hunt.otziv.l_lead.dto.LeadDtoTransfer)payload)
                : jwtService.generateSyncToken(scope, payload);
        headers.set(LeadIntegrationHeaders.TOKEN, token);
        headers.setBearerAuth(token);
        try {
            var response = transport.exchange(target, HttpMethod.POST, new HttpEntity<>(payload, headers), String.class);
            boolean confirmed=response.getStatusCode().is2xxSuccessful()
                    && codec.receipt(response.getBody()).matches(LeadCommandProtocol.identity(payload),claim.hash());
            repository.complete(claim,confirmed ? "SUCCEEDED" : "UNKNOWN",confirmed ? null : "UNCONFIRMED_HTTP_RECEIPT",0);
        } catch (RestClientResponseException error) {
            int code = error.getStatusCode().value();
            // Explicit admission rejection is retryable. A 5xx may follow a commit.
            String state = code == 429 && claim.attempt() < maxAttempts ? "READY"
                    : code >= 400 && code < 500 && code != 408 ? "DEAD" : "UNKNOWN";
            repository.complete(claim, state, "HTTP_" + code,
                    (int) Math.min(1800L, 30L << Math.min(6, claim.attempt() - 1)));
        } catch (RuntimeException error) {
            repository.complete(claim, "UNKNOWN", "TRANSPORT_OUTCOME_UNKNOWN", 0);
        }
    }
}
