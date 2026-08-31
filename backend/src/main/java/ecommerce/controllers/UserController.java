package ecommerce.controllers;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import ecommerce.dto.user.SignInDto;
import ecommerce.dto.user.SignInResponseDto;
import ecommerce.dto.user.SignUpResponseDto;
import ecommerce.dto.user.SignupDto;
import ecommerce.dto.user.UserResponseDto;
import ecommerce.exceptions.AuthenticationFailException;
import ecommerce.exceptions.CustomException;
import ecommerce.security.CurrentUserService;
import ecommerce.service.UserService;

@RequestMapping("user")
@RestController
public class UserController {

    private final UserService userService;
    private final CurrentUserService currentUserService;

    public UserController(UserService userService, CurrentUserService currentUserService) {
        this.userService = userService;
        this.currentUserService = currentUserService;
    }

    @PostMapping("/signup")
    public SignUpResponseDto Signup(@RequestBody SignupDto signupDto) throws CustomException {
        return userService.signUp(signupDto);
    }

    @PostMapping("/signIn")
    public SignInResponseDto SignIn(@RequestBody SignInDto signInDto)
            throws CustomException, AuthenticationFailException {
        return userService.signIn(signInDto);
    }

    @GetMapping("/me")
    public ResponseEntity<UserResponseDto> me() throws AuthenticationFailException {
        return new ResponseEntity<>(
                userService.toUserResponse(currentUserService.requireCurrentUser()),
                HttpStatus.OK);
    }
}
