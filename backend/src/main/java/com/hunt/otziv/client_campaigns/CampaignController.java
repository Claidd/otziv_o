package com.hunt.otziv.client_campaigns;

import static com.hunt.otziv.client_campaigns.CampaignModels.*;
import jakarta.validation.Valid;
import java.nio.charset.StandardCharsets;
import java.security.Principal;
import java.util.List;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.http.*;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.server.ResponseStatusException;

@RestController
@RequiredArgsConstructor
public class CampaignController {
    private final CampaignStore store;
    private final CampaignService service;

    @GetMapping("/api/admin/client-offers")
    @PreAuthorize("hasAnyRole('ADMIN','OWNER')")
    public Board board() { return service.board(); }

    @PostMapping("/api/admin/client-offers/preview")
    @PreAuthorize("hasAnyRole('ADMIN','OWNER')")
    public List<AudienceCount> preview(@Valid @RequestBody Settings settings) { return store.preview(settings); }

    @PutMapping(value="/api/admin/client-offers/{id}",consumes=MediaType.MULTIPART_FORM_DATA_VALUE)
    @PreAuthorize("hasAnyRole('ADMIN','OWNER')")
    public Campaign save(@PathVariable UUID id, @Valid @RequestPart("settings") Settings settings,
            @RequestPart(value="file",required=false) MultipartFile file,
            @RequestParam(defaultValue="false") boolean removeFile, Principal actor) {
        return store.save(id.toString(),settings,CampaignFileGuard.read(file),removeFile,actor.getName(),service.now());
    }

    @PostMapping("/api/admin/client-offers/{id}/{action}")
    @PreAuthorize("hasAnyRole('ADMIN','OWNER')")
    public Board action(@PathVariable UUID id,@PathVariable String action) {
        if ("start".equals(action)) store.start(id.toString(),service.now());
        else store.action(id.toString(),action,service.now());
        return service.board();
    }

    @GetMapping("/api/admin/client-offers/{id}/recipients")
    @PreAuthorize("hasAnyRole('ADMIN','OWNER')")
    public List<Recipient> recipients(@PathVariable UUID id,@RequestParam(defaultValue="0") int page) {
        store.get(id.toString());
        return store.recipients(id.toString(),page);
    }

    @GetMapping("/api/admin/client-offers/{id}/file")
    @PreAuthorize("hasAnyRole('ADMIN','OWNER')")
    public ResponseEntity<byte[]> file(@PathVariable UUID id) { return download(store.attachment(id.toString())); }

    @GetMapping("/api/public/client-offer-files/{token}")
    public ResponseEntity<byte[]> publicFile(@PathVariable UUID token) { return download(store.publicAttachment(token.toString())); }

    private ResponseEntity<byte[]> download(Attachment file) {
        if (file == null) throw new ResponseStatusException(HttpStatus.NOT_FOUND);
        return ResponseEntity.ok().contentType(MediaType.APPLICATION_OCTET_STREAM)
                .header(HttpHeaders.CONTENT_DISPOSITION,ContentDisposition.attachment().filename(file.name(),StandardCharsets.UTF_8).build().toString())
                .header("X-Content-Type-Options","nosniff").header("Content-Security-Policy","sandbox")
                .cacheControl(CacheControl.noStore()).contentLength(file.bytes().length).body(file.bytes());
    }
}
