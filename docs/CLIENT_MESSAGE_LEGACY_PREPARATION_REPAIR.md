# Legacy order notification preparation

The September 11 production diagnosis found orders whose `client_message_generation`
remained zero after new status transitions. The occurrence store correctly rejected
unverified legacy identities before allocating an operation or contacting a channel.
The caller nevertheless classified the preparation exception as an uncertain external
send. Both the scheduled recovery guard and the manager's repair button then refused
to retry. Publication-start notifications used the same reservation path in WhatsApp,
MAX and Telegram and could be lost when the status transaction committed without them.

## New business events

A validated status transition advances the message generation, including zero to one.
A no-op status request cannot establish a lineage. Completion of publication also
advances it when the order actually enters the published status. Existing unconfirmed
occurrences continue to reuse their operation identity; the generation change does
not authorize replay of an uncertain operation.

Publication-start notifications now commit an immutable intent and operation identity
with the order transition in `order_publication_client_updates`. These notification-only
rows have completion and billing already marked done: delivering a start notification
cannot complete an order or issue an invoice. Channel calls run outside the business
transaction. A known pre-send refusal returns the same operation to the queue; an
uncertain result enters receipt-only recovery. The start notification follows the
immediate-messages setting independently of optional per-review progress reports.

## Recovery of blocked preparation

`LegacyOrderMessagePreparationRecovery` is invoked by the normal worker and the
manager's repair action. It does not contact a provider. It locks Order before State
and requires all of the following before returning a held action to the normal queue:

- The scenario is a review-check delivery or final-invoice retry, still in its exact
  current order status cycle.
- That cycle began after successful migration `1.10.305`, and the state was created
  after the status change. No fallback date is accepted as proof.
- The order generation is zero and no order notification occurrence exists.
- There is no envelope, delivery token, prepared timestamp, channel, task, message,
  successful delivery, or active processing lease.
- Retained attempts prove only the specific pre-provider legacy preparation failure.
  Missing history, ambiguous attempts and any provider evidence fail closed.

The recovered generation, queue state and recovery audit commit together. The bounded
worker scan checks up to 20 candidates per pass, with a ten-minute cooldown for held
rows. It does not clear unrelated uncertain sends. A genuinely unverified historical
order remains held; the UI explains that evidence is required instead of promising
an unconditional retry.

## Validation and release

Tests cover real MySQL migrations, both affected scenarios, concurrent repair,
rollback, missing or contradictory evidence, channel-independent delivery, disabled
progress preferences and unchanged operation identities across a retry. The existing
generation-zero reservation test remains in force.

This source change requires a new candidate image and CI run. Green checks for
`28544cbf` do not cover it. Historical failed CI runs retain their original result.
Production data diagnosis artifacts remain outside the repository; restoring old
publication-start notifications is a separate data-recovery decision, not an automatic
replay performed by this change.
