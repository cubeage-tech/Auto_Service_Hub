package com.autoservicehub.ai;

import com.autoservicehub.entity.JobCard;
import com.autoservicehub.entity.Mechanic;
import com.autoservicehub.entity.MechanicSkill;
import com.autoservicehub.exception.BusinessRuleException;
import com.autoservicehub.exception.ResourceNotFoundException;
import com.autoservicehub.repository.JobCardRepository;
import com.autoservicehub.repository.MechanicRepository;
import com.autoservicehub.repository.MechanicSkillRepository;
import com.autoservicehub.service.ServiceAdvisorAccessService;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class MechanicRecommendationService {

    private final JobCardRepository jobCardRepository;
    private final MechanicRepository mechanicRepository;
    private final MechanicSkillRepository skillRepository;
    private final ServiceAdvisorAccessService advisorAccessService;

    @Value("${app.mechanics.skill-enforcement:true}")
    private boolean skillEnforcementEnabled;

    public AiResult recommend(AiRequest request) {
        JobCard jobCard = request.getJobCardId() == null ? null : jobCardRepository.findById(request.getJobCardId())
                .orElseThrow(() -> new ResourceNotFoundException("JobCard not found: " + request.getJobCardId()));
        advisorAccessService.assertCanAccess(jobCard);
        String repairType = jobCard == null ? request.getRepairType() : jobCard.getServiceType();
        Set<String> requiredSkills = jobCard == null
                ? normalizeSkills(request.getRequiredSkills())
                : normalizeSkills(jobCard.getRequiredSkills());
        if ((repairType == null || repairType.isBlank()) && requiredSkills.isEmpty()) {
            throw new BusinessRuleException("Provide a job card, repair type, or required skills for mechanic recommendations.");
        }

        String normalizedRepairType = normalize(repairType);
        List<Map<String, Object>> recommendations = new ArrayList<>();
        mechanicRepository.findAll().stream()
                .filter(mechanic -> "ACTIVE".equalsIgnoreCase(mechanic.getStatus()))
                .forEach(mechanic -> {
                    Set<String> mechanicSkills = skillRepository.findByMechanicIdOrderBySkillNameAsc(mechanic.getId()).stream()
                            .map(MechanicSkill::getSkillName)
                            .filter(skill -> skill != null && !skill.isBlank())
                            .collect(Collectors.toCollection(LinkedHashSet::new));
                    Set<String> normalizedMechanicSkills = mechanicSkills.stream()
                            .map(this::normalize).collect(Collectors.toSet());
                    Set<String> matchedRequired = requiredSkills.stream()
                            .filter(normalizedMechanicSkills::contains).collect(Collectors.toCollection(LinkedHashSet::new));

                    if (skillEnforcementEnabled && !requiredSkills.isEmpty() && matchedRequired.size() != requiredSkills.size()) {
                        return;
                    }

                    List<String> repairTypeMatches = mechanicSkills.stream()
                            .filter(skill -> matchesRepairType(skill, normalizedRepairType))
                            .toList();
                    int experience = Math.max(0, mechanic.getExperienceYears() == null ? 0 : mechanic.getExperienceYears());
                    long workload = jobCardRepository.countActiveAssignments(mechanic.getId());
                    int score = score(requiredSkills.size(), matchedRequired.size(), !repairTypeMatches.isEmpty(), experience, workload);
                    List<String> reasons = reasons(requiredSkills, matchedRequired, repairTypeMatches, experience, workload, repairType);

                    Map<String, Object> recommendation = new LinkedHashMap<>();
                    recommendation.put("mechanicId", mechanic.getId());
                    recommendation.put("mechanicName", mechanic.getName());
                    recommendation.put("score", score);
                    recommendation.put("reasons", reasons);
                    recommendation.put("matchedSkills", matchedRequired);
                    recommendation.put("experienceYears", mechanic.getExperienceYears());
                    recommendation.put("activeWorkload", workload);
                    recommendations.add(recommendation);
                });

        recommendations.sort(Comparator
                .comparingInt((Map<String, Object> recommendation) -> (Integer) recommendation.get("score"))
                .reversed()
                .thenComparingLong(recommendation -> (Long) recommendation.get("mechanicId")));

        Map<String, Object> details = new LinkedHashMap<>();
        details.put("jobCardId", jobCard == null ? null : jobCard.getId());
        details.put("repairType", repairType);
        details.put("requiredSkills", requiredSkills);
        details.put("skillEnforcementEnabled", skillEnforcementEnabled);
        details.put("recommendations", recommendations);
        int topScore = recommendations.isEmpty() ? 0 : (Integer) recommendations.get(0).get("score");
        String summary = recommendations.isEmpty()
                ? "No active mechanic satisfies the required skill restrictions."
                : "Ranked " + recommendations.size() + " active mechanics for " + (repairType == null ? "the requested skills" : repairType) + ".";
        return new AiResult(AiFeatureType.MECHANIC_ASSIGNMENT, summary, BigDecimal.valueOf(topScore, 2), details, true);
    }

    private int score(int requiredSkillCount, int matchedSkillCount, boolean repairTypeMatch, int experience, long workload) {
        int skillScore = requiredSkillCount == 0 ? 0 : (int) Math.round(45.0 * matchedSkillCount / requiredSkillCount);
        int repairTypeScore = repairTypeMatch ? 25 : 0;
        int experienceScore = Math.min(experience, 20);
        int workloadScore = (int) (10 / (1 + workload));
        return skillScore + repairTypeScore + experienceScore + workloadScore;
    }

    private List<String> reasons(Set<String> requiredSkills, Set<String> matchedSkills, List<String> repairTypeMatches,
                                 int experience, long workload, String repairType) {
        List<String> reasons = new ArrayList<>();
        if (!matchedSkills.isEmpty()) reasons.add("Required skills available: " + String.join(", ", matchedSkills));
        if (requiredSkills.isEmpty()) reasons.add("No explicit required skills are configured for this repair.");
        if (!repairTypeMatches.isEmpty()) reasons.add("Stored skill names matching repair type '" + repairType + "': " + String.join(", ", repairTypeMatches));
        else if (repairType != null && !repairType.isBlank()) reasons.add("No stored skill name matches repair type '" + repairType + "'.");
        reasons.add("Recorded experience: " + experience + " years.");
        reasons.add("Current active assigned jobs: " + workload + ".");
        return reasons;
    }

    private boolean matchesRepairType(String skill, String normalizedRepairType) {
        String normalizedSkill = normalize(skill);
        return !normalizedRepairType.isBlank() && normalizedSkill.length() >= 3
                && (normalizedRepairType.contains(normalizedSkill) || normalizedSkill.contains(normalizedRepairType));
    }

    private Set<String> normalizeSkills(Set<String> skills) {
        if (skills == null) return Set.of();
        Set<String> normalized = new LinkedHashSet<>();
        for (String skill : skills) {
            if (skill == null || skill.isBlank()) throw new BusinessRuleException("Required skill names must not be blank.");
            normalized.add(skill.trim());
        }
        return normalized;
    }

    private String normalize(String value) {
        return value == null ? "" : value.trim().toLowerCase(Locale.ROOT);
    }
}