package com.hunt.otziv.common_billing.dto;

/**
 * A company of a common billing link.
 *
 * @param problem why the company's orders are not collected: the system switched the link off, or the
 *     collection keeps failing and will be retried; {@code null} when there is nothing to fix
 * @param separateOrdersNote which unfinished orders stay on their own invoices and what to do with
 *     them; {@code null} when every order may enter the common invoice. Filled only for the views of
 *     one link or one company, not for the list of all links
 */
public record CommonBillingCompanyResponse(
        Long companyId,
        String companyTitle,
        boolean enabled,
        String problem,
        String separateOrdersNote
) {
    public CommonBillingCompanyResponse(Long companyId, String companyTitle, boolean enabled) {
        this(companyId, companyTitle, enabled, null, null);
    }
}
