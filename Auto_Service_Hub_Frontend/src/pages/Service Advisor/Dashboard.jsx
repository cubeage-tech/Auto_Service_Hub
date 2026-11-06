import React, { useEffect, useMemo, useState } from "react";

import {
  getAiInsights,
  getDashboardJobCards,
  getDashboardSummary,
  getInvoices,
  getMechanics,
  getRevenueReport,
} from "../../services/dashboardService";

import "./ServiceAdvisor.css";


/* =========================================================
   FORMATTERS
   ========================================================= */

const dateString = (date) => {
  const year = date.getFullYear();
  const month = String(date.getMonth() + 1).padStart(2, "0");
  const day = String(date.getDate()).padStart(2, "0");
  return `${year}-${month}-${day}`;
};

const addDays = (date, amount) => {
  const result = new Date(date);
  result.setDate(result.getDate() + amount);
  return result;
};

/* Statuses the backend treats as still open on the workshop floor.
   JobCardResponseDTO.status is the source; these mirror the
   Garage Owner dashboard so both screens agree. */
const isActiveJob = (job) =>
  ["RECEIVED", "INSPECTION", "IN_REPAIR", "QUALITY_CHECK"].includes(
    String(job.status || "").trim().toUpperCase().replace(/[\s-]+/g, "_")
  );

const formatCurrency = (value) =>
  value === null || value === undefined
    ? "—"
    : new Intl.NumberFormat("en-IN", {
        style: "currency",
        currency: "INR",
        maximumFractionDigits: 0,
      }).format(Number(value));

const readableStatus = (status) =>
  (status || "Unknown")
    .toLowerCase()
    .split(/[_\s]+/)
    .map((word) => word.charAt(0).toUpperCase() + word.slice(1))
    .join(" ");

/* Maps a backend status onto one of the existing .status-* CSS classes. */
const statusClass = (status) => {
  const normalized = (status || "").toUpperCase().replace(/[\s-]+/g, "_");
  if (normalized.includes("REPAIR")) return "status-repair";
  if (normalized.includes("QUALITY") || normalized === "QC") return "status-qc";
  if (normalized.includes("DELIVER")) return "status-delivered";
  if (normalized.includes("INSPECT")) return "status-inspection";
  if (normalized.includes("RECEIV")) return "status-received";
  return "";
};

/* ETA. JobCardServiceImpl copies estimatedDelivery straight from the entity,
   so it is legitimately absent on a job card that has not been scheduled.
   A missing or unparseable value renders a dash rather than a guess. */
const formatDateTime = (value) => {
  if (!value) return "—";
  const date = new Date(value);
  return Number.isNaN(date.getTime()) ? "—" : date.toLocaleString("en-IN", {
    day: "2-digit",
    month: "short",
    hour: "2-digit",
    minute: "2-digit",
  });
};

/* AiInsightDTO.confidence is stored from AiResult.confidence, which the
   backend documents as a 0.00-1.00 score. Values above 1 are shown verbatim
   rather than rescaled, so an unexpected scale is never silently rewritten. */
const formatConfidence = (value) => {
  if (value === null || value === undefined || value === "") return null;
  const number = Number(value);
  if (!Number.isFinite(number) || number < 0) return null;
  if (number > 1) return String(number);
  return `${Math.round(number * 100)}%`;
};

/* Resolves a mechanic to the same key job cards use, preferring the id. */
const mechanicKey = (mechanic) =>
  mechanic.id != null
    ? `id:${mechanic.id}`
    : mechanic.name
      ? `name:${String(mechanic.name).trim().toLowerCase()}`
      : null;

const jobMechanicKey = (job) =>
  job.mechanicId != null
    ? `id:${job.mechanicId}`
    : job.mechanicName
      ? `name:${String(job.mechanicName).trim().toLowerCase()}`
      : null;


/* =========================================================
   DASHBOARD
   Reads only the real dashboard/report APIs. Nothing on this
   page invents a figure: a section that cannot load shows the
   reason, and a section with nothing to show says so.
   ========================================================= */

