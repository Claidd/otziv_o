package com.hunt.otziv.workload_shadow.transfer.dto;

import com.hunt.otziv.workload_shadow.transfer.dto.WorkloadTransferCompanyGraph.BadTaskNode;
import com.hunt.otziv.workload_shadow.transfer.dto.WorkloadTransferCompanyGraph.OrderNode;
import com.hunt.otziv.workload_shadow.transfer.dto.WorkloadTransferCompanyGraph.RecoveryTaskNode;
import com.hunt.otziv.workload_shadow.transfer.dto.WorkloadTransferCompanyGraph.ReviewNode;
import com.hunt.otziv.workload_shadow.transfer.dto.WorkloadTransferCompanyGraph.Warning;
import com.hunt.otziv.workload_shadow.transfer.dto.WorkloadTransferCompanyGraph.WarningCode;
import com.hunt.otziv.workload_shadow.transfer.dto.WorkloadTransferCompanyGraph.WarningSeverity;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * Compact, stable diagnostic summary of all nodes in a company transfer graph.
 */
public record WorkloadTransferGraphDiagnostics(
        int warningCount,
        int errorCount,
        List<WarningCode> warningCodes,
        List<WarningCode> errorCodes
) {

    public WorkloadTransferGraphDiagnostics {
        warningCodes = List.copyOf(warningCodes);
        errorCodes = List.copyOf(errorCodes);
    }

    public static WorkloadTransferGraphDiagnostics from(WorkloadTransferCompanyGraph graph) {
        List<Warning> all = new ArrayList<>(graph.warnings());
        for (OrderNode order : graph.orders()) {
            all.addAll(order.warnings());
            order.reviews().stream().map(ReviewNode::warnings).forEach(all::addAll);
            order.recoveryTasks().stream().map(RecoveryTaskNode::warnings).forEach(all::addAll);
            order.badTasks().stream().map(BadTaskNode::warnings).forEach(all::addAll);
        }
        graph.detachedReviews().stream().map(ReviewNode::warnings).forEach(all::addAll);
        graph.detachedRecoveryTasks().stream().map(RecoveryTaskNode::warnings).forEach(all::addAll);
        graph.detachedBadTasks().stream().map(BadTaskNode::warnings).forEach(all::addAll);

        int warnings = safeInt(all.stream()
                .filter(value -> value.severity() == WarningSeverity.WARNING)
                .count());
        int errors = safeInt(all.stream()
                .filter(value -> value.severity() == WarningSeverity.ERROR)
                .count());
        return new WorkloadTransferGraphDiagnostics(
                warnings,
                errors,
                codes(all, WarningSeverity.WARNING),
                codes(all, WarningSeverity.ERROR)
        );
    }

    public boolean hasReportableIssues() {
        return warningCount > 0 || errorCount > 0;
    }

    public String compactWarningCodes() {
        return compact(warningCodes);
    }

    public String compactErrorCodes() {
        return compact(errorCodes);
    }

    private static List<WarningCode> codes(List<Warning> warnings, WarningSeverity severity) {
        return warnings.stream()
                .filter(value -> value.severity() == severity)
                .map(Warning::code)
                .distinct()
                .sorted(Comparator.comparing(Enum::name))
                .toList();
    }

    private static String compact(List<WarningCode> codes) {
        return codes.stream()
                .map(Enum::name)
                .reduce((left, right) -> left + "," + right)
                .orElse("");
    }

    private static int safeInt(long value) {
        return value > Integer.MAX_VALUE ? Integer.MAX_VALUE : (int) value;
    }
}
