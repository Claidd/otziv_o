package com.hunt.otziv.review_recovery.services;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.hunt.otziv.b_bots.model.Bot;
import com.hunt.otziv.review_recovery.repository.ReviewRecoveryBotExclusionRepository;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class ReviewRecoveryBotExclusionServiceTest {

    @Mock
    private ReviewRecoveryBotExclusionRepository repository;

    @InjectMocks
    private ReviewRecoveryBotExclusionService service;

    @Test
    void storesRejectedBotOnceForTask() {
        Bot bot = new Bot();
        bot.setId(25L);

        service.reject(40L, bot, "change");

        verify(repository).insertIgnore(40L, 25L, "CHANGE");
    }

    @Test
    void returnsDefensiveCopyAndClearsTaskHistory() {
        when(repository.findBotIdsByTaskId(40L)).thenReturn(Set.of(21L, 22L));
        when(repository.deleteByTaskId(40L)).thenReturn(2);

        Set<Long> result = service.excludedBotIds(40L);
        result.add(23L);

        assertEquals(Set.of(21L, 22L, 23L), result);
        assertEquals(2, service.clearForTask(40L));
        verify(repository).deleteByTaskId(40L);
    }

    @Test
    void ignoresStubBot() {
        Bot stub = new Bot();
        stub.setId(1L);

        service.reject(40L, stub, "BLOCK");

        verifyNoInteractions(repository);
    }
}
