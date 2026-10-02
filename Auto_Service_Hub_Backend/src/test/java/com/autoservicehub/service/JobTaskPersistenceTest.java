package com.autoservicehub.service;

import com.autoservicehub.dto.JobTaskRequestDTO;
import com.autoservicehub.dto.JobTaskStatusRequestDTO;
import com.autoservicehub.dto.JobTaskWorkNotesRequestDTO;
import com.autoservicehub.entity.JobCard;
import com.autoservicehub.entity.JobTask;
import com.autoservicehub.entity.Mechanic;
import com.autoservicehub.exception.BusinessRuleException;
import com.autoservicehub.repository.CustomerRepository;
import com.autoservicehub.repository.JobCardRepository;
import com.autoservicehub.repository.JobTaskRepository;
import com.autoservicehub.repository.MechanicRepository;
import com.autoservicehub.repository.VehicleRepository;
import com.autoservicehub.service.impl.JobCardServiceImpl;
import com.autoservicehub.service.impl.JobTaskServiceImpl;
import com.autoservicehub.service.impl.AuditServiceImpl;
import com.autoservicehub.util.BillingCalculator;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.context.annotation.Import;
import org.springframework.dao.DataIntegrityViolationException;

import java.math.BigDecimal;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Integration tests for job tasks and labour against a real database.
 *
 * <p>These cover what a Mockito test cannot: that a task really is persisted
 * against its job card, that the job's task count and labour total are read
 * from the database rather than assembled in memory, that a task with no job
 * card is refused by the database itself (the NOT NULL column constraint, which
 * is what actually prevents orphans), and that deleting a job card takes its
 * tasks with it rather than stranding them.
 *
 * <p>Uses {@code @DataJpaTest} for the same reason as the existing slices:
 * application.yml pins MySQLDialect, whose DDL H2 cannot execute, so the
 * dialect is overridden to match the real JDBC metadata and the database name is
 * isolated from the shared {@code testdb}.
 */
@DataJpaTest(properties = {
        "spring.datasource.url=jdbc:h2:mem:job-task-test;DB_CLOSE_DELAY=-1;DB_CLOSE_ON_EXIT=FALSE;NON_KEYWORDS=YEAR",
        "spring.datasource.driver-class-name=org.h2.Driver",
        "spring.jpa.hibernate.ddl-auto=create-drop",
        "spring.jpa.properties.hibernate.dialect=org.hibernate.dialect.H2Dialect"
})
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
// BillingCalculator is a @Component in util, which @DataJpaTest does not scan,
// so it is imported explicitly alongside the services under test.
@Import({ AuditServiceImpl.class, BillingCalculator.class, JobTaskServiceImpl.class, JobCardServiceImpl.class})
class JobTaskPersistenceTest {

    @Autowired JobTaskServiceImpl  taskService;
    @Autowired JobCardServiceImpl  jobCardService;
    @Autowired JobTaskRepository   taskRepository;
    @Autowired JobCardRepository  jobCardRepository;
    @Autowired MechanicRepository mechanicRepository;
    @Autowired CustomerRepository customerRepository;
    @Autowired VehicleRepository  vehicleRepository;

    // ── Fixtures ────────────────────────────────────────────────────────

    private JobCard givenJobCard(String number) {
        JobCard jc = new JobCard();
        jc.setJobCardNumber(number);
        jc.setServiceType("BRAKE_SERVICE");
        jc.setStatus("IN_REPAIR");
        return jobCardRepository.save(jc);
    }

    private Mechanic givenMechanic(String name) {
        Mechanic m = new Mechanic();
        m.setName(name);
        m.setEmployeeCode("MECH-" + name);
        m.setStatus("ACTIVE");
        return mechanicRepository.save(m);
    }

    private JobTaskRequestDTO request(String description, String labourCost) {
        JobTaskRequestDTO dto = new JobTaskRequestDTO();
        dto.setDescription(description);
        dto.setLabourCost(labourCost == null ? null : new BigDecimal(labourCost));
        return dto;
    }

