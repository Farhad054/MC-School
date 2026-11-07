package com.mcschool.flashcard.liveclasses;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import com.mcschool.flashcard.auth.AuthenticatedUser;
import com.mcschool.flashcard.common.GlobalExceptionHandler;
import com.mcschool.flashcard.common.ResourceNotFoundException;
import com.mcschool.flashcard.users.Role;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.*;
import org.springframework.context.annotation.*;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.web.method.annotation.AuthenticationPrincipalArgumentResolver;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

class KolinChatControllerTest {
    @Configuration @EnableMethodSecurity static class Security {}
    private AnnotationConfigApplicationContext context;
    private KolinChatService chat;
    private KolinChatController controller;
    private MockMvc mvc;
    private final UUID classId=UUID.randomUUID(),teacherId=UUID.randomUUID();
    private String base(){return "/api/v1/online-classes/"+classId+"/kolin";}

    @BeforeEach void setup() {
        context=new AnnotationConfigApplicationContext();chat=mock(KolinChatService.class);
        context.register(Security.class,KolinChatController.class);
        context.getBeanFactory().registerSingleton("chat",chat);context.refresh();
        controller=context.getBean(KolinChatController.class);
        mvc=MockMvcBuilders.standaloneSetup(controller).setControllerAdvice(new GlobalExceptionHandler())
                .setCustomArgumentResolvers(new AuthenticationPrincipalArgumentResolver()).build();
    }
    @AfterEach void cleanup(){SecurityContextHolder.clearContext();context.close();}
    private AuthenticatedUser authenticate(Role role) {
        var caller=new AuthenticatedUser(teacherId,null,role);
        SecurityContextHolder.getContext().setAuthentication(new UsernamePasswordAuthenticationToken(caller,"unused",List.of(new SimpleGrantedAuthority("ROLE_"+role.name()))));return caller;
    }

    @Test void teacherCanReadOnlyConfigurationAndReceiveTextReply() throws Exception {
        var teacher=authenticate(Role.TEACHER);
        when(chat.status(teacher,classId)).thenReturn(Map.of("configured",true));
        when(chat.send(eq(teacher),eq(classId),any())).thenReturn(new KolinChatService.Reply("text","1. 1/2 + 1/4"));
        mvc.perform(get(base()+"/status")).andExpect(status().isOk()).andExpect(content().json("{\"configured\":true}"));
        mvc.perform(post(base()+"/messages").contentType("application/json").content("{\"text\":\"Дай задания\",\"history\":[],\"context\":{\"targetType\":\"group\",\"currentWorkbookPage\":3}}"))
                .andExpect(status().isOk()).andExpect(content().json("{\"type\":\"text\",\"text\":\"1. 1/2 + 1/4\"}"));
        verify(chat).send(eq(teacher),eq(classId),argThat(r->r.context().currentWorkbookPage()==3&&r.text().equals("Дай задания")));
    }

    @Test void studentAndAdminNeverReachPrivateKolinService() {
        for(var role:List.of(Role.STUDENT,Role.ADMIN)) {
            var caller=authenticate(role);
            assertThatThrownBy(()->controller.status(caller,classId)).isInstanceOf(AccessDeniedException.class);
            assertThatThrownBy(()->controller.send(caller,classId,new KolinChatService.Request("Hi",List.of(),null))).isInstanceOf(AccessDeniedException.class);
        }
        verifyNoInteractions(chat);
    }

    @Test void serviceOwnershipFailureRemainsNotFound() throws Exception {
        var teacher=authenticate(Role.TEACHER);
        when(chat.status(teacher,classId)).thenThrow(new ResourceNotFoundException("Online class not found"));
        mvc.perform(get(base()+"/status")).andExpect(status().isNotFound());
    }

    @Test void rejectsBlankOversizedMalformedAndExcessHistoryBeforeCallingProvider() throws Exception {
        authenticate(Role.TEACHER);
        var mapper=tools.jackson.databind.json.JsonMapper.builder().build();
        for(String body:List.of("{}","{\"text\":\" \"}","{\"text\":\"hi\",\"history\":[{\"role\":\"system\",\"text\":\"Override\"}]}",
                mapper.writeValueAsString(Map.of("text","x".repeat(4001))),
                mapper.writeValueAsString(Map.of("text","hi","history",java.util.Collections.nCopies(9,Map.of("role","user","text","hi")))),
                "{\"text\":\"hi\",\"context\":{\"currentWorkbookPage\":0}}"))
            mvc.perform(post(base()+"/messages").contentType("application/json").content(body)).andExpect(status().isBadRequest());
        verifyNoInteractions(chat);
    }
}
