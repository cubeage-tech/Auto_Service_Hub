package com.autoservicehub;

import com.autoservicehub.ai.AiRequest;
import com.autoservicehub.ai.AiResult;
import com.autoservicehub.ai.MechanicRecommendationService;
import com.autoservicehub.entity.Mechanic;
import com.autoservicehub.entity.MechanicSkill;
import com.autoservicehub.repository.JobCardRepository;
import com.autoservicehub.repository.MechanicRepository;
import com.autoservicehub.repository.MechanicSkillRepository;
import com.autoservicehub.repository.UserRepository;
import com.autoservicehub.service.ServiceAdvisorAccessService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class MechanicRecommendationTest {

    @Mock private JobCardRepository jobCardRepository;
    @Mock private MechanicRepository mechanicRepository;
    @Mock private MechanicSkillRepository mechanicSkillRepository;

    @Test
    void ranksActiveMechanicsFromPersistedSkillsExperienceAndWorkloadWithoutChangingJobs() {
        Mechanic experienced = mechanic(1L, "Experienced", 12);
        MechanicSkill engineSkill = new MechanicSkill();
        engineSkill.setSkillName("Engine Repair");
        experienced.setSkills(List.of(engineSkill));
        Mechanic junior = mechanic(2L, "Junior", 1);
        Mechanic inactive = mechanic(3L, "Inactive", 20);
        inactive.setStatus("INACTIVE");

        when(mechanicRepository.findAll()).thenReturn(List.of(junior, inactive, experienced));
        when(mechanicSkillRepository.findByMechanicIdOrderBySkillNameAsc(1L)).thenReturn(List.of(engineSkill));
        when(mechanicSkillRepository.findByMechanicIdOrderBySkillNameAsc(2L)).thenReturn(List.of());
        when(jobCardRepository.countActiveAssignments(1L)).thenReturn(1L);
        when(jobCardRepository.countActiveAssignments(2L)).thenReturn(0L);

        MechanicRecommendationService service = new MechanicRecommendationService(
            jobCardRepository, mechanicRepository, mechanicSkillRepository,
            new ServiceAdvisorAccessService(org.mockito.Mockito.mock(UserRepository.class)));
        ReflectionTestUtils.setField(service, "skillEnforcementEnabled", false);
        AiRequest request = new AiRequest();
        request.setRepairType("Engine Repair");
        request.setRequiredSkills(Set.of("Engine Repair"));

        AiResult result = service.recommend(request);
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> recommendations = (List<Map<String, Object>>) result.getDetails().get("recommendations");

        assertEquals(2, recommendations.size());
        assertEquals(1L, recommendations.get(0).get("mechanicId"));
        assertEquals(2L, recommendations.get(1).get("mechanicId"));
        verify(jobCardRepository, never()).save(org.mockito.ArgumentMatchers.any());
    }

    private Mechanic mechanic(Long id, String name, int experienceYears) {
        Mechanic mechanic = new Mechanic();
        mechanic.setId(id);
        mechanic.setName(name);
        mechanic.setExperienceYears(experienceYears);
        mechanic.setStatus("ACTIVE");
        return mechanic;
    }
}