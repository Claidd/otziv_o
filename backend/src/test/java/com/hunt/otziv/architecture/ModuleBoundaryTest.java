package com.hunt.otziv.architecture;

import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import com.tngtech.archunit.core.importer.ImportOption;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.Properties;
import java.util.Set;
import java.util.TreeSet;
import java.util.stream.Collectors;
import org.junit.jupiter.api.Test;
import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;
import static org.assertj.core.api.Assertions.assertThat;

/** Bytecode rules: baseline is explicit migration debt, never an automatically updated allowance. */
class ModuleBoundaryTest {
    private static final String ROOT = "com.hunt.otziv.";
    private static final JavaClasses CLASSES = new ClassFileImporter()
            .withImportOption(ImportOption.Predefined.DO_NOT_INCLUDE_TESTS).importPackages("com.hunt.otziv");

    @Test
    void settlementCannotCallEitherFinancialFacade() {
        noClasses().that().haveFullyQualifiedName(ROOT + "common_billing.service.CommonInvoiceSettlementService")
                .should().dependOnClassesThat().haveFullyQualifiedName(ROOT + "common_billing.service.CommonBillingService")
                .orShould().dependOnClassesThat().haveFullyQualifiedName(ROOT + "payments.service.PaymentLinkService")
                .check(CLASSES);
        noClasses().that().haveSimpleName("PaymentBankObservationService")
                .or().haveSimpleName("CommonInvoiceRouteSelector")
                .or().haveSimpleName("CommonInvoicePresenter")
                .or().haveSimpleName("CommonInvoiceCancellationService")
                .or().haveSimpleName("CommonInvoiceInitializationService")
                .or().haveSimpleName("CommonInvoiceDetailsAssembler")
                .or().haveSimpleName("CommonInvoiceDeliveryService")
                .or().haveSimpleName("PaymentLinkPresenter")
                .or().haveSimpleName("PaymentLinkCancellationWorkflow")
                .or().haveSimpleName("PaymentLinkSettlementService")
                .or().haveSimpleName("PaymentLinkAmountPolicy")
                .or().haveSimpleName("ManualPaymentConfirmationWorkflow")
                .or().haveSimpleName("PaymentLinkLifecycleService")
                .or().haveSimpleName("BankInitializationStateService")
                .or().haveSimpleName("PaymentLinkPreparationWorkflow")
                .or().haveSimpleName("PublicPaymentLinkResolutionService")
                .or().haveSimpleName("PaymentLinkInitializationWorkflow")
                .or().haveSimpleName("ManualCardRoutePolicy")
                .or().haveSimpleName("BankObservationApplicationService")
                .or().haveSimpleName("ManualCardPaymentWorkflow")
                .or().haveSimpleName("OwnerManualCardApprovalWorkflow")
                .or().haveSimpleName("BankPaymentReconciliationWorkflow")
                .or().haveSimpleName("OrderPaymentLinkWorkflow")
                .or().haveSimpleName("PublicPaymentPageWorkflow")
                .or().haveSimpleName("PaymentRouteReplacementWorkflow")
                .or().haveSimpleName("PaymentLinkAdminBoardWorkflow")
                .or().haveSimpleName("CommonInvoiceTochkaReconciliationService")
                .or().haveSimpleName("CommonInvoiceManualPaymentWorkflow")
                .or().haveSimpleName("CommonInvoicePaymentRouteWorkflow")
                .or().haveSimpleName("CommonInvoiceMembershipWorkflow")
                .or().haveSimpleName("CommonBillingCompanyReconciliationWorkflow")
                .or().haveSimpleName("CommonBillingAccountWorkflow")
                .or().haveSimpleName("CommonInvoiceBoardWorkflow")
                .or().haveSimpleName("CommonInvoiceArchiveWorkflow")
                .or().haveSimpleName("CommonInvoiceReviewApprovalWorkflow")
                .or().haveSimpleName("CommonInvoiceRecoveryWorkflow")
                .or().haveSimpleName("CommonInvoiceCheckoutWorkflow")
                .or().haveSimpleName("CommonInvoiceDeletionWorkflow")
                .or().haveSimpleName("CommonInvoicePositionPaymentWorkflow")
                .should().dependOnClassesThat().haveFullyQualifiedName(ROOT + "payments.service.PaymentLinkService")
                .orShould().dependOnClassesThat().haveFullyQualifiedName(ROOT + "common_billing.service.CommonBillingService")
                .check(CLASSES);
        noClasses().that().haveFullyQualifiedName(ROOT + "payments.service.PaymentLinkService")
                .should().dependOnClassesThat().haveFullyQualifiedName(ROOT + "common_billing.service.CommonBillingService")
                .check(CLASSES);
        noClasses().that().haveFullyQualifiedName(ROOT + "common_billing.service.CommonBillingService")
                .should().dependOnClassesThat().haveFullyQualifiedName(ROOT + "payments.service.PaymentLinkService")
                .check(CLASSES);
    }

