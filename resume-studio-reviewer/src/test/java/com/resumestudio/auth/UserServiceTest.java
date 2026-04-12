package com.resumestudio.auth;

import com.resumestudio.auth.model.Plan;
import com.resumestudio.auth.model.User;
import com.resumestudio.tracker.JobApplicationRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataIntegrityViolationException;

import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

class UserServiceTest {

    private UserRepository repo;
    private JobApplicationRepository jobRepo;
    private UserService service;

    @BeforeEach
    void setUp() {
        repo = mock(UserRepository.class);
        jobRepo = mock(JobApplicationRepository.class);
        service = new UserService(repo, jobRepo);
    }

    @Test
    void existingUser_returnsWithoutSaving() {
        var existing = new User("uid-1", "a@b.com");
        when(repo.findById("uid-1")).thenReturn(Optional.of(existing));

        var result = service.getOrCreate("uid-1", "a@b.com");
        assertSame(existing, result);
        verify(repo, never()).saveAndFlush(any());
    }

    @Test
    void newUser_savesAndReturns() {
        when(repo.findById("uid-new")).thenReturn(Optional.empty());
        var saved = new User("uid-new", "new@b.com");
        when(repo.saveAndFlush(any())).thenReturn(saved);

        var result = service.getOrCreate("uid-new", "new@b.com");
        assertEquals("uid-new", result.getId());
        assertEquals(Plan.FREE, result.getPlan());
    }

    @Test
    void concurrentFirstLogin_constraintViolation_retriesAndReturnsExisting() {
        when(repo.findById("uid-race")).thenReturn(Optional.empty());
        when(repo.saveAndFlush(any())).thenThrow(new DataIntegrityViolationException("duplicate key"));
        var existing = new User("uid-race", "race@b.com");
        // Second findById (after constraint violation) returns the row inserted by the other thread
        when(repo.findById("uid-race"))
            .thenReturn(Optional.empty())   // first call — triggers save
            .thenReturn(Optional.of(existing)); // second call — after constraint violation

        var result = service.getOrCreate("uid-race", "race@b.com");
        assertSame(existing, result);
    }

    @Test
    void getPlan_existingUser_returnsPlan() {
        var user = new User("uid-1", "a@b.com");
        user.setPlan(Plan.PRO);
        when(repo.findById("uid-1")).thenReturn(Optional.of(user));
        assertEquals(Plan.PRO, service.getPlan("uid-1"));
    }

    @Test
    void getPlan_unknownUser_returnsFree() {
        when(repo.findById("unknown")).thenReturn(Optional.empty());
        assertEquals(Plan.FREE, service.getPlan("unknown"));
    }

    @Test
    void deleteUser_deletesJobsThenUser() {
        service.deleteUser("uid-1");
        verify(jobRepo).deleteByUserId("uid-1");
        verify(repo).deleteById("uid-1");
    }
}
