package com.sprintiq.pms.config;

import com.sprintiq.pms.modules.ai.domain.Developer;
import com.sprintiq.pms.modules.ai.domain.DeveloperProfile;
import com.sprintiq.pms.modules.ai.domain.Sprint;
import com.sprintiq.pms.modules.ai.domain.Task;
import com.sprintiq.pms.modules.ai.domain.TaskStatus;
import com.sprintiq.pms.modules.ai.domain.OptimizerDeveloperProfileRepository;
import com.sprintiq.pms.modules.ai.domain.OptimizerDeveloperRepository;
import com.sprintiq.pms.modules.ai.domain.OptimizerSprintRepository;
import com.sprintiq.pms.modules.ai.domain.OptimizerTaskRepository;
import com.sprintiq.pms.modules.ai.domain.OptimizerAllocationRepository;

import org.springframework.boot.CommandLineRunner;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;

@Configuration
@ConditionalOnProperty(name = "app.seed.optimizer", havingValue = "true")
public class DataLoader {

    @Bean
    CommandLineRunner initDatabase(
            OptimizerDeveloperProfileRepository profileRepo,
            OptimizerDeveloperRepository devRepo,
            OptimizerTaskRepository taskRepo,
            OptimizerSprintRepository sprintRepo,
            OptimizerAllocationRepository allocationRepo
    ) {
        return args -> {
            allocationRepo.deleteAll();
            taskRepo.deleteAll();
            profileRepo.deleteAll();
            devRepo.deleteAll();
            sprintRepo.deleteAll();

            seedDevelopers(profileRepo, devRepo);

            List<Sprint> sprints = seedSprints(sprintRepo);

            seedTasks(taskRepo, sprints);
        };
    }

    // =====================================================
    // SEED DEVELOPERS
    // =====================================================

    private void seedDevelopers(
            OptimizerDeveloperProfileRepository profileRepo,
            OptimizerDeveloperRepository devRepo
    ) {

        if (devRepo.count() > 0) {
            System.out.println("Developers already exist. Skipping seeding...");
            return;
        }

        Random rand = new Random();

        for (int i = 1; i <= 5; i++) {

            Developer dev = new Developer();

            dev.setDev_id("D" + i);

            int exp = rand.nextInt(3) + 1;

            dev.setExperience_level(exp);

            dev.setSkill_frontend(rand.nextInt(5) + 1);
            dev.setSkill_backend(rand.nextInt(5) + 1);
            dev.setSkill_db(rand.nextInt(5) + 1);

            dev.setConsistency(0.6 + (1.0 - 0.6) * rand.nextDouble());

            dev.setLearning_rate(
                    0.01 + (0.1 - 0.01) * rand.nextDouble()
            );

            int tasks = rand.nextInt(6) + 1;

            dev.setCurrent_tasks(tasks);

            int workload = tasks * (rand.nextInt(6) + 3);

            dev.setCurrent_workload(workload);

            double availability =
                    Math.max(0,
                            100 - workload * (3 + rand.nextDouble() * 3)
                    );

            dev.setAvailability(availability);

            // =====================================================
            // PROFILE
            // =====================================================

            DeveloperProfile profile = new DeveloperProfile();

            profile.setFirstName("Developer");
            profile.setLastName(String.valueOf(i));

            profile.setAge(rand.nextInt(35) + 22);

            profile.setEmail("dev" + i + "@agileai.com");

            profile.setPhoneNumber(
                    "123-456-78" + String.format("%02d", i)
            );

            profile.setDeveloperMetrics(dev);

            dev.setProfile(profile);

            profileRepo.save(profile);
        }

        System.out.println("✅ 5 Developers inserted into database!");
    }

    // =====================================================
    // SEED SPRINTS
    // =====================================================

