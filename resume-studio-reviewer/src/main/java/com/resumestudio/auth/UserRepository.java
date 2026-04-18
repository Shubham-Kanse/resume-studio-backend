package com.resumestudio.auth;

import com.resumestudio.auth.model.User;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

public interface UserRepository extends JpaRepository<User, String> {
    Optional<User> findByStripeCustomerId(String stripeCustomerId);
}
