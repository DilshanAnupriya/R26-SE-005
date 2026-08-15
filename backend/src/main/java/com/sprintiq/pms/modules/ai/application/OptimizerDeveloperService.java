package com.sprintiq.pms.modules.ai.application;

import com.sprintiq.pms.modules.ai.domain.Developer;
import com.sprintiq.pms.modules.ai.domain.DeveloperProfile;
import com.sprintiq.pms.modules.ai.domain.OptimizerDeveloperProfileRepository;
import com.sprintiq.pms.modules.ai.domain.OptimizerDeveloperRepository;
import com.sprintiq.pms.modules.ai.web.dto.OptimizerDtos.DeveloperRegistrationRequest;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Map;
import java.util.Optional;

import com.sprintiq.pms.tenancy.TenantContext;
import java.util.UUID;

@Service
public class OptimizerDeveloperService {

    @Autowired
    private OptimizerDeveloperRepository developerRepository;

    @Autowired
    private OptimizerDeveloperProfileRepository developerProfileRepository;

    @Autowired
    private org.springframework.jdbc.core.JdbcTemplate jdbcTemplate;


    public DeveloperProfile addDeveloper(DeveloperRegistrationRequest request) {
        UUID orgId = request.getOrganizationId();
        if (orgId == null) {
            orgId = TenantContext.organizationId().orElse(null);
        }

        if (orgId == null && request.getEmail() != null && !request.getEmail().isBlank()) {
            try {
                List<UUID> orgs = jdbcTemplate.query(
                        "SELECT m.organization_id FROM users u JOIN org_members m ON m.user_id = u.id WHERE lower(u.email) = lower(?) LIMIT 1",
                        (rs, rowNum) -> (UUID) rs.getObject(1),
                        request.getEmail().trim()
                );
                if (!orgs.isEmpty()) {
                    orgId = orgs.get(0);
                }
            } catch (Exception ignored) {
            }
        }

        if (orgId == null) {
            try {
                List<UUID> defaultOrgs = jdbcTemplate.query(
                        "SELECT id FROM organizations ORDER BY created_at ASC LIMIT 1",
                        (rs, rowNum) -> (UUID) rs.getObject(1)
                );
                if (!defaultOrgs.isEmpty()) {
                    orgId = defaultOrgs.get(0);
                }
            } catch (Exception ignored) {
            }
        }


        Developer developer = new Developer();
        developer.setOrganizationId(orgId);
        developer.setDev_id(request.getDevId());
        developer.setExperience_level(request.getExperienceLevel());
        developer.setSkill_frontend(request.getSkillFrontend());
        developer.setSkill_backend(request.getSkillBackend());
        developer.setSkill_db(request.getSkillDb());
        developer.setCurrent_workload(request.getCurrentWorkload());
        developer.setAvailability(request.getAvailability());
        developer.setCurrent_tasks(request.getCurrentTasks());
        developer.setConsistency(request.getConsistency());
        developer.setLearning_rate(request.getLearningRate());

        DeveloperProfile profile = new DeveloperProfile();
        profile.setOrganizationId(orgId);
        profile.setFirstName(request.getFirstName());
        profile.setLastName(request.getLastName());
        profile.setAge(request.getAge());
        profile.setEmail(request.getEmail());
        profile.setPassword(request.getPassword()); // In a real system, you'd hash this
        profile.setPhoneNumber(request.getPhoneNumber());
        profile.setSpecialization(request.getSpecialization());
        profile.setBio(request.getBio());

        profile.setDeveloperMetrics(developer); // Link them
        developer.setProfile(profile);

        return developerProfileRepository.save(profile);
    }


    public List<DeveloperProfile> getAllDeveloperProfiles() {
        return developerProfileRepository.findAll();
    }

    public List<Developer> getAllDevelopers() {
        return developerRepository.findAll();
    }

    public Developer getDeveloperById(Long id) {
        return developerRepository.findById(id).orElseThrow(() -> new RuntimeException("Developer not found"));
    }

    public DeveloperProfile getDeveloperProfileById(Long id) {
        return developerProfileRepository.findById(id).orElseThrow(() -> new RuntimeException("Profile not found"));
    }

    public DeveloperProfile getProfileByDeveloperId(Long developerId) {
        return developerProfileRepository.findByDeveloperMetricsId(developerId)
                .orElseThrow(() -> new RuntimeException("Profile for developer " + developerId + " not found"));
    }

    // =====================================================
    // UPDATE BEHAVIOR METRICS (FROM ML)
    // =====================================================
    public void updateBehaviorMetrics(List<Map<String, Object>> devs) {
        for (Map<String, Object> dev : devs) {
            Object devIdObj = dev.get("dev_id");
            if (devIdObj == null) continue;

            String devId = devIdObj.toString();
            Optional<Developer> optionalDev = developerRepository.findByDev_id(devId);

            if (optionalDev.isEmpty()) continue;

            Developer developer = optionalDev.get();

            // SAFE parsing (prevents crashes)
            developer.setConsistency(parseDoubleSafe(dev.get("consistency")));
            developer.setLearning_rate(parseDoubleSafe(dev.get("learning_rate")));

            developerRepository.save(developer);
        }
    }

    // =====================================================
    // SAFE DOUBLE PARSER
    // =====================================================
    private double parseDoubleSafe(Object value) {
        try {
            if (value == null) return 0.0;
            return Double.parseDouble(value.toString());
        } catch (Exception e) {
            return 0.0;
        }
    }
}
