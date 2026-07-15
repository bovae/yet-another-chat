package com.bovae.yac.unit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.bovae.yac.exception.ConflictException;
import com.bovae.yac.exception.FileStorageException;
import com.bovae.yac.exception.ForbiddenException;
import com.bovae.yac.exception.GlobalApiExceptionHandler;
import com.bovae.yac.exception.ResourceNotFoundException;
import com.bovae.yac.model.dto.ErrorResponse;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.ConstraintViolation;
import jakarta.validation.ConstraintViolationException;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.validation.BindingResult;
import org.springframework.validation.FieldError;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;

/** Unit tests for {@link GlobalApiExceptionHandler}. */
@ExtendWith(MockitoExtension.class)
class GlobalApiExceptionHandlerTest {

    private static final String REQUEST_URI = "/api/rooms/123";

    @Mock
    private HttpServletRequest request;

    @InjectMocks
    private GlobalApiExceptionHandler handler;

    @BeforeEach
    void setUp() {
        lenient().when(request.getRequestURI()).thenReturn(REQUEST_URI);
    }

    // --- direct-delegation handlers ---

    @Test
    void handleNotFound_shouldReturn404WithMessage_whenResourceNotFound() {
        ResponseEntity<ErrorResponse> response =
                handler.handleNotFound(new ResourceNotFoundException("Room not found"), request);

        assertBody(response, HttpStatus.NOT_FOUND, "Room not found");
    }

    @Test
    void handleForbidden_shouldReturn403WithMessage_whenAccessDenied() {
        ResponseEntity<ErrorResponse> response =
                handler.handleForbidden(new ForbiddenException("Not a member"), request);

        assertBody(response, HttpStatus.FORBIDDEN, "Not a member");
    }

    @Test
    void handleConflict_shouldReturn409WithMessage_whenConflict() {
        ResponseEntity<ErrorResponse> response =
                handler.handleConflict(new ConflictException("Already exists"), request);

        assertBody(response, HttpStatus.CONFLICT, "Already exists");
    }

    @Test
    void handleFileStorage_shouldReturn400WithMessage_whenFileStorageFails() {
        ResponseEntity<ErrorResponse> response =
                handler.handleFileStorage(new FileStorageException("Disk full"), request);

        assertBody(response, HttpStatus.BAD_REQUEST, "Disk full");
    }

    @Test
    void handleIllegalArgument_shouldReturn400WithMessage_whenArgumentInvalid() {
        ResponseEntity<ErrorResponse> response =
                handler.handleIllegalArgument(new IllegalArgumentException("bad arg"), request);

        assertBody(response, HttpStatus.BAD_REQUEST, "bad arg");
    }

    @Test
    void handleGeneral_shouldReturn500WithGenericMessage_whenUnexpectedError() {
        ResponseEntity<ErrorResponse> response = handler.handleGeneral(new RuntimeException("boom"), request);

        assertBody(response, HttpStatus.INTERNAL_SERVER_ERROR, "An unexpected error occurred");
    }

    @Test
    void handleNotFound_shouldFallBackToReasonPhrase_whenMessageNull() {
        ResponseEntity<ErrorResponse> response = handler.handleNotFound(new ResourceNotFoundException(null), request);

        assertBody(response, HttpStatus.NOT_FOUND, HttpStatus.NOT_FOUND.getReasonPhrase());
    }

    // --- message-aggregating / mapped handlers ---

    @Test
    void handleValidation_shouldReturn400WithAggregatedFieldError_whenBodyInvalid() {
        MethodArgumentNotValidException ex = mock(MethodArgumentNotValidException.class);
        BindingResult bindingResult = mock(BindingResult.class);
        FieldError fieldError = mock(FieldError.class);
        when(ex.getBindingResult()).thenReturn(bindingResult);
        when(bindingResult.getFieldErrors()).thenReturn(List.of(fieldError));
        when(fieldError.getField()).thenReturn("name");
        when(fieldError.getDefaultMessage()).thenReturn("must not be blank");

        ResponseEntity<ErrorResponse> response = handler.handleValidation(ex, request);

        assertBody(response, HttpStatus.BAD_REQUEST, "name: must not be blank");
    }

    @Test
    void handleConstraintViolation_shouldReturn400WithViolationMessage_whenConstraintViolated() {
        ConstraintViolation<?> violation = mock(ConstraintViolation.class);
        when(violation.getMessage()).thenReturn("must not be blank");
        ConstraintViolationException ex = new ConstraintViolationException(Set.of(violation));

        ResponseEntity<ErrorResponse> response = handler.handleConstraintViolation(ex, request);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        ErrorResponse body = response.getBody();
        assertThat(body).isNotNull();
        assertThat(body.status()).isEqualTo(HttpStatus.BAD_REQUEST.value());
        assertThat(body.message()).contains("must not be blank");
        assertThat(body.path()).isEqualTo(REQUEST_URI);
    }

    @Test
    void handleDataIntegrity_shouldReturn409WithGenericMessage_whenDataIntegrityViolated() {
        ResponseEntity<ErrorResponse> response =
                handler.handleDataIntegrity(new DataIntegrityViolationException("duplicate key value"), request);

        assertBody(response, HttpStatus.CONFLICT, "The request conflicts with existing data");
    }

    @Test
    void handleTypeMismatch_shouldReturn400WithParamNameAndValue_whenTypeMismatch() {
        MethodArgumentTypeMismatchException ex = mock(MethodArgumentTypeMismatchException.class);
        when(ex.getName()).thenReturn("roomId");
        when(ex.getValue()).thenReturn("abc");

        ResponseEntity<ErrorResponse> response = handler.handleTypeMismatch(ex, request);

        assertBody(response, HttpStatus.BAD_REQUEST, "Invalid value for parameter 'roomId': abc");
    }

    private void assertBody(ResponseEntity<ErrorResponse> response, HttpStatus status, String expectedMessage) {
        assertThat(response.getStatusCode()).isEqualTo(status);
        ErrorResponse body = response.getBody();
        assertThat(body).isNotNull();
        assertThat(body.status()).isEqualTo(status.value());
        assertThat(body.message()).isEqualTo(expectedMessage);
        assertThat(body.path()).isEqualTo(REQUEST_URI);
        assertThat(body.timestamp()).isNotNull();
    }
}
