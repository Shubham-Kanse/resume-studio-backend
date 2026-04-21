package com.resumestudio.tracker;

import com.resumestudio.auth.UserRepository;
import com.resumestudio.auth.model.User;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

class JobReminderSchedulerTest {

    private JobApplicationRepository repo;
    private UserRepository userRepo;
    private TrackerReminderEmailSender emailSender;
    private JobReminderScheduler scheduler;

    @BeforeEach
    void setUp() {
        repo = mock(JobApplicationRepository.class);
        userRepo = mock(UserRepository.class);
        emailSender = mock(TrackerReminderEmailSender.class);
        scheduler = new JobReminderScheduler(repo, userRepo, emailSender, true);
    }

    @Test
    void sendDueReminders_sendsAndSchedulesNextReminder() {
        JobApplication job = new JobApplication();
        job.setUserId("user-1");
        job.setUserEmail("user@example.com");
        job.setReminderEnabled(true);
        job.setReminderFrequencyDays(3);
        job.setNextReminderAt(Instant.now().minusSeconds(60));

        when(emailSender.isConfigured()).thenReturn(true);
        when(repo.findDueReminders(any())).thenReturn(List.of(job));
        when(userRepo.findById("user-1")).thenReturn(Optional.of(new User("user-1")));
        when(emailSender.sendReminder(job)).thenReturn(true);
        when(repo.save(any())).thenAnswer(invocation -> invocation.getArgument(0));

        scheduler.sendDueReminders();

        verify(emailSender).sendReminder(job);
        verify(repo).save(job);
        assertNotNull(job.getLastReminderSentAt());
        assertNotNull(job.getNextReminderAt());
    }

    @Test
    void sendDueReminders_skipsWhenProviderIsMissing() {
        when(emailSender.isConfigured()).thenReturn(false);
        scheduler.sendDueReminders();
        verify(repo, never()).findDueReminders(any());
        verify(emailSender, never()).sendReminder(any());
    }

    @Test
    void sendDueReminders_skipsWhenUserOptedOutGlobally() {
        JobApplication job = new JobApplication();
        job.setUserId("user-1");
        job.setUserEmail("user@example.com");
        job.setReminderEnabled(true);
        job.setReminderFrequencyDays(3);
        job.setNextReminderAt(Instant.now().minusSeconds(60));

        User user = new User("user-1");
        user.setReminderEmailsEnabled(false);

        when(emailSender.isConfigured()).thenReturn(true);
        when(repo.findDueReminders(any())).thenReturn(List.of(job));
        when(userRepo.findById("user-1")).thenReturn(Optional.of(user));

        scheduler.sendDueReminders();

        verify(emailSender, never()).sendReminder(any());
        verify(repo, never()).save(any());
    }
}
