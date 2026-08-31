package ecommerce.dto.user;

public class SignInResponseDto {
    private String status;
    private String token;
    private UserResponseDto user;

    public String getStatus() {
        return status;
    }

    public void setStatus(String status) {
        this.status = status;
    }

    public String getToken() {
        return token;
    }

    public void setToken(String token) {
        this.token = token;
    }

    public UserResponseDto getUser() {
        return user;
    }

    public void setUser(UserResponseDto user) {
        this.user = user;
    }

    public SignInResponseDto(String status, String token) {
        this.status = status;
        this.token = token;
    }

    public SignInResponseDto(String status, String token, UserResponseDto user) {
        this.status = status;
        this.token = token;
        this.user = user;
    }
}
