package io.veridex.iam.infrastructure;

import io.veridex.iam.domain.PlatformUserRepository;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.core.userdetails.UsernameNotFoundException;
import org.springframework.stereotype.Service;

@Service
public class PlatformUserDetailsService implements UserDetailsService {

    private final PlatformUserRepository users;

    public PlatformUserDetailsService(PlatformUserRepository users) {
        this.users = users;
    }

    @Override
    public UserDetails loadUserByUsername(String username) throws UsernameNotFoundException {
        return users.findByUsername(username)
                .map(PlatformUserDetails::new)
                .orElseThrow(() -> new UsernameNotFoundException("unknown user: " + username));
    }
}
