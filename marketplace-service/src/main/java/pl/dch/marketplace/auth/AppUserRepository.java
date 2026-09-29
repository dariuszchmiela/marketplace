package pl.dch.marketplace.auth;

import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;

public interface AppUserRepository extends JpaRepository<AppUser, Long> {

    /** Always call with a normalized email ({@link EmailAddress#normalize}). */
    Optional<AppUser> findByEmail(String email);

    boolean existsByEmail(String email);
}
