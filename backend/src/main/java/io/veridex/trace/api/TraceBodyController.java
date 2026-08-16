package io.veridex.trace.api;

import io.veridex.iam.api.CurrentActor;
import io.veridex.shared.infrastructure.RequestIds;
import io.veridex.trace.application.TraceBodyReadService;
import jakarta.servlet.http.HttpServletRequest;
import java.util.UUID;
import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class TraceBodyController {
    private final TraceBodyReadService service;

    public TraceBodyController(TraceBodyReadService service) {
        this.service = service;
    }

    @GetMapping("/api/traces/{runId}/body")
    public ResponseEntity<TraceBodyResponse> read(@PathVariable UUID runId,
                                                   @RequestHeader(value = "X-Trace-Access-Reason", required = false) String reason,
                                                   HttpServletRequest request) {
        TraceBodyResponse response = service.read(runId, CurrentActor.id(), reason, RequestIds.current(request));
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(response);
    }
}
