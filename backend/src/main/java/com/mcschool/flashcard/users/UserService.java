package com.mcschool.flashcard.users;

import com.mcschool.flashcard.auth.AuthenticatedUser;
import com.mcschool.flashcard.common.ResourceNotFoundException;
import com.mcschool.flashcard.users.dto.UpdateSettingsRequest;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Operations a user performs on their own account, available to every role. */
@Service
public class UserService {

    private final UserRepository userRepository;

    public UserService(UserRepository userRepository) {
        this.userRepository = userRepository;
    }

    @Transactional
    public UserResponse updateSettings(AuthenticatedUser caller, UpdateSettingsRequest request) {
        User user = requireUser(caller.id());
        user.changeLanguage(request.preferredLanguage());
        return UserResponse.from(user);
    }

    private User requireUser(UUID userId) {
        return userRepository.findById(userId)
                .orElseThrow(() -> new ResourceNotFoundException("Account no longer exists"));
    }
}
