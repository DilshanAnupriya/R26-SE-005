package com.sprintiq.pms.modules.ai.web;

import com.sprintiq.pms.modules.ai.domain.Developer;
import com.sprintiq.pms.modules.ai.domain.DeveloperProfile;
import com.sprintiq.pms.modules.ai.application.OptimizerDeveloperService;
import com.sprintiq.pms.modules.ai.web.dto.OptimizerDtos.DeveloperRegistrationRequest;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/api/developers")
@CrossOrigin("*")
public class OptimizerDeveloperController {

    @Autowired
    private OptimizerDeveloperService developerService;

    @PostMapping
    public DeveloperProfile addDeveloper(@RequestBody DeveloperRegistrationRequest request) {
        return developerService.addDeveloper(request);
    }

    @GetMapping
    public List<Developer> getAll() {
        return developerService.getAllDevelopers();
    }

    @GetMapping("/profiles")
    public List<DeveloperProfile> getAllProfiles() {
        return developerService.getAllDeveloperProfiles();
    }

    @GetMapping("/{id}")
    public Developer getById(@PathVariable Long id) {
        return developerService.getDeveloperById(id);
    }

    @GetMapping("/profile/{id}")
    public DeveloperProfile getProfileById(@PathVariable Long id) {
        return developerService.getDeveloperProfileById(id);
    }

    @GetMapping("/{id}/profile")
    public DeveloperProfile getProfileByDevId(@PathVariable Long id) {
        return developerService.getProfileByDeveloperId(id);
    }
}