export default function Dashboard() {
  const today = useMemo(() => new Date(), []);
  const toDate = dateString(today);

  const weekStart = useMemo(() => {
    const daysSinceMonday = (today.getDay() + 6) % 7;
    return addDays(today, -daysSinceMonday);
  }, [today]);

  const fromDate = dateString(weekStart);
  const weekTo = dateString(addDays(weekStart, 6));
  const insightFrom = useMemo(() => dateString(addDays(today, -29)), [today]);

  const weekDays = useMemo(
    () => Array.from({ length: 7 }, (_, index) => addDays(weekStart, index)),
    [weekStart]
  );

  const [refreshKey, setRefreshKey] = useState(0);

  const [dashboard, setDashboard] = useState({
    summary: { status: "loading" },
    jobs: { status: "loading" },
    invoices: { status: "loading" },
    mechanics: { status: "loading" },
    insights: { status: "loading" },
    revenue: { status: "loading" },
  });

  useEffect(() => {
    let mounted = true;

    const requests = {
      summary: getDashboardSummary(),
      jobs: getDashboardJobCards(),
      invoices: getInvoices(),
      mechanics: getMechanics(),
      insights: getAiInsights(insightFrom, toDate),
      revenue: getRevenueReport(fromDate, weekTo),
    };

    Promise.all(
      Object.entries(requests).map(async ([key, request]) => {
        try {
          return [key, { status: "success", data: await request }];
        } catch (error) {
          return [
            key,
            {
              status: "error",
              error:
                error instanceof Error
                  ? error.message
                  : "Request failed.",
            },
          ];
        }
      })
    ).then((results) => {
      if (mounted) {
        setDashboard(Object.fromEntries(results));
      }
    });

    return () => {
      mounted = false;
    };
  }, [fromDate, insightFrom, refreshKey, toDate, weekTo]);


  const summary = dashboard.summary.data;
  const jobs = dashboard.jobs.data || [];
  const invoices = dashboard.invoices.data || [];
  const mechanics = dashboard.mechanics.data || [];
  const insights = dashboard.insights.data || [];
  const revenueReport = dashboard.revenue.data;

  const activeJobs = jobs.filter(isActiveJob);
  const visibleJobs = activeJobs.slice(0, 6);

  /* Invoices still carrying a balance, and the value of that balance. */
  const outstandingInvoices = invoices.filter(
    (invoice) =>
      String(invoice.status || "").toUpperCase() !== "CANCELLED" &&
      Number(invoice.outstandingAmount) > 0
  );
  const outstandingTotal = outstandingInvoices.reduce(
    (total, invoice) => total + (Number(invoice.outstandingAmount) || 0),
    0
  );

  /* Active job count per mechanic, keyed the same way on both sides. */
  const activeJobsByMechanic = new Map();
  activeJobs.forEach((job) => {
    const key = jobMechanicKey(job);
    if (key) {
      activeJobsByMechanic.set(key, (activeJobsByMechanic.get(key) || 0) + 1);
    }
  });

  /* Service distribution across active jobs, from JobCardResponseDTO.serviceType. */
  const serviceCounts = new Map();
  activeJobs.forEach((job) => {
    const serviceType =
      typeof job.serviceType === "string" ? job.serviceType.trim() : "";
    if (serviceType) {
      serviceCounts.set(serviceType, (serviceCounts.get(serviceType) || 0) + 1);
    }
  });
  const serviceDistribution = [...serviceCounts.entries()]
    .map(([name, count]) => ({ name, count }))
    .sort(
      (left, right) =>
        right.count - left.count || left.name.localeCompare(right.name)
    );
  const maxServiceCount = Math.max(
    ...serviceDistribution.map(({ count }) => count),
    1
  );

  /* Weekly revenue, aligned to the seven days of the current week. */
  const revenueDays = revenueReport?.dailyTrend || [];
  const revenueByDate = new Map(
    revenueDays.map((day) => [String(day.date), Number(day.total) || 0])
  );
  const weekRevenue = weekDays.map(
    (date) => revenueByDate.get(dateString(date)) || 0
  );
  const maxRevenue = Math.max(...weekRevenue, 1);
  const weekRevenueTotal = weekRevenue.reduce((total, value) => total + value, 0);

  const hasError = Object.values(dashboard).some(
    (section) => section.status === "error"
  );

  /* Per-section state text. Never substitutes a number for a failure. */
  const sectionText = (section, loadingText, emptyText) => {
    if (section.status === "loading") return loadingText;
    if (section.status === "error") return section.error;
    return emptyText;
  };

  const statValue = (section, value) => {
    if (value !== null && value !== undefined) return value;
    if (section.status === "loading") return "…";
    if (section.status === "error") return "—";
    return "—";
  };


  return (
    <div className="sa-page">

      <div className="sa-page-head">
        <div>
          <div className="sa-kicker">
            — TODAY,{" "}
            {today
              .toLocaleDateString("en-IN", {
                day: "2-digit",
                month: "long",
                year: "numeric",
              })
              .toUpperCase()}
          </div>
          <h1 className="sa-title">OPERATIONS OVERVIEW</h1>
        </div>
      </div>


      {hasError && (
        <div className="sa-banner" role="alert">
          <span>Some dashboard data could not be loaded.</span>
          <button
            type="button"
            className="sa-retry"
            onClick={() => setRefreshKey((value) => value + 1)}
          >
            Retry
          </button>
        </div>
      )}


      {/* ── STAT CARDS ── */}

      <div className="sa-stats">

        <div className="sa-stat">
          <div className="sa-stat-label">Today's Jobs</div>
          <div
            className="sa-stat-value"
            style={{ color: "var(--sa-lime)" }}
          >
            {statValue(dashboard.summary, summary?.todaysJobs)}
          </div>
          <div className="sa-stat-note">
            {summary
              ? `${summary.pendingJobs} pending · ${summary.completedJobs} completed`
              : sectionText(
                  dashboard.summary,
                  "Loading summary…",
                  "Summary unavailable"
                )}
          </div>
        </div>

        <div className="sa-stat">
          <div className="sa-stat-label">Revenue Today</div>
          <div className="sa-stat-value">
            {statValue(
              dashboard.summary,
              summary ? formatCurrency(summary.revenue) : null
            )}
          </div>
          <div className="sa-stat-note">
            {summary
              ? "Invoiced value for today"
              : sectionText(
                  dashboard.summary,
                  "Loading summary…",
                  "Summary unavailable"
                )}
          </div>
        </div>

        <div className="sa-stat">
          <div className="sa-stat-label">Pending Invoices</div>
          <div className="sa-stat-value">
            {dashboard.invoices.status === "success"
              ? outstandingInvoices.length
              : statValue(dashboard.invoices, null)}
          </div>
          <div className="sa-stat-note">
            {dashboard.invoices.status === "success"
              ? `${formatCurrency(outstandingTotal)} outstanding`
              : sectionText(
                  dashboard.invoices,
                  "Loading invoices…",
                  "Invoice data unavailable"
                )}
          </div>
        </div>

        <div className="sa-stat">
          <div className="sa-stat-label">Low Stock Alerts</div>
          <div className="sa-stat-value">
            {statValue(dashboard.summary, summary?.lowStockParts)}
          </div>
          <div className="sa-stat-note">
            {summary
              ? "Parts at or below reorder level"
              : sectionText(
                  dashboard.summary,
                  "Loading summary…",
                  "Summary unavailable"
                )}
          </div>
        </div>

      </div>


      {/* ── ACTIVE JOBS · WORKLOAD · AI ALERTS ── */}

      <div className="sa-grid-main">

        <section className="sa-card">
          <div className="sa-card-head">Active Job Cards</div>

          {dashboard.jobs.status !== "success" ? (
            <div
              className="sa-state"
              role={dashboard.jobs.status === "error" ? "alert" : undefined}
            >
              {sectionText(
                dashboard.jobs,
                "Loading job cards…",
                "Job card data unavailable"
              )}
            </div>
          ) : visibleJobs.length === 0 ? (
            <div className="sa-state">No active job cards.</div>
          ) : (
            /* .sa-card clips its overflow and the table cells are nowrap, so
               without this the right-hand columns are cut off on narrow
               screens. Scroll rather than truncate — all seven columns stay
               reachable. */
            <div className="sa-table-scroll">
              <table className="sa-table">
                <thead>
                  <tr>
                    <th>Job ID</th>
                    <th>Customer</th>
                    <th>Vehicle</th>
                    <th>Mechanic</th>
                    <th>Status</th>
                    <th>ETA</th>
                    <th>Amount</th>
                  </tr>
                </thead>
                <tbody>
                  {visibleJobs.map((job) => (
                    <tr key={job.id ?? job.jobCardNumber}>
                      <td className="sa-id">{job.jobCardNumber || job.id}</td>
                      <td>
                        <strong>{job.customerName || "—"}</strong>
                      </td>
                      <td>{job.vehicleInfo || "—"}</td>
                      <td>
                        <strong>{job.mechanicName || "Unassigned"}</strong>
                      </td>
                      <td>
                        <span className={`sa-status ${statusClass(job.status)}`}>
                          {readableStatus(job.status)}
                        </span>
                      </td>
                      <td>{formatDateTime(job.estimatedDelivery)}</td>
                      <td>
                        <strong>{formatCurrency(job.estimatedCost)}</strong>
                      </td>
                    </tr>
                  ))}
                </tbody>
              </table>
            </div>
          )}
        </section>

        <div className="sa-side-stack">

          <section className="sa-card">
            <div className="sa-card-head">Mechanic Workload</div>

            {dashboard.mechanics.status !== "success" ? (
              <div
                className="sa-state"
                role={dashboard.mechanics.status === "error" ? "alert" : undefined}
              >
                {sectionText(
                  dashboard.mechanics,
                  "Loading mechanics…",
                  "Mechanic data unavailable"
                )}
              </div>
            ) : mechanics.length === 0 ? (
              <div className="sa-state">No mechanics found.</div>
            ) : (
              <div className="sa-list">
                {mechanics.map((mechanic) => {
                  const key = mechanicKey(mechanic);
                  const count = key ? activeJobsByMechanic.get(key) || 0 : 0;
                  return (
                    <div className="sa-list-row" key={mechanic.id ?? mechanic.name}>
                      <div className="sa-list-left">
                        <i className={`sa-dot ${count > 0 ? "green" : ""}`}></i>
                        <strong>{mechanic.name}</strong>
                      </div>
                      <span className="sa-list-count">{count} active</span>
                    </div>
                  );
                })}
              </div>
            )}
          </section>

          <section className="sa-card">
            <div className="sa-card-head">AI Alerts</div>

            {dashboard.insights.status !== "success" ? (
              <div
                className="sa-state"
                role={dashboard.insights.status === "error" ? "alert" : undefined}
              >
                {sectionText(
                  dashboard.insights,
                  "Loading AI insights…",
                  "AI insights unavailable"
                )}
              </div>
            ) : insights.length === 0 ? (
              <div className="sa-state">
                No stored AI insights in the last 30 days.
              </div>
            ) : (
              insights.slice(0, 3).map((insight) => {
                const confidence = formatConfidence(insight.confidence);
                return (
                  <div className="sa-alert" key={insight.id}>
                    <div className="sa-alert-top">
                      <span className="sa-alert-icon">◇</span>
                      {insight.featureType || "AI insight"}
                    </div>
                    {insight.inputRef && <small>{insight.inputRef}</small>}
                    {confidence && <small>{confidence} confidence</small>}
                  </div>
                );
              })
            )}
          </section>

        </div>

      </div>


      {/* ── WEEKLY REVENUE · SERVICE DISTRIBUTION ── */}

      <div className="sa-bottom-grid">

        <section className="sa-card">
          <div className="sa-card-head">Revenue — This Week</div>

          {dashboard.revenue.status === "success" && revenueDays.length > 0 ? (
            <div className="sa-chart">
              <div className="sa-chart-grid">
                {weekRevenue.map((amount, index) => (
                  <div
                    key={dateString(weekDays[index])}
                    className="sa-chart-bar"
                    style={{
                      "--h": `${Math.max(2, (amount / maxRevenue) * 78)}px`,
                      opacity: amount > 0 ? 1 : 0.35,
                    }}
                    title={`${dateString(weekDays[index])}: ${formatCurrency(amount)}`}
                  />
                ))}
              </div>
              <div className="sa-days">
                {weekDays.map((date) => (
                  <span key={dateString(date)}>
                    {date.toLocaleDateString("en", { weekday: "short" })}
                  </span>
                ))}
              </div>
              <div className="sa-chart-total">
                Week total: {formatCurrency(weekRevenueTotal)}
              </div>
            </div>
          ) : (
            <div
              className="sa-state"
              role={dashboard.revenue.status === "error" ? "alert" : undefined}
            >
              {sectionText(
                dashboard.revenue,
                "Loading revenue…",
                "No invoiced revenue recorded for this week"
              )}
            </div>
          )}
        </section>

        <section className="sa-card">
          <div className="sa-card-head">Service Distribution</div>

          {dashboard.jobs.status !== "success" ? (
            <div
              className="sa-state"
              role={dashboard.jobs.status === "error" ? "alert" : undefined}
            >
              {sectionText(
                dashboard.jobs,
                "Loading service data…",
                "Service data unavailable"
              )}
            </div>
          ) : serviceDistribution.length === 0 ? (
            <div className="sa-state">
              {activeJobs.length > 0
                ? "Service types are not recorded for active jobs."
                : "No active jobs to distribute."}
            </div>
          ) : (
            <div className="sa-bars">
              {serviceDistribution.slice(0, 5).map(({ name, count }, index) => (
                <div className="sa-bar-row" key={name}>
                  <div className="sa-bar-label">
                    <span>{name}</span>
                    <span>{count}</span>
                  </div>
                  <div
                    className={`sa-progress ${["", "blue", "purple", "green"][index % 4]}`}
                    style={{ "--w": `${(count / maxServiceCount) * 100}%` }}
                  >
                    <i />
                  </div>
                </div>
              ))}
            </div>
          )}
        </section>

      </div>

    </div>
  );
}
