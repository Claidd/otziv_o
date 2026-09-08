# ADR-011: Owner API for next-order request reads and origin locking

Status: implemented first A02 seam; validation results belong to the coordinated correction run.

The owner of `NextOrderRequest` persistence and state is `orders` (`p_products`). Company boards and common billing previously queried its repository directly, interpreted its internal status enum and traversed request entities. The first A02 step replaces exactly four caller-to-repository edges with `p_products.api.NextOrderRequests`.

| Caller | Capability | Owner behavior and transaction contract |
| --- | --- | --- |
| `CompanyServiceImpl` | `companySummaries(companyIds)` | One scalar query for PENDING/FAILED requests; orders produces immutable counts, latest branch title and latest nonblank failure. The board retains its existing company authorization and pagination. REQUIRED read-only joins the caller transaction. |
| `CommonInvoiceDetailsAssembler` | `createdOrdersForSources(sourceOrderIds)` | One scalar projection query returns source/created IDs and company/branch/status titles in request creation order. Only requests with a created order are included; repeated successor IDs remain repeated rows. Billing keeps its own batch lookup of current invoice membership and ambiguity check. REQUIRED read-only. |
| `CommonInvoiceArchiveWorkflow` | `hasOpenRequest(sourceOrderId)` | Orders owns the PENDING/FAILED blocker predicate; returns one boolean. Existing invoice visibility checks, archive eligibility and transaction stay with billing. |
| `CommonInvoiceMembershipWorkflow` | `lockCreatedOrigin(createdOrderId)` | MANDATORY caller transaction. The caller first locks the canonical Order; orders then runs the existing request-row pessimistic query in request-ID order and returns whether a CREATED origin matches. Subsequent account/invoice locks, detachment and rollback remain in the same caller transaction. |

These are internal application capabilities, not HTTP endpoints or independent authorization grants. A new transport must authorize its own business scenario before calling them. The locking capability does not open a separate transaction and requires the caller's canonical Order lock; the public signature exposes no entity, repository, internal status enum or persistence projection type.

`NextOrderRequestAccessService` depends only on its owner's repository and state types. Public immutable records contain scalar IDs, counts and text. The repository's read projections select columns and do not JOIN FETCH entity graphs. The unused entity-fetch query for common-invoice successors is removed. Existing internal next-order automation APIs and company/order mutations outside this seam are unchanged.

This step removes four cross-module persistence allowances and twelve corresponding internal-type allowances. It adds no public package wildcard, no cross-module internal allowance and no new logical owner direction. Broader companies/orders and billing/orders entity dependencies remain work for later A02 steps; this seam is not a claim that all module cycles have been removed.

Validation: `CommonInvoiceDetailsAssemblerBatchTest` preserves one request query and one membership query for 1/10/50 successors, ordering, repeated IDs, missing membership and ambiguity behavior. `CommonBillingServiceTest` asserts Order → next-order origin → billing lock sequence. `CompanyServiceImplTest` uses the owner API. `NextOrderRequestAccessMySqlIntegrationTest` runs the actual Spring Data projection queries against MySQL, verifies detached scalar values with zero entity loads, and holds the request lock through a late outer rollback while an independent updater blocks. The test also rejects calling the locking capability without a transaction. `ModuleBoundaryTest` and `ModuleEncapsulationTest` enforce the reduced allowances.
