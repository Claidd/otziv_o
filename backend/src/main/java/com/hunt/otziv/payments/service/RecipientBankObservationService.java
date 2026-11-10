package com.hunt.otziv.payments.service;

import com.hunt.otziv.payments.api.UnpaidBankObservation;
import com.hunt.otziv.payments.model.PaymentProfile;
import com.hunt.otziv.payments.tochka.service.TochkaClient;
import com.hunt.otziv.payments.tochka.service.TochkaPaymentOperationMapper;
import com.hunt.otziv.payments.tochka.service.TochkaPaymentProfileResolver;
import java.util.Objects;
import java.util.Set;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

@Service
@RequiredArgsConstructor
public class RecipientBankObservationService implements UnpaidBankObservation {
    private final PaymentProfileService profiles;
    private final TbankRuntimeSettingsService settings;
    private final TbankClient tbank;
    private final TochkaClient tochka;
    private final TochkaPaymentProfileResolver tochkaProfiles;
    private final TochkaPaymentOperationMapper mapper;

    @Override
    public Result observe(Source s) {
        var entity = profiles.findById(s.profileId()).orElseThrow();
        if (PaymentProfile.PROVIDER_TOCHKA.equals(s.provider()) && profiles.isTochkaProvider(entity)) {
            var profile = tochkaProfiles.resolveForExistingPayment(entity);
            if (!profile.merchantId().equals(s.merchant()) || (s.test() != null && s.test() != profile.testMode()))
                throw new IllegalStateException("Профиль Точки изменился");
            var response = tochka.getPaymentInfo(profile, s.operation());
            var operations = response.data().operations();
            if (operations == null || operations.size() != 1) throw new IllegalStateException("Неоднозначная операция Точки");
            var payment = operations.getFirst();
            var mode = SettledBankPaymentObservationService.observedTochkaPaymentMode(payment.paymentType(), payment.status(),
                    SettledBankPaymentObservationService.parseTochkaPaymentModes(s.modes()));
            var mapped = mapper.map(payment, new TochkaPaymentOperationMapper.ExpectedPayment(s.operation(), s.order(),
                    profile.customerCode(), s.merchant(), s.amount(), mode), false);
            // Acquiring exposes observation and refund of money already received. CREATED is still payable.
            if ("EXPIRED".equals(mapped.providerStatus()) && ((payment.paidAt() != null && !payment.paidAt().isBlank())
                    || (payment.orders() != null && !payment.orders().isEmpty()))) return new Result(Outcome.FINANCIAL,"HISTORY");
            return new Result(classify(mapped.providerStatus()).outcome(),mapped.providerStatus(),
                    mapped.status(), mapped.paymentMethod(), mapped.providerPaymentType());
        }
        if (!PaymentProfile.PROVIDER_TBANK.equals(s.provider()) || !settings.isTbankEnabled())
            return new Result(Outcome.WAITING, "UNAVAILABLE");
        var profile = profiles.toRuntimeForTerminal(entity, s.merchant());
        if (!profile.terminalKey().equals(s.merchant()) || (s.test() != null && s.test() != profile.testMode()))
            throw new IllegalStateException("Терминал Т-Банка изменился");
        var state = tbank.getState(profile, s.operation());
        if (!state.success() || !"0".equals(state.errorCode()) || !s.merchant().equals(state.terminalKey())
                || !s.operation().equals(state.paymentId()) || !s.order().equals(state.orderId()))
            throw new IllegalStateException("Ответ Т-Банка не совпал с операцией");
        Result result = classify(state.status());
        boolean unpaidClosure = result.outcome() == Outcome.CLOSED && Objects.equals(0L, state.amount());
        boolean returnedMoney = result.outcome() == Outcome.FINANCIAL && !"CONFIRMED".equals(state.status())
                && state.amount() != null && state.amount() >= 0 && state.amount() <= s.amount();
        if (!Objects.equals(s.amount(), state.amount()) && !unpaidClosure && !returnedMoney)
            throw new IllegalStateException("Сумма ответа Т-Банка не совпала с операцией");
        // Cancel can refund a payment completed between two API calls. Only observe the original operation.
        return result;
    }

    static Result classify(String status) {
        if (status == null) return new Result(Outcome.WAITING, "UNKNOWN");
        if (Set.of("CANCELED","EXPIRED","DEADLINE_EXPIRED","REJECTED").contains(status)) return new Result(Outcome.CLOSED,status);
        if (Set.of("CONFIRMED","APPROVED","REFUNDED","REVERSED","PARTIAL_REFUNDED","PARTIAL_REVERSED",
                "REFUNDED_PARTIALLY","ON-REFUND","REFUNDING","REVERSING").contains(status)) return new Result(Outcome.FINANCIAL,status);
        return new Result(Outcome.WAITING,status);
    }
}
