package com.autoservicehub.projection;

/**
 * Job-card counts rolled up per mechanic over a date range (FR-REP-2).
 *
 * <p>{@code assignedJobs} counts every job card for the mechanic in the window;
 * {@code completedJobs} counts those that reached the terminal delivered state.
 *
 * <p>A mechanic with job cards but none completed yields
 * {@code completedJobs == 0} rather than being omitted, because the outer join
 * in the query is what produces that row. Reading that as "no data" rather than
 * "zero completed" would understate the figure.
 */
public interface MechanicJobCountProjection {

    Long getMechanicId();

    String getMechanicName();

    /** Matches {@code Mechanic.employeeCode}, which is a String, not a number. */
    String getEmployeeCode();

    Long getAssignedJobs();

    Long getCompletedJobs();
}