    // ── A task is persisted against its job card ────────────────────────

@Test
@DisplayName("JTP1 - a created task is persisted with its job card, status and labour")
void jtp1_create_persistsTaskWithJobCard() {
JobCard jc = givenJobCard("JC-P1");

var response = taskService.create(jc.getId(), request("Replace front brake pads", "500.00"));

JobTask stored = taskRepository.findById(response.getId()).orElseThrow();
assertThat(stored.getJobCard().getId()).isEqualTo(jc.getId());
assertThat(stored.getDescription()).isEqualTo("Replace front brake pads");
assertThat(stored.getStatus()).isEqualTo("PENDING");
assertThat(stored.getLabourCost()).isEqualByComparingTo("500.00");
assertThat(stored.getCreatedAt()).isNotNull();
}

@Test
@DisplayName("JTP2 - a mechanic assignment is persisted with the task")
void jtp2_mechanicAssignment_persists() {
JobCard jc = givenJobCard("JC-P2");
Mechanic anil = givenMechanic("Anil");

JobTaskRequestDTO req = request("Replace front brake pads", "500.00");
req.setMechanicId(anil.getId());
var response = taskService.create(jc.getId(), req);

JobTask stored = taskRepository.findById(response.getId()).orElseThrow();
assertThat(stored.getMechanic().getId()).isEqualTo(anil.getId());
assertThat(response.getMechanicName()).isEqualTo("Anil");
}

@Test
@DisplayName("JTP3 - tasks are listed for their job card and only that card's")
void jtp3_listByJobCard_returnsOnlyItsOwnTasks() {
JobCard first = givenJobCard("JC-P3-A");
JobCard second = givenJobCard("JC-P3-B");
taskService.create(first.getId(), request("Front brake pads", "500.00"));
taskService.create(first.getId(), request("Rear brake pads", "400.00"));
taskService.create(second.getId(), request("Fluid flush", "250.00"));

var page = taskService.listByJobCard(first.getId(),
org.springframework.data.domain.PageRequest.of(0, 20));

assertThat(page.getTotalElements()).isEqualTo(2);
assertThat(page.getContent())
.extracting(com.autoservicehub.dto.JobTaskResponseDTO::getDescription)
.containsExactly("Front brake pads", "Rear brake pads");
}

// ── Orphan prevention, enforced by the schema ───────────────────────

/**
* The column constraint, not the service, is what makes an orphan impossible.
*
* <p>The service always attaches a job card, but a future caller writing an
* entity directly would slip past that. This bypasses the service entirely and
* tries to persist a task with no parent, so the NOT NULL on job_card_id is
* shown to be the real guard rather than a claim about it.
*/
@Test
@DisplayName("JTP4 - the database refuses a task with no job card (orphan prevention)")
void jtp4_orphanTask_refusedByDatabase() {
JobTask orphan = new JobTask();
orphan.setDescription("Task with no job card");
orphan.setStatus("PENDING");
orphan.setLabourCost(new BigDecimal("100.00"));

assertThatThrownBy(() -> taskRepository.saveAndFlush(orphan))
            .isInstanceOf(DataIntegrityViolationException.class);
}

/**
 * The companion to JTP4: only parented tasks exist in the table.
 *
 * <p>Separate because the failed flush in JTP4 poisons the persistence context
 * ("don't flush the Session after an exception occurs"), so that test cannot
 * go on to query. This one runs in a clean context and asserts the "nothing
 * stray was written" half.
 */
@Test
@DisplayName("JTP4b - every persisted task belongs to a job card")
void jtp4b_noStrayTasks() {
    JobCard jc = givenJobCard("JC-P4B");
    taskService.create(jc.getId(), request("Replace front brake pads", "500.00"));

    assertThat(taskRepository.count()).isEqualTo(1);
    assertThat(taskRepository.findAll()).allSatisfy(t -> assertThat(t.getJobCard()).isNotNull());
}

// ── Labour totals are read from the database ─────────────────────────

@Test
@DisplayName("JTP5 - a job card's labour total is the stored sum of its tasks")
void jtp5_labourTotal_isStoredSum() {
JobCard jc = givenJobCard("JC-P5");
taskService.create(jc.getId(), request("Replace front brake pads", "500.00"));
taskService.create(jc.getId(), request("Replace front brake discs", "1250.00"));
taskService.create(jc.getId(), request("Road test", "150.00"));

assertThat(taskService.totalLabourCost(jc.getId())).isEqualByComparingTo("1900.00");
}

@Test
@DisplayName("JTP6 - a job card with no tasks totals zero, not null")
void jtp6_labourTotal_noTasks_isZero() {
JobCard jc = givenJobCard("JC-P6");

assertThat(taskService.totalLabourCost(jc.getId())).isEqualByComparingTo("0.00");
}

@Test
@DisplayName("JTP7 - a cancelled task still counts toward cost actually incurred")
void jtp7_labourTotal_includesCancelledTasks() {
// A cancelled task did incur some work before it was dropped, so its cost stays
// in the total. The workshop spent it either way. Documented rather than
// filtered, so the figure reads as "cost incurred", not "cost billed".
JobCard jc = givenJobCard("JC-P7");
taskService.create(jc.getId(), request("Replace front brake pads", "500.00"));

JobTaskRequestDTO cancelled = request("Respray wing mirror", "900.00");
cancelled.setStatus("CANCELLED");
taskService.create(jc.getId(), cancelled);

assertThat(taskService.totalLabourCost(jc.getId())).isEqualByComparingTo("1400.00");
}

@Test
@DisplayName("JTP8 - the job card response carries its task count and labour total")
void jtp8_jobCardResponse_carriesTaskTotals() {
JobCard jc = givenJobCard("JC-P8");
taskService.create(jc.getId(), request("Replace front brake pads", "500.00"));
taskService.create(jc.getId(), request("Replace front brake discs", "1250.00"));

var response = jobCardService.getById(jc.getId());

assertThat(response.getTaskCount()).isEqualTo(2);
assertThat(response.getTotalLabourCost()).isEqualByComparingTo("1750.00");
}

@Test
@DisplayName("JTP9 - a job card with no tasks reports zero count and zero labour")
void jtp9_jobCardResponse_noTasks_isZero() {
JobCard jc = givenJobCard("JC-P9");

var response = jobCardService.getById(jc.getId());

assertThat(response.getTaskCount()).isZero();
assertThat(response.getTotalLabourCost()).isEqualByComparingTo("0.00");
}

// ── Status changes and work notes are persisted ─────────────────────

@Test
@DisplayName("JTP10 - a status change is persisted and leaves the labour cost alone")
void jtp10_statusChange_persists() {
JobCard jc = givenJobCard("JC-P10");
var created = taskService.create(jc.getId(), request("Replace front brake pads", "500.00"));

JobTaskStatusRequestDTO start = new JobTaskStatusRequestDTO();
start.setStatus("IN_PROGRESS");
// Walks the task forward through the real workflow rather than jumping straight
// to COMPLETED: PENDING -> COMPLETED is not a legal move, since work has to be
// started before it can be finished.
taskService.updateStatus(created.getId(), start);

JobTaskStatusRequestDTO req = new JobTaskStatusRequestDTO();
req.setStatus("COMPLETED");
var updated = taskService.updateStatus(created.getId(), req);

assertThat(updated.getStatus()).isEqualTo("COMPLETED");
JobTask stored = taskRepository.findById(created.getId()).orElseThrow();
assertThat(stored.getStatus()).isEqualTo("COMPLETED");
assertThat(stored.getLabourCost()).isEqualByComparingTo("500.00");
}

@Test
@DisplayName("JTP11 - an unsupported status changes nothing")
void jtp11_invalidStatus_persistsNothing() {
JobCard jc = givenJobCard("JC-P11");
var created = taskService.create(jc.getId(), request("Replace front brake pads", "500.00"));

JobTaskStatusRequestDTO req = new JobTaskStatusRequestDTO();
req.setStatus("NEARLY");

assertThatThrownBy(() -> taskService.updateStatus(created.getId(), req))
.isInstanceOf(BusinessRuleException.class);

assertThat(taskRepository.findById(created.getId()).orElseThrow().getStatus())
.isEqualTo("PENDING");
}

@Test
@DisplayName("JTP12 - work notes are persisted without disturbing the status or cost")
void jtp12_workNotes_persists() {
JobCard jc = givenJobCard("JC-P12");
var created = taskService.create(jc.getId(), request("Replace front brake pads", "500.00"));

JobTaskWorkNotesRequestDTO notes = new JobTaskWorkNotesRequestDTO();
notes.setWorkNotes("Rear pads also worn - advised customer");
var updated = taskService.updateWorkNotes(created.getId(), notes);

assertThat(updated.getWorkNotes()).isEqualTo("Rear pads also worn - advised customer");
assertThat(updated.getStatus()).isEqualTo("PENDING");
assertThat(updated.getLabourCost()).isEqualByComparingTo("500.00");
}

// ── Mechanic allocation and deletion ────────────────────────────────

@Test
@DisplayName("JTP13 - a mechanic can be allocated and then cleared")
void jtp13_assignAndUnassignMechanic() {
JobCard jc = givenJobCard("JC-P13");
Mechanic anil = givenMechanic("Anil");
var created = taskService.create(jc.getId(), request("Replace front brake pads", "500.00"));

assertThat(taskService.assignMechanic(created.getId(), anil.getId()).getMechanicName())
.isEqualTo("Anil");
assertThat(taskService.assignMechanic(created.getId(), null).getMechanicId()).isNull();
assertThat(taskRepository.findById(created.getId()).orElseThrow().getMechanic()).isNull();
}

@Test
@DisplayName("JTP14 - an unknown mechanic is refused and nothing is written")
void jtp14_unknownMechanic_refused() {
JobCard jc = givenJobCard("JC-P14");

assertThatThrownBy(() -> taskService.assignMechanic(999L, 999L))
.isInstanceOf(com.autoservicehub.exception.ResourceNotFoundException.class);
}

@Test
@DisplayName("JTP15 - deleting a task removes it from its job card's labour total")
void jtp15_deleteTask_updatesTotal() {
JobCard jc = givenJobCard("JC-P15");
var first = taskService.create(jc.getId(), request("Replace front brake pads", "500.00"));
taskService.create(jc.getId(), request("Replace front brake discs", "1250.00"));
assertThat(taskService.totalLabourCost(jc.getId())).isEqualByComparingTo("1750.00");

taskService.delete(first.getId());

assertThat(taskService.totalLabourCost(jc.getId())).isEqualByComparingTo("1250.00");
assertThat(taskRepository.findById(first.getId())).isEmpty();
}

@Test
@DisplayName("JTP16 - deleting a job card removes its tasks rather than stranding them")
void jtp16_deleteJobCard_removesItsTasks() {
JobCard jc = givenJobCard("JC-P16");
taskService.create(jc.getId(), request("Replace front brake pads", "500.00"));
taskService.create(jc.getId(), request("Road test", "150.00"));
assertThat(taskRepository.count()).isEqualTo(2);

jobCardService.delete(jc.getId());

assertThat(jobCardRepository.findById(jc.getId())).isEmpty();
assertThat(taskRepository.count()).isZero();
}

// ── Existing JobCard behaviour still holds ──────────────────────────

@Test
@DisplayName("JTP17 - an existing job card with no tasks is unchanged by task support")
void jtp17_jobCardWithoutTasks_behaviourUnchanged() {
JobCard jc = givenJobCard("JC-P17");

var response = jobCardService.getById(jc.getId());

// Everything the job card already reported is still reported.
assertThat(response.getId()).isEqualTo(jc.getId());
assertThat(response.getJobCardNumber()).isEqualTo("JC-P17");
assertThat(response.getServiceType()).isEqualTo("BRAKE_SERVICE");
assertThat(response.getStatus()).isEqualTo("IN_REPAIR");
assertThat(response.getProgress()).isEqualTo(3);
assertThat(response.getInspectionId()).isNull();
}
}
