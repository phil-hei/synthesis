package de.synthesis.auth;

import de.synthesis.SynthesisProperties;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.validation.constraints.NotBlank;
import org.springframework.http.HttpStatus;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.AuthenticationException;
import org.springframework.security.core.context.SecurityContext;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.web.authentication.logout.CookieClearingLogoutHandler;
import org.springframework.security.web.authentication.logout.SecurityContextLogoutHandler;
import org.springframework.security.web.context.SecurityContextRepository;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

import java.util.List;

/** Sitzung anlegen, prüfen und beenden; eigenes Passwort ändern. */
@RestController
@RequestMapping("/api/auth")
public class AuthController {

    public record LoginRequest(@NotBlank String username, @NotBlank String password) { }

    public record PasswordChange(@NotBlank String currentPassword, @NotBlank String newPassword) { }

    public record SessionUser(String username, String displayName, List<String> roles) {
        static SessionUser of(UserAccount account) {
            return new SessionUser(account.username(), account.displayName(), account.roles());
        }
    }

    private final AuthenticationManager authenticationManager;
    private final SecurityContextRepository contextRepository;
    private final UserStore users;
    private final SynthesisProperties properties;

    public AuthController(AuthenticationManager authenticationManager, SecurityContextRepository contextRepository,
                          UserStore users, SynthesisProperties properties) {
        this.authenticationManager = authenticationManager;
        this.contextRepository = contextRepository;
        this.users = users;
        this.properties = properties;
    }

    @PostMapping("/login")
    public SessionUser login(@RequestBody LoginRequest request, HttpServletRequest http, HttpServletResponse response) {
        if (!properties.getAuth().isEnabled()) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Anmeldung ist deaktiviert");
        }
        Authentication authentication = authenticationManager.authenticate(
                UsernamePasswordAuthenticationToken.unauthenticated(request.username().trim(), request.password()));
        // Session-Fixation: eine vorhandene anonyme Sitzung bekommt beim Anmelden eine neue ID.
        if (http.getSession(false) != null) {
            http.changeSessionId();
        }
        SecurityContext context = SecurityContextHolder.createEmptyContext();
        context.setAuthentication(authentication);
        SecurityContextHolder.setContext(context);
        contextRepository.saveContext(context, http, response);
        users.recordLogin(authentication.getName());
        return users.find(authentication.getName()).map(SessionUser::of)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Unbekannter Benutzer"));
    }

    @PostMapping("/logout")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void logout(HttpServletRequest http, HttpServletResponse response) {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        new SecurityContextLogoutHandler().logout(http, response, authentication);
        new CookieClearingLogoutHandler("JSESSIONID", "XSRF-TOKEN").logout(http, response, authentication);
    }

    @GetMapping("/me")
    public SessionUser me() {
        if (!properties.getAuth().isEnabled()) {
            return new SessionUser("local", "Lokaler Betrieb ohne Anmeldung", List.of(UserAccount.ROLE_ADMIN));
        }
        return AuthContext.username().flatMap(users::find).map(SessionUser::of)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Nicht angemeldet"));
    }

    @PostMapping("/password")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void changePassword(@RequestBody PasswordChange request) {
        String username = AuthContext.username()
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Nicht angemeldet"));
        if (!users.verify(username, request.currentPassword())) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Aktuelles Passwort ist falsch");
        }
        try {
            users.setPassword(username, request.newPassword());
        } catch (IllegalArgumentException e) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, e.getMessage());
        }
    }

    @ExceptionHandler({BadCredentialsException.class, AuthenticationException.class})
    ResponseStatusException badCredentials(AuthenticationException e) {
        return new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Benutzername oder Passwort ist falsch");
    }
}
