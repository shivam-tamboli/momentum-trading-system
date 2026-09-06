package com.momentum.service;

import com.momentum.model.User;
import com.momentum.repository.UserRepository;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Service;

/**
 * Every controller with a {@code userId} path variable must confirm the path actually refers to
 * the caller before acting on it — otherwise any authenticated user could read or trade on any
 * other user's account just by changing the number in the URL. {@code JwtAuthFilter} resolves the
 * caller's Supabase email once per request and stores it as the authentication principal; this
 * service turns that into "does userId belong to me."
 */
@Service
public class UserAuthorizationService {

    private final UserRepository userRepository;

    public UserAuthorizationService(UserRepository userRepository) {
        this.userRepository = userRepository;
    }

    public boolean isOwnedByCaller(Long pathUserId) {
        if (pathUserId == null) {
            return false;
        }

        String email = (String) SecurityContextHolder.getContext().getAuthentication().getPrincipal();
        return userRepository.findByEmail(email)
                .map(User::getId)
                .map(pathUserId::equals)
                .orElse(false);
    }
}
