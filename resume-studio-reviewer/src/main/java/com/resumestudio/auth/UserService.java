package com.resumestudio.auth;

import com.resumestudio.auth.model.Plan;
import com.resumestudio.auth.model.User;
import com.resumestudio.tracker.JobApplicationRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class UserService {

    private static final Logger log = LoggerFactory.getLogger(UserService.class);

    private final UserRepository repo;
    private final JobApplicationRepository jobRepo;

    public UserService(UserRepository repo, JobApplicationRepository jobRepo) {
        this.repo = repo;
        this.jobRepo = jobRepo;
    }

    @Transactional
    public User getOrCreate(String userId) {
        return repo.findById(userId).orElseGet(() -> {
            try {
                return repo.saveAndFlush(new User(userId));
            } catch (DataIntegrityViolationException e) {
                return repo.findById(userId)
                    .orElseThrow(() -> new IllegalStateException("User disappeared after constraint violation", e));
            }
        });
    }

    public Plan getPlan(String userId) {
        return repo.findById(userId).map(User::getPlan).orElse(Plan.FREE);
    }

    @Transactional
    public void upgradePlan(String userId, Plan plan) {
        repo.findById(userId).ifPresent(u -> {
            u.setPlan(plan);
            repo.save(u);
        });
    }

    @Transactional
    public void downgradeByStripeCustomerId(String stripeCustomerId) {
        repo.findByStripeCustomerId(stripeCustomerId).ifPresent(u -> {
            u.setPlan(Plan.FREE);
            repo.save(u);
            log.info("User {} downgraded to FREE after subscription cancellation", u.getId());
        });
    }

    @Transactional
    public void setStripeCustomerId(String userId, String stripeCustomerId) {
        repo.findById(userId).ifPresent(u -> {
            u.setStripeCustomerId(stripeCustomerId);
            repo.save(u);
        });
    }

    @Transactional
    public User save(User user) {
        return repo.save(user);
    }

    /** Deletes all user data — job applications then the user record itself. */
    @Transactional
    public void deleteUser(String userId) {
        jobRepo.deleteByUserId(userId);
        repo.deleteById(userId);
    }
}
