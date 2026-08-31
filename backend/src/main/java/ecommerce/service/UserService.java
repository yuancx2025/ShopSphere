package ecommerce.service;

import java.util.Objects;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;

import ecommerce.config.MessageStrings;
import ecommerce.dto.user.SignInDto;
import ecommerce.dto.user.SignInResponseDto;
import ecommerce.dto.user.SignUpResponseDto;
import ecommerce.dto.user.SignupDto;
import ecommerce.dto.user.UserResponseDto;
import ecommerce.exceptions.AuthenticationFailException;
import ecommerce.exceptions.CustomException;
import ecommerce.model.Role;
import ecommerce.model.User;
import ecommerce.repository.UserRepository;
import ecommerce.security.JwtService;

@Service
public class UserService {

    private final UserRepository userRepository;
    private final PasswordEncoder passwordEncoder;
    private final JwtService jwtService;
    private final Logger logger = LoggerFactory.getLogger(UserService.class);

    public UserService(
            UserRepository userRepository,
            PasswordEncoder passwordEncoder,
            JwtService jwtService) {
        this.userRepository = userRepository;
        this.passwordEncoder = passwordEncoder;
        this.jwtService = jwtService;
    }

    public SignUpResponseDto signUp(SignupDto signupDto) throws CustomException {
        if (Objects.nonNull(userRepository.findByEmail(signupDto.getEmail()))) {
            throw new CustomException("User already exists");
        }

        User user = new User(
                signupDto.getFirstName(),
                signupDto.getLastName(),
                signupDto.getEmail(),
                passwordEncoder.encode(signupDto.getPassword()));
        user.setRole(Role.USER);

        try {
            userRepository.save(user);
            return new SignUpResponseDto("success", "user created successfully");
        } catch (Exception e) {
            logger.error("signup failed {}", e.getMessage());
            throw new CustomException(e.getMessage());
        }
    }

    public SignInResponseDto signIn(SignInDto signInDto) throws AuthenticationFailException, CustomException {
        User user = userRepository.findByEmail(signInDto.getEmail());
        if (!Objects.nonNull(user)) {
            throw new AuthenticationFailException("user not present");
        }

        if (!passwordEncoder.matches(signInDto.getPassword(), user.getPassword())) {
            throw new AuthenticationFailException(MessageStrings.WRONG_PASSWORD);
        }

        return new SignInResponseDto("success", jwtService.generateToken(user), toUserResponse(user));
    }

    public UserResponseDto toUserResponse(User user) {
        return new UserResponseDto(
                user.getId(),
                user.getFirstName(),
                user.getLastName(),
                user.getEmail(),
                user.getRole().name());
    }
}
