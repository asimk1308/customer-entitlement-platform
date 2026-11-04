package com.example.platform.api;

import com.example.platform.security.Principal;
import com.example.platform.security.RequiresScope;
import com.example.platform.upstream.Models.Entitlement;
import com.example.platform.upstream.Models.EntitlementView;
import com.example.platform.upstream.Models.Issue;
import com.example.platform.upstream.Models.Usage;
import com.example.platform.upstream.Sources;
import com.example.platform.upstream.UpstreamFailure;
import com.example.platform.web.ApiProblem;
import com.example.platform.web.ApiProblem.Code;
import com.example.platform.web.Concurrent;
import jakarta.validation.constraints.Pattern;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RestController;

@RestController
class CustomerController {
    private static final Logger LOG = LoggerFactory.getLogger(CustomerController.class);

    record UsageBody(String month, double protectedTb, long apiCalls, long activeUsers) {}

    record CustomerBody(String id, Views.EntitlementBody entitlement, UsageBody usage, boolean degraded,
                        List<Views.DegradedSource> degradedSources, List<String> redactedFields, List<Issue> dataQuality) {}

    private record UsageResult(Usage usage, String failureReason) {}

    private record EntitlementResult(EntitlementView view, UpstreamFailure failure) {}

    private final Sources sources;

    CustomerController(Sources sources) {
        this.sources = sources;
    }

    @RequiresScope("customers:read")
    @GetMapping("/v1/customers/{customerId}")
    ResponseEntity<CustomerBody> getCustomer(
            Principal principal,
            @PathVariable @Pattern(regexp = "ACC-[0-9]{1,10}", message = "Must look like ACC-007.") String customerId) {
        // Checked first, so a restricted caller can't learn anything from timing or degraded states.
        if (!principal.canSeeAccount(customerId)) {
            throw ApiProblem.of(Code.NOT_FOUND);
        }

        // Concurrent, so the response time follows the slower source rather than the sum of both.
        CompletableFuture<EntitlementResult> entitlements = Concurrent.supply(this::loadEntitlements);
        CompletableFuture<UsageResult> usage = Concurrent.supply(() -> latestUsage(customerId));
        EntitlementResult loaded = Concurrent.await(entitlements);
        UsageResult usageResult = Concurrent.await(usage);

        if (loaded.failure() != null) {
            throw Views.unavailable(loaded.failure(),
                    "The entitlements system is unavailable and no snapshot exists for this customer.");
        }
        EntitlementView view = loaded.view();
        Entitlement entitlement = view.entitlements().get(customerId);
        if (entitlement == null) {
            // With stale data, "not in the snapshot" doesn't prove the customer doesn't exist.
            if (view.degradedReason() != null) {
                throw Views.unavailable(new UpstreamFailure("entitlements", view.degradedReason()),
                        "The entitlements system is unavailable and this customer isn't in the snapshot.");
            }
            throw ApiProblem.of(Code.NOT_FOUND);
        }

        List<Views.DegradedSource> degraded = new ArrayList<>();
        if (view.degradedReason() != null) {
            degraded.add(Views.degraded("entitlements", view.degradedReason(), view.asOf()));
        }
        if (usageResult.failureReason() != null) {
            degraded.add(Views.degraded("usage", usageResult.failureReason(), null));
        }

        Views.EntitlementResult body = Views.entitlement(entitlement, view.asOf(), principal);
        Usage latest = usageResult.usage();
        return Views.json(200)
                // Content depends on the caller's scopes, so a shared cache must never store it. A
                // degraded response must not outlive the outage in any cache.
                .header("Cache-Control", degraded.isEmpty() ? "private" : "no-store")
                .header("Vary", "Authorization")
                .body(new CustomerBody(
                        customerId,
                        body.body(),
                        latest == null ? null : new UsageBody(latest.month(), latest.protectedTb(), latest.apiCalls(), latest.activeUsers()),
                        !degraded.isEmpty(),
                        degraded,
                        body.redacted(),
                        entitlement.issues()));
    }

    private EntitlementResult loadEntitlements() {
        try {
            return new EntitlementResult(sources.loadEntitlements(), null);
        } catch (UpstreamFailure failure) {
            return new EntitlementResult(null, failure);
        }
    }

    /** Usage is optional in this view, so a failure degrades the response and doesn't fail it. */
    private UsageResult latestUsage(String accountId) {
        try {
            return new UsageResult(sources.latestUsage(accountId), null);
        } catch (UpstreamFailure failure) {
            LOG.atWarn().addKeyValue("reason", failure.reason).log("usage_unavailable");
            return new UsageResult(null, failure.reason);
        }
    }
}
