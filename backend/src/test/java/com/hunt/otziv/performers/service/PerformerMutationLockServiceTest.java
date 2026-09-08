package com.hunt.otziv.performers.service;

import static org.mockito.Mockito.*;
import static org.assertj.core.api.Assertions.*;
import com.hunt.otziv.p_products.model.Order;
import com.hunt.otziv.p_products.review.service.OrderAggregateMutationLockService;
import com.hunt.otziv.performers.model.ReviewPerformerAssignment;
import jakarta.persistence.EntityManager;
import jakarta.persistence.LockModeType;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;

class PerformerMutationLockServiceTest {
    @Test
    @SuppressWarnings("unchecked")
    void canonicalOrderMutexPrecedesAssignmentRefreshAndNestedCallPreservesWrites() {
        var orders=mock(OrderAggregateMutationLockService.class);
        var jdbc=mock(JdbcTemplate.class);
        var em=mock(EntityManager.class);
        var assignment=ReviewPerformerAssignment.builder().id(2L).order(Order.builder().id(1L).build()).build();
        when(jdbc.query(anyString(),any(RowMapper.class),eq(2L))).thenReturn(List.of(1L));
        when(em.find(ReviewPerformerAssignment.class,2L)).thenReturn(assignment);
        when(em.getLockMode(assignment)).thenReturn(LockModeType.NONE,LockModeType.PESSIMISTIC_WRITE);
        var locks=new PerformerMutationLockService(orders,jdbc,em);
        locks.assignment(2L);
        assignment.setManagerNote("pending caller write");
        locks.assignment(2L);
        var sequence=inOrder(orders,em);
        sequence.verify(orders).lock(1L);
        sequence.verify(em).find(ReviewPerformerAssignment.class,2L);
        sequence.verify(em).getLockMode(assignment);
        sequence.verify(em).refresh(assignment,LockModeType.PESSIMISTIC_WRITE);
        verify(em,times(1)).refresh(assignment,LockModeType.PESSIMISTIC_WRITE);
        assertThat(assignment.getManagerNote()).isEqualTo("pending caller write");
    }
}
