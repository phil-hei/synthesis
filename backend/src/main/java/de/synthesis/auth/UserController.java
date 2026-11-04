package de.synthesis.auth;

import org.springframework.http.HttpStatus;
import org.springframework.security.core.session.SessionInformation;
import org.springframework.security.core.session.SessionRegistry;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

import java.util.List;

/** Kontenverwaltung; die Rolle ADMIN wird in {@link SecurityConfig} erzwungen. */
@RestController
@RequestMapping("/api/users")
public class UserController {

    public record NewUser(String username, String displayName, String password, String role) { }

    public record RoleChange(String role) { }

    public record PasswordReset(String password) { }

    private final UserStore users;
    private final SessionRegistry sessionRegistry;

    public UserController(UserStore users, SessionRegistry sessionRegistry) {
        this.users = users;
        this.sessionRegistry = sessionRegistry;
    }

    /** Kontoänderungen gelten sofort: laufende Sitzungen des Kontos werden beendet (nächste Anfrage 401). */
    private void endSessionsOf(String username) {
        for (SessionInformation session : sessionRegistry.getAllSessions(username, false)) {
            session.expireNow();
        }
    }

    @GetMapping
    public List<UserAccount> list() {
        return users.list();
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public UserAccount create(@RequestBody NewUser request) {
        return users.create(request.username(), request.displayName(), request.password(), request.role());
    }

    @PutMapping("/{username}/role")
    public UserAccount setRole(@PathVariable String username, @RequestBody RoleChange request) {
        if (AuthContext.username().map(username::equals).orElse(false)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Die eigene Rolle kann nicht geändert werden");
        }
        UserAccount updated = users.setRole(username, request.role());
        endSessionsOf(updated.username());
        return updated;
    }

    @PutMapping("/{username}/password")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void resetPassword(@PathVariable String username, @RequestBody PasswordReset request) {
        users.setPassword(username, request.password());
        endSessionsOf(username);
    }

    @DeleteMapping("/{username}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void delete(@PathVariable String username) {
        if (AuthContext.username().map(username::equals).orElse(false)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Das eigene Konto kann nicht gelöscht werden");
        }
        users.delete(username);
        endSessionsOf(username);
    }

    @ExceptionHandler(IllegalArgumentException.class)
    ResponseStatusException invalid(IllegalArgumentException e) {
        return new ResponseStatusException(HttpStatus.BAD_REQUEST, e.getMessage());
    }
}
