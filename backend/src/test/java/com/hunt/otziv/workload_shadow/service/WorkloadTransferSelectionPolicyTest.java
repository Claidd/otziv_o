package com.hunt.otziv.workload_shadow.service;

import static org.junit.jupiter.api.Assertions.assertEquals;

import com.hunt.otziv.workload_shadow.service.WorkloadTransferSelectionPolicy.Tier;
import java.util.List;
import org.junit.jupiter.api.Test;

class WorkloadTransferSelectionPolicyTest {

    @Test
    void tiersAreRelativeToConfiguredAllowedFailureDays() {
        Tier first = new Tier(15, 1);
        Tier second = new Tier(25, 2);
        Tier later = new Tier(30, 3);

        assertEquals(first, WorkloadTransferSelectionPolicy.tier(6, 5, first, second, later));
        assertEquals(second, WorkloadTransferSelectionPolicy.tier(7, 5, first, second, later));
        assertEquals(later, WorkloadTransferSelectionPolicy.tier(8, 5, first, second, later));
        assertEquals(later, WorkloadTransferSelectionPolicy.tier(12, 5, first, second, later));
    }

    @Test
    void selectionChoosesClosestWorkloadWithoutGrossOvershoot() {
        Candidate huge = new Candidate(1, 90);
        Candidate small = new Candidate(2, 10);

        List<Candidate> selected = WorkloadTransferSelectionPolicy.selectClosest(
                List.of(huge, small),
                15,
                1,
                Candidate::units,
                Candidate::companyId
        );

        assertEquals(List.of(small), selected);
    }

    @Test
    void selectionCanCombineCompaniesToReachCloserTarget() {
        Candidate thirty = new Candidate(1, 30);
        Candidate twenty = new Candidate(2, 20);
        Candidate fortyFive = new Candidate(3, 45);

        List<Candidate> selected = WorkloadTransferSelectionPolicy.selectClosest(
                List.of(thirty, twenty, fortyFive),
                50,
                2,
                Candidate::units,
                Candidate::companyId
        );

        assertEquals(List.of(thirty, twenty), selected);
    }

    private record Candidate(long companyId, long units) {
    }
}
