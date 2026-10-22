package com.hunt.otziv.manager_control.service;

import com.hunt.otziv.manager_control.diagnostics.ManagerProblemDiagnosticsService;
import com.hunt.otziv.manager_control.model.ManagerDailyControlConcreteItem;
import com.hunt.otziv.manager_control.model.ManagerDailyControlItemStatus;
import com.hunt.otziv.manager_control.repository.ManagerDailyControlConcreteItemRepository;
import com.hunt.otziv.manager_control.repository.ManagerDailyControlEventRepository;
import com.hunt.otziv.manager_control.repository.ManagerDailyControlItemRepository;
import com.hunt.otziv.manager_performance.service.ManagerPerformanceService;
import org.junit.jupiter.api.Test;
import static org.mockito.Mockito.*;

class ManagerControlCardLifecycleDiagnosticsTest {
    @Test
    void bothAutomaticAndDeferredClosureRequestCommittedDiagnosticSnapshot() {
        ManagerProblemDiagnosticsService diagnostics = mock(ManagerProblemDiagnosticsService.class);
        var lifecycle = new ManagerControlCardLifecycle(mock(ManagerDailyControlConcreteItemRepository.class),
                mock(ManagerDailyControlItemRepository.class), mock(ManagerDailyControlEventRepository.class),
                mock(ManagerPerformanceService.class), mock(ManagerControlReadSnapshots.class), diagnostics);
        ManagerDailyControlConcreteItem open = new ManagerDailyControlConcreteItem();
        open.setId(10L);
        open.setStatus(ManagerDailyControlItemStatus.OPEN);
        lifecycle.recordConcreteEpisode(open, ManagerDailyControlItemStatus.RESOLVED, true);
        ManagerDailyControlConcreteItem deferred = new ManagerDailyControlConcreteItem();
        deferred.setId(11L);
        deferred.setStatus(ManagerDailyControlItemStatus.DEFERRED);
        lifecycle.recordConcreteEpisode(deferred, ManagerDailyControlItemStatus.RESOLVED, false);
        verify(diagnostics).requestRefresh(10L);
        verify(diagnostics).requestRefresh(11L);
    }
}
