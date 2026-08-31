package ecommerce.security;

import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;

import ecommerce.exceptions.AuthenticationFailException;
import ecommerce.model.User;

@Component
public class CurrentUserService {

    public User requireCurrentUser() throws AuthenticationFailException {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        if (authentication == null
                || !authentication.isAuthenticated()
                || !(authentication.getPrincipal() instanceof UserPrincipal principal)) {
            throw new AuthenticationFailException("User is not authenticated");
        }
        return principal.getUser();
    }
}