    @Test
    void extractedManagerWorkflowsCannotCallTheLegacyFacade() {
        noClasses().that().haveSimpleName("ManagerControlAccessPolicy")
                .or().haveSimpleName("ManagerControlCardLifecycle")
                .or().haveSimpleName("ManagerControlClientMessageText")
                .or().haveSimpleName("ManagerControlSlaPolicy")
                .or().haveSimpleName("ManagerControlConcretePresenter")
                .or().haveSimpleName("ManagerControlClientSendWorkflow")
                .or().haveSimpleName("ManagerControlProblemExamples")
                .or().haveSimpleName("ManagerControlConcreteSnapshotWorkflow")
                .or().haveSimpleName("ManagerControlDayLifecycle")
                .or().haveSimpleName("ManagerControlDailySnapshotWorkflow")
                .or().haveSimpleName("ManagerControlBoardWorkflow")
                .or().haveSimpleName("ManagerControlDayActions")
                .or().haveSimpleName("ManagerControlReminderWorkflow")
                .or().haveSimpleName("ManagerControlWorkerTaskWorkflow")
                .or().haveSimpleName("ManagerControlItemActions")
                .or().haveSimpleName("ManagerControlRepairOutcome")
                .or().haveSimpleName("ManagerControlChatRepairWorkflow")
                .or().haveSimpleName("ManagerControlAutomationRepairWorkflow")
                .or().haveSimpleName("ManagerControlRepairWorkflow")
                .or().haveSimpleName("ManagerControlClientReplyWorkflow")
                .or().haveSimpleName("ManagerControlClientConversationWorkflow")
                .should().dependOnClassesThat().haveFullyQualifiedName(ROOT + "manager_control.service.ManagerControlService")
                .check(CLASSES);
    }

    @Test
    void newApplicationAndInvoiceApiCannotDependOnTransportOrPersistenceImplementation() {
        noClasses().that().resideInAnyPackage("..p_products.application..", "..common_billing.api..", "..c_companies.api..", "..p_products.api..", "..whatsapp.api..", "..client_messages.api..")
                .should().dependOnClassesThat().resideInAnyPackage("..controller..", "org.springframework.web..", "jakarta.servlet..")
                .check(CLASSES);
        noClasses().that().resideInAnyPackage("..common_billing.api..", "..payments.api..", "..c_companies.api..", "..p_products.api..", "..whatsapp.api..", "..client_messages.api..")
                .should().dependOnClassesThat().resideInAnyPackage("..repository..", "..service..")
                .check(CLASSES);
    }

    @Test
    void companyPersistenceBoundaryCannotLoadTheCompanyOrFinancialWorkflows() {
        noClasses().that().haveSimpleName("CompanyRecordService")
                .or().haveSimpleName("CompanyStatisticsService")
                .should().dependOnClassesThat().haveFullyQualifiedName(ROOT + "c_companies.service.CompanyService")
                .orShould().dependOnClassesThat().haveFullyQualifiedName(ROOT + "c_companies.service.CompanyServiceImpl")
                .orShould().dependOnClassesThat().resideInAnyPackage(
                        "..p_products..service..", "..r_review..service..", "..payments..service..",
                        "..common_billing..service..", "..z_zp..service..")
                .check(CLASSES);
        noClasses().that().haveSimpleName("OrderTransactionServiceImpl")
                .or().haveSimpleName("NextOrderRequestService")
                .or().haveSimpleName("OrderCompanyStatusService")
                .or().haveSimpleName("OrderPaymentCancellationService")
                .or().haveSimpleName("PaymentReturnOrderRecoveryService")
                .or().haveSimpleName("PaymentCheckServiceImpl")
                .should().dependOnClassesThat().haveFullyQualifiedName(ROOT + "c_companies.service.CompanyService")
                .orShould().dependOnClassesThat().haveFullyQualifiedName(ROOT + "c_companies.service.CompanyServiceImpl")
                .check(CLASSES);
    }

    @Test
    void crossModulePersistenceAccessCannotGrow() throws Exception {
        Properties ownership = new Properties();
        try (var stream = Files.newInputStream(Path.of("src/test/resources/architecture/module-owners.properties"))) {
            ownership.load(stream);
        }
        Set<String> actual = new TreeSet<>();
        Set<String> unmapped = new TreeSet<>();
        for (var source : CLASSES) {
            if (!source.getName().startsWith(ROOT)) continue;
            String owner = owner(source.getName(), ownership, unmapped);
            for (var dependency : source.getDirectDependenciesFromSelf()) {
                String target = dependency.getTargetClass().getName();
                if (!target.startsWith(ROOT) || !target.contains(".repository.")) continue;
                String targetOwner = owner(target, ownership, unmapped);
                if (!owner.equals(targetOwner)) actual.add(source.getName().split("\\$")[0] + " -> " + target.split("\\$")[0]);
            }
        }
        assertThat(unmapped).as("Every package needs an explicit data owner").isEmpty();
        Path observed = Path.of("target/architecture/cross-module-persistence-observed.txt");
        Files.createDirectories(observed.getParent());
        Files.write(observed, actual);
        Path baseline = Path.of("src/test/resources/architecture/cross-module-persistence-baseline.txt");
        Set<String> allowed = Files.readAllLines(baseline).stream().map(String::trim)
                .filter(line -> !line.isBlank() && !line.startsWith("#")).collect(Collectors.toSet());
        actual.removeAll(allowed);
        assertThat(actual).as("New data-owner bypasses; use the owner's application API. See ADR-001 and target/architecture report").isEmpty();
    }

    private static String owner(String type, Properties ownership, Set<String> unmapped) {
        String relative = type.substring(ROOT.length());
        if (!relative.contains(".")) return "platform";
        String prefix = relative.substring(0, relative.indexOf('.'));
        String owner = ownership.getProperty(prefix);
        if (owner == null) unmapped.add(prefix);
        return owner == null ? prefix : owner;
    }
}
