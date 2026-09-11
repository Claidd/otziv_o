package com.hunt.otziv.u_users.repository;

import static org.junit.jupiter.api.Assertions.assertTrue;

import java.lang.reflect.Method;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.data.jpa.repository.Query;

class ManagerRepositoryPaymentProfileAssignmentContractTest {

    @Test
    void operationalManagerListContainsOnlyActiveManagerUsers() throws Exception {
        assertCurrentManagerQuery(query(
                ManagerRepository.class,
                "findAllWithUserAndImage"
        ));
    }

    @Test
    void ownerManagerExpansionCannotReintroduceHistoricalManagerIdentities() throws Exception {
        assertCurrentManagerQuery(query(
                ManagerRepository.class,
                "findAllManagersToOwner",
                List.class
        ));
    }

    @Test
    void workerManagerExpansionCannotReintroduceHistoricalManagerIdentities() throws Exception {
        assertCurrentManagerQuery(query(
                ManagerRepository.class,
                "findManagersWithWorkers",
                List.class
        ));
    }

    @Test
    void collectionExpansionUsesOnlyManagersAcceptedByTheActiveRoleQuery() {
        var current = new com.hunt.otziv.u_users.model.Manager(); current.setId(1L);
        var historical = new com.hunt.otziv.u_users.model.Manager(); historical.setId(2L);
        var repository = org.mockito.Mockito.mock(ManagerRepository.class, org.mockito.Mockito.CALLS_REAL_METHODS);
        org.mockito.Mockito.when(repository.findManagersWithWorkers(List.of(current,historical)))
                .thenReturn(List.of(current));
        org.junit.jupiter.api.Assertions.assertEquals(List.of(current),repository.findAllManagersWorkers(List.of(current,historical)));
        org.mockito.Mockito.verify(repository).fetchManagerOperators(List.of(current));
        org.mockito.Mockito.verify(repository).fetchManagerMarketologs(List.of(current));
    }

    @Test
    void ownerTeamLookupContainsOnlyActiveManagerUsers() throws Exception {
        String query = query(
                UserRepository.class,
                "findManagersWithTeamByUsername",
                String.class
        );

        assertTrue(query.contains("JOIN mu.roles mr"));
        assertTrue(query.contains("mu.active = true"));
        assertTrue(query.contains("mr.name = 'ROLE_MANAGER'"));
    }

    @Test
    void paymentProfileAssignmentsContainOnlyActiveManagerUsers() throws Exception {
        assertCurrentManagerQuery(query(
                ManagerRepository.class,
                "findAllForPaymentProfileAssignments"
        ));
    }

    @Test
    void reportReviewSettingsContainOnlyActiveManagerUsers() throws Exception {
        assertCurrentManagerQuery(query(
                ManagerRepository.class,
                "findAllForReportReviewSettings"
        ));
    }

    private static void assertCurrentManagerQuery(String query) {
        assertTrue(query.contains("JOIN u.roles r"));
        assertTrue(query.contains("u.active = true"));
        assertTrue(query.contains("r.name = 'ROLE_MANAGER'"));
    }

    private static String query(
            Class<?> repository,
            String methodName,
            Class<?>... parameterTypes
    ) throws Exception {
        Method method = repository.getMethod(methodName, parameterTypes);
        return method.getAnnotation(Query.class).value();
    }
}
