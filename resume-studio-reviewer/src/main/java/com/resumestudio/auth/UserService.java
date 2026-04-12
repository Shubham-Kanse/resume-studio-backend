package com.resumestudio.auth;

import com.resumestudio.auth.model.Plan;
import com.resumestudio.auth.model.User;
import com.resumestudio.tracker.JobApplicationRepository;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class UserService {

    private final UserRepository repo;
    private final JobApplicationRepository jobRepo;

    public UserService(UserRepository repo, JobApplicationRepository jobRepo) {
        this.repo = repo;
        this.jobRepo = jobRepo;
    }

    @Transactional
    public User getOrCreate(String userId, String email) {
        return repo.findById(userId).orElseGet(() -> {
            try {
                return repo.saveAndFlush(new User(userId, email));
            } catch (DataIntegrityViolationException e) {
                return repo.findById(userId)
                    .orElseThrow(() -> new IllegalStateException("User disappeared after constraint violation", e));
            }
        });
    }

    public Plan getPlan(String userId) {
        return repo.findById(userId).map(User::getPlan).orElse(Plan.FREE);
    }

    /** Deletes all user data — job applications then the user record itself. */
    @Transactional
    public void deleteUser(String userId) {
        jobRepo.deleteByUserId(userId);
        repo.deleteById(userId);
    }
}
