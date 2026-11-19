package com.hunt.otziv.client_messages;

import com.hunt.otziv.client_messages.api.ClientContactHealth;
import com.hunt.otziv.client_messages.controller.ClientContactHealthController;
import org.junit.jupiter.api.Test;
import org.springframework.context.annotation.*;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.authentication.TestingAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.access.AccessDeniedException;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

class ClientContactHealthAuthorizationTest {
    @Configuration @EnableMethodSecurity static class Config {
        @Bean ClientContactHealth health(){ return mock(ClientContactHealth.class); }
        @Bean ClientContactHealthController controller(ClientContactHealth health){return new ClientContactHealthController(health);}
    }
    @Test void managersCannotListExcludedCompaniesOrProbeChatsButAdminAndOwnerCan() {
        try(var context=new AnnotationConfigApplicationContext(Config.class)) {
            var controller=context.getBean(ClientContactHealthController.class);
            var health=context.getBean(ClientContactHealth.class);
            SecurityContextHolder.getContext().setAuthentication(new TestingAuthenticationToken("manager","","ROLE_MANAGER"));
            assertThatThrownBy(controller::list).isInstanceOf(AccessDeniedException.class);
            assertThatThrownBy(()->controller.recheck(2116L)).isInstanceOf(AccessDeniedException.class);
            verifyNoInteractions(health);
            for(String role:new String[]{"ROLE_ADMIN","ROLE_OWNER"}) {
                SecurityContextHolder.getContext().setAuthentication(new TestingAuthenticationToken("fixture","",role));
                controller.list();controller.recheck(2116L);
            }
            verify(health,times(2)).excludedCompanies();verify(health,times(2)).recheck(2116L);
        } finally {SecurityContextHolder.clearContext();}
    }
}
