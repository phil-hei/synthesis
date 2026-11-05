package de.synthesis.auth;

import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;

import java.util.Optional;

/** Lesezugriff auf die angemeldete Sitzung für Controller außerhalb des Auth-Pakets. */
public final class AuthContext {

    private AuthContext() {
    }

    private static Optional<Authentication> current() {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        if (authentication == null || !authentication.isAuthenticated()
                || "anonymousUser".equals(authentication.getPrincipal())) {
            return Optional.empty();
        }
        return Optional.of(authentication);
    }

    public static Optional<String> username() {
        return current().map(Authentication::getName);
    }

    public static boolean isAdmin() {
        return current().map(a -> a.getAuthorities().stream()
                .anyMatch(g -> ("ROLE_" + UserAccount.ROLE_ADMIN).equals(g.getAuthority()))).orElse(false);
    }

    /**
     * Ob die Sitzung ein Objekt mit dem angegebenen Ersteller verwalten darf. Ohne Anmeldung
     * (Auth deaktiviert) oder ohne hinterlegten Ersteller gilt der geteilte Arbeitsbereich.
     */
    public static boolean mayManage(String createdBy) {
        Optional<String> user = username();
        if (user.isEmpty() || createdBy == null || createdBy.isBlank()) {
            return true;
        }
        return isAdmin() || createdBy.equals(user.get());
    }
}
