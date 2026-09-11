package com.hunt.otziv.whatsapp.support;

import com.hunt.otziv.whatsapp.api.WhatsAppBusinessOperations;

/** Transport caller fixture only. Durable freeze behavior is tested separately against MySQL. */
public final class WhatsAppOperationsFixture {
    private WhatsAppOperationsFixture() {}
    public static WhatsAppBusinessOperations echo() {
        return org.mockito.Mockito.mock(WhatsAppBusinessOperations.class,invocation->{
            if(invocation.getMethod().getName().equals("freeze")||invocation.getMethod().getName().equals("freezeForDispatch"))
                return new WhatsAppBusinessOperations.FrozenMessage(invocation.getArgument(0),invocation.getArgument(1),invocation.getArgument(2),invocation.getArgument(3),invocation.getArgument(4));
            return org.mockito.Answers.RETURNS_DEFAULTS.answer(invocation);
        });
    }
}