    private List<Sprint> seedSprints(OptimizerSprintRepository repo) {

        if (repo.count() > 0) {
            System.out.println("Sprints already exist. Skipping seeding...");
            return repo.findAll();
        }

        List<Sprint> sprints = new ArrayList<>();

        sprints.add(
                repo.save(
                        Sprint.builder()
                                .sprintId("S-2024-01")
                                .startDate(LocalDate.now().minusDays(14))
                                .endDate(LocalDate.now())
                                .status("COMPLETED")
                                .build()
                )
        );

        sprints.add(
                repo.save(
                        Sprint.builder()
                                .sprintId("S-2024-02")
                                .startDate(LocalDate.now())
                                .endDate(LocalDate.now().plusDays(14))
                                .status("ACTIVE")
                                .build()
                )
        );

        sprints.add(
                repo.save(
                        Sprint.builder()
                                .sprintId("S-2024-03")
                                .startDate(LocalDate.now().plusDays(14))
                                .endDate(LocalDate.now().plusDays(28))
                                .status("PLANNED")
                                .build()
                )
        );

        System.out.println("✅ 3 Sprints inserted into database!");

        return sprints;
    }

    // =====================================================
    // SEED TASKS
    // =====================================================

    private void seedTasks(
        OptimizerTaskRepository repo,
        List<Sprint> sprints
    ) {

        if (repo.count() > 0) {
            System.out.println("Tasks already exist. Skipping seeding...");
            return;
        }

        Random rand = new Random();

        String[][] taskData = {
                {"Fix Login Authentication Bug", "4", "5", "1", "5", "3"},
                {"Implement Dashboard Charts", "3", "8", "5", "2", "1"},
                {"Optimize Database Queries for Reports", "5", "13", "0", "3", "5"},
                {"Develop User Profile API", "2", "3", "1", "4", "2"},
                {"Design Responsive Landing Page", "2", "5", "5", "0", "0"},
                {"Integrate Stripe Payment Gateway", "5", "21", "2", "5", "4"},
                {"Build Automated PDF Report Generator", "3", "8", "1", "5", "2"},
                {"Implement WebSocket Notifications", "4", "13", "4", "5", "1"},
                {"Refactor Legacy Module", "4", "13", "2", "4", "2"},
                {"Setup CI/CD Pipeline", "5", "21", "1", "5", "3"},
                {"Fix UI Issues", "2", "3", "5", "1", "1"},
                {"Add MFA Support", "4", "8", "2", "5", "4"}
        };

        for (int i = 0; i < taskData.length; i++) {

            String[] data = taskData[i];

            Task task = new Task();

            task.setTask_type(data[0]);
            task.setTask_complexity(Integer.parseInt(data[1]));
            task.setStory_points(Integer.parseInt(data[2]));
            task.setReq_frontend(Integer.parseInt(data[3]));
            task.setReq_backend(Integer.parseInt(data[4]));
            task.setReq_db(Integer.parseInt(data[5]));

            task.setAmbiguity(rand.nextDouble());
            task.setDependency_risk(rand.nextDouble());

            // assign sprint evenly across all sprints
            Sprint sprint = sprints.get(i % sprints.size());
            task.setSprint(sprint);

            // =====================================================
            // ONLY COMPLETED SPRINTS GET METRICS
            // =====================================================

            if ("COMPLETED".equals(sprint.getStatus())) {

                task.setCompletion_time(2 + rand.nextDouble() * 10);
                task.setDefects(rand.nextInt(4));
                task.setVelocity_contribution(task.getStory_points());
                task.setBlocked(rand.nextDouble() < 0.2);
                task.setReopened(rand.nextDouble() < 0.1);
                task.setStatus(TaskStatus.COMPLETED);

            } else {

                // Active / Planned sprints
                task.setCompletion_time(0);
                task.setDefects(0);
                task.setVelocity_contribution(0);
                task.setBlocked(false);
                task.setReopened(false);
                task.setStatus(TaskStatus.PLANNED);
            }

            task.setCreated_at(LocalDateTime.now().minusDays(rand.nextInt(30)));
            task.setStarted_at(LocalDateTime.now().minusDays(rand.nextInt(20)));
            task.setCompleted_at(null);

            repo.save(task);
        }

        System.out.println("✅ Tasks seeded with proper ML separation (historical vs future)");
    }
}