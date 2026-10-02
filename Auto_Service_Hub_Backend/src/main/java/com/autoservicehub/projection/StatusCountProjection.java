package com.autoservicehub.projection;

/**
 * A job-card status together with how many job cards carry it.
 *
 * <p>Interface projection so a status breakdown is one aggregate query instead
 * of loading every job card and counting in memory.
 */
public interface StatusCountProjection {

    String getStatus();

    /**
     * Row count for that status. Aliased {@code statusCount} rather than
     * {@code count} in the queries, because COUNT is a JPQL function name and a
     * bare alias of that name is not portable across the databases this project
     * runs on.
     */
    Long getStatusCount();
}
