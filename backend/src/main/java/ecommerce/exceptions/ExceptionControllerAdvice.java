package ecommerce.exceptions;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.security.core.AuthenticationException;
import org.springframework.web.bind.annotation.ControllerAdvice;
import org.springframework.web.bind.annotation.ExceptionHandler;

@ControllerAdvice
public class ExceptionControllerAdvice {

    @ExceptionHandler(value = CustomException.class)
    public final ResponseEntity<String> handleCustomException(CustomException exception) {
        return new ResponseEntity<>(exception.getMessage(), HttpStatus.BAD_REQUEST);
    }

    @ExceptionHandler(value = ProductNotExistException.class)
    public final ResponseEntity<String> handleProductNotExistException(ProductNotExistException exception) {
        return new ResponseEntity<>(exception.getMessage(), HttpStatus.BAD_REQUEST);
    }

    @ExceptionHandler(value = OrderNotFoundException.class)
    public final ResponseEntity<String> handleOrderNotFoundException(OrderNotFoundException exception) {
        return new ResponseEntity<>(exception.getMessage(), HttpStatus.BAD_REQUEST);
    }

    @ExceptionHandler(value = AuthenticationFailException.class)
    public final ResponseEntity<String> handleAuthenticationFailException(AuthenticationFailException exception) {
        return new ResponseEntity<>(exception.getMessage(), HttpStatus.UNAUTHORIZED);
    }

    @ExceptionHandler(value = {AuthenticationException.class, BadCredentialsException.class})
    public final ResponseEntity<String> handleAuthenticationException(Exception exception) {
        return new ResponseEntity<>(exception.getMessage(), HttpStatus.UNAUTHORIZED);
    }

    @ExceptionHandler(value = AccessDeniedException.class)
    public final ResponseEntity<String> handleAccessDeniedException(AccessDeniedException exception) {
        return new ResponseEntity<>("Access denied", HttpStatus.FORBIDDEN);
    }
}
