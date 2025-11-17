package com.system.morapack.bll.controller;

import com.system.morapack.dao.morapack_psql.model.Account;
import com.system.morapack.dao.morapack_psql.model.User;
import com.system.morapack.dao.morapack_psql.repository.AccountRepository;
import com.system.morapack.dao.morapack_psql.service.AccountService;
import com.system.morapack.dao.morapack_psql.service.UserService;
import com.system.morapack.schemas.*;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.HashMap;
import java.util.Map;

@Service
@RequiredArgsConstructor
public class AuthController {

  private final UserService userService;
  private final AccountService accountService;
  private final AccountRepository accountRepository;

  // Simple in-memory session storage (for simulation only)
  private final Map<Integer, SessionSchema> activeSessions = new HashMap<>();
  private static Integer sessionIdCounter = 1;

  public AuthResponse register(RegisterRequest request) {
    try {
      // Check if email already exists
      if (accountRepository.existsByEmail(request.getUsername())) {
        return AuthResponse.builder()
            .success(false)
            .message("Email already registered")
            .build();
      }

      // Create user
      User user = User.builder()
          .name(request.getName())
          .lastName(request.getLastName())
          .userType(request.getType())
          .build();
      User savedUser = userService.createUser(user);

      // Create account
      Account account = Account.builder()
          .email(request.getUsername())
          .password(request.getPassword()) // In production, hash this password!
          .user(savedUser)
          .build();
      accountService.createAccount(account);

      // Create session
      SessionSchema session = SessionSchema.builder()
          .id(sessionIdCounter++)
          .userId(savedUser.getId())
          .userName(savedUser.getName())
          .userLastName(savedUser.getLastName())
          .userType(savedUser.getUserType())
          .loginTime(LocalDateTime.now())
          .lastActivity(LocalDateTime.now())
          .active(true)
          .build();

      activeSessions.put(session.getId(), session);

      return AuthResponse.builder()
          .success(true)
          .message("User registered successfully")
          .session(session)
          .build();

    } catch (Exception e) {
      return AuthResponse.builder()
          .success(false)
          .message("Registration failed: " + e.getMessage())
          .build();
    }
  }

  public AuthResponse login(LoginRequest request) {
    try {
      // Find account by email
      Account account = accountRepository.findByEmail(request.getUsername())
          .orElseThrow(() -> new IllegalArgumentException("Invalid credentials"));

      // Verify password (in production, compare hashed passwords)
      if (!account.getPassword().equals(request.getPassword())) {
        return AuthResponse.builder()
            .success(false)
            .message("Invalid credentials")
            .build();
      }

      User user = account.getUser();

      // Create session
      SessionSchema session = SessionSchema.builder()
          .id(sessionIdCounter++)
          .userId(user.getId())
          .userName(user.getName())
          .userLastName(user.getLastName())
          .email(account.getEmail())
          .userType(user.getUserType())
          .loginTime(LocalDateTime.now())
          .lastActivity(LocalDateTime.now())
          .active(true)
          .build();

      activeSessions.put(session.getId(), session);

      return AuthResponse.builder()
          .success(true)
          .message("Login successful")
          .session(session)
          .build();

    } catch (Exception e) {
      return AuthResponse.builder()
          .success(false)
          .message("Login failed: " + e.getMessage())
          .build();
    }
  }

  public AuthResponse logout(Integer sessionId) {
    SessionSchema session = activeSessions.get(sessionId);
    if (session != null) {
      session.setActive(false);
      activeSessions.remove(sessionId);
      return AuthResponse.builder()
          .success(true)
          .message("Logout successful")
          .build();
    }

    return AuthResponse.builder()
        .success(false)
        .message("Session not found")
        .build();
  }

  public SessionSchema getSession(Integer sessionId) {
    SessionSchema session = activeSessions.get(sessionId);
    if (session != null) {
      session.setLastActivity(LocalDateTime.now());
      return session;
    }
    return null;
  }
}
