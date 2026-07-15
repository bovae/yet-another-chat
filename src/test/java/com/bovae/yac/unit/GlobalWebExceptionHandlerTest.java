package com.bovae.yac.unit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.bovae.yac.exception.ForbiddenException;
import com.bovae.yac.exception.GlobalWebExceptionHandler;
import com.bovae.yac.exception.ResourceNotFoundException;
import jakarta.servlet.http.HttpServletRequest;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.ui.Model;

/** Unit tests for {@link GlobalWebExceptionHandler}. */
@ExtendWith(MockitoExtension.class)
class GlobalWebExceptionHandlerTest {

    @Mock
    private Model model;

    @Mock
    private HttpServletRequest request;

    @InjectMocks
    private GlobalWebExceptionHandler handler;

    @Test
    void handleNotFound_shouldReturn404ViewAndSetMessage_whenResourceNotFound() {
        when(request.getRequestURI()).thenReturn("/rooms/1");

        String view = handler.handleNotFound(new ResourceNotFoundException("Room not found"), model, request);

        assertThat(view).isEqualTo("error/404");
        verify(model).addAttribute("message", "Room not found");
    }

    @Test
    void handleForbidden_shouldReturn403ViewAndSetMessage_whenAccessDenied() {
        String view = handler.handleForbidden(new ForbiddenException("Access denied"), model);

        assertThat(view).isEqualTo("error/403");
        verify(model).addAttribute("message", "Access denied");
    }

    @Test
    void handleGeneral_shouldReturn500ViewAndSetGenericMessage_whenUnexpectedError() {
        String view = handler.handleGeneral(new RuntimeException("boom"), model);

        assertThat(view).isEqualTo("error/500");
        verify(model).addAttribute("message", "An unexpected error occurred");
    }
}
