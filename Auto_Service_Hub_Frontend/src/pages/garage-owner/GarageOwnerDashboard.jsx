import React, { useEffect, useMemo, useState } from "react";
import { useNavigate } from "react-router-dom";
import {
  getAiInsights,
  getDashboardJobCards,
  getDashboardSummary,
  getInvoices,
  getMechanics,
  getRevenueReport,
} from "../../services/dashboardService";
import "./GarageOwnerDashboard.css";

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

const statusClass = (status) => {
  const normalized = (status || "").toUpperCase().replace(/[\s-]+/g, "_");
  if (normalized.includes("REPAIR")) return "repair";
  if (normalized.includes("QUALITY") || normalized === "QC") return "quality";
  if (normalized.includes("DELIVER")) return "delivered";
  if (normalized.includes("INSPECT")) return "inspection";
  if (normalized.includes("RECEIV")) return "received";
  return "";
};

const formatDateTime = (value) => {
  if (!value) return "Not specified";
  const date = new Date(value);
  return Number.isNaN(date.getTime())
    ? "Not specified"
    : date.toLocaleString("en-IN", {
        day: "2-digit",
        month: "short",
        hour: "2-digit",
        minute: "2-digit",
      });
};

const sectionMessage = (section, loadingText, emptyText) => {
  if (section.status === "loading") return loadingText;
  if (section.status === "error") return section.error;
  return emptyText;
};

function GarageOwnerDashboard() {
  const navigate = useNavigate();
  const today = useMemo(() => new Date(), []);
  const toDate = dateString(today);
  const weekStart = useMemo(() => {
    const daysSinceMonday = (today.getDay() + 6) % 7;
    return addDays(today, -daysSinceMonday);
  }, [today]);
  const fromDate = dateString(weekStart);
  const weekEnd = addDays(weekStart, 6);
  const weekTo = dateString(weekEnd);
  const insightFrom = useMemo(() => {
    return dateString(addDays(today, -29));
  }, [today]);
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
          const data = await request;
          return [key, { status: "success", data }];
        } catch (error) {
          return [
            key,
            {
              status: "error",
              error: error instanceof Error ? error.message : "Request failed.",
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
  const activeJobs = jobs.filter(isActiveJob);
  const visibleJobs = activeJobs.slice(0, 6);
  const invoices = dashboard.invoices.data || [];
  const mechanics = dashboard.mechanics.data || [];
  const insights = dashboard.insights.data || [];
  const revenueReport = dashboard.revenue.data;
  const revenueDays = revenueReport?.dailyTrend || [];
  const revenueByDate = new Map(
    revenueDays.map((day) => [String(day.date), Number(day.total) || 0])
  );
  const chartPoints = weekDays.map((date) => revenueByDate.get(dateString(date)) || 0);
  const maxRevenue = Math.max(...chartPoints, 1);
  const revenuePolyline = chartPoints
    .map((amount, index) => {
      const x = chartPoints.length === 1
        ? 350
        : (index / (chartPoints.length - 1)) * 700;
      const y = 94 - (amount / maxRevenue) * 84;
      return `${x},${y}`;
    })
    .join(" ");
  const pendingInvoices = invoices.filter((invoice) =>
    String(invoice.status || "").toUpperCase() !== "CANCELLED" &&
    Number(invoice.outstandingAmount) > 0
  ).length;
  const activeJobsByMechanic = new Map();
  activeJobs.forEach((job) => {
    const key = job.mechanicId != null
      ? `id:${job.mechanicId}`
      : job.mechanicName
        ? `name:${job.mechanicName.trim().toLowerCase()}`
        : null;
    if (key) activeJobsByMechanic.set(key, (activeJobsByMechanic.get(key) || 0) + 1);
  });
  const serviceCounts = new Map();
  activeJobs.forEach((job) => {
    const serviceType = typeof job.serviceType === "string" ? job.serviceType.trim() : "";
    if (serviceType) serviceCounts.set(serviceType, (serviceCounts.get(serviceType) || 0) + 1);
  });
  const serviceDistribution = [...serviceCounts.entries()]
    .map(([name, count]) => ({ name, count }))
    .sort((left, right) => right.count - left.count || left.name.localeCompare(right.name));
  const maxServiceCount = Math.max(...serviceDistribution.map(({ count }) => count), 1);
  const dashboardHasError = Object.values(dashboard).some(({ status }) => status === "error");

  const statusText = (section, loadingText, emptyText) =>
    sectionMessage(section, loadingText, emptyText);

  return (
    <div className="garage-dashboard-page">
      <section className="garage-dashboard-heading">
        <div className="garage-dashboard-date">
          TODAY, {today.toLocaleDateString("en-IN", {
            day: "2-digit",
            month: "long",
            year: "numeric",
          }).toUpperCase()}
        </div>
        <h1>OPERATIONS OVERVIEW</h1>
      </section>
      {dashboardHasError && (
        <div className="garage-dashboard-retry" role="alert">
          Some dashboard data could not be loaded.
          <button type="button" onClick={() => setRefreshKey((value) => value + 1)}>Retry</button>
        </div>
      )}

      <section className="garage-dashboard-stats">
        <div className="garage-dashboard-stat-card">
          <div className="garage-dashboard-stat-label">TODAY&apos;S JOBS</div>
          <div className="garage-dashboard-stat-value lime">
            {summary ? summary.todaysJobs : dashboard.summary.status === "loading" ? "…" : "—"}
          </div>
          <div className="garage-dashboard-stat-description">
            {summary
              ? "Assigned today"
              : statusText(dashboard.summary, "Loading summary…", "Summary unavailable")}
          </div>
        </div>

        <div className="garage-dashboard-stat-card">
          <div className="garage-dashboard-stat-label">REVENUE TODAY</div>
          <div className="garage-dashboard-stat-value">
            {summary ? formatCurrency(summary.revenue) : dashboard.summary.status === "loading" ? "…" : "—"}
          </div>
          <div className="garage-dashboard-stat-description">
            {summary ? "Reported by dashboard summary" : statusText(dashboard.summary, "Loading summary…", "Summary unavailable")}
          </div>
        </div>

        <div className="garage-dashboard-stat-card">
          <div className="garage-dashboard-stat-label">PENDING INVOICES</div>
          <div className="garage-dashboard-stat-value">
            {dashboard.invoices.status === "success"
              ? pendingInvoices
              : dashboard.invoices.status === "loading" ? "…" : "—"}
          </div>
          <div className="garage-dashboard-stat-description">
            {dashboard.invoices.status === "success"
              ? "Invoices with an outstanding balance"
              : statusText(dashboard.invoices, "Loading invoices…", "Invoice data unavailable")}
          </div>
        </div>

        <div className="garage-dashboard-stat-card">
          <div className="garage-dashboard-stat-label">LOW STOCK ALERTS</div>
          <div className="garage-dashboard-stat-value">
            {summary ? summary.lowStockParts : dashboard.summary.status === "loading" ? "…" : "—"}
          </div>
          <div className="garage-dashboard-stat-description">
            {summary ? "Parts at or below reorder level" : statusText(dashboard.summary, "Loading summary…", "Summary unavailable")}
          </div>
        </div>
      </section>

      <section className="garage-dashboard-middle">
        <div className="garage-dashboard-panel garage-dashboard-jobs">
          <div className="garage-dashboard-panel-title">ACTIVE JOB CARDS</div>
          <div className="garage-dashboard-table-wrapper">
            <table className="garage-dashboard-table">
              <thead>
                <tr>
                  <th>JOB ID</th>
                  <th>CUSTOMER</th>
                  <th>VEHICLE</th>
                  <th>MECHANIC</th>
                  <th>STATUS</th>
                  <th>ETA</th>
                  <th>AMOUNT</th>
                </tr>
              </thead>
              <tbody>
                {dashboard.jobs.status !== "success" ? (
                  <tr>
                    <td colSpan="7" role={dashboard.jobs.status === "error" ? "alert" : undefined}>
                      {statusText(dashboard.jobs, "Loading job cards…", "No recent job cards")}
                    </td>
                  </tr>
                ) : visibleJobs.length === 0 ? (
                  <tr><td colSpan="7">No active job cards.</td></tr>
                ) : visibleJobs.map((job) => (
                  <tr key={job.id ?? job.jobCardNumber}>
                    <td className="garage-dashboard-job-id">
                      <button type="button" onClick={() => navigate(`/garage-owner/jobcards/${job.id}`)}>
                        {job.jobCardNumber || job.id}
                      </button>
                    </td>
                    <td className="garage-dashboard-customer">{job.customerName || "—"}</td>
                    <td className="garage-dashboard-vehicle">{job.vehicleInfo || "—"}</td>
                    <td className="garage-dashboard-mechanic">{job.mechanicName || "Unassigned"}</td>
                    <td>
                      <span className={`garage-dashboard-status ${statusClass(job.status)}`}>
                        {readableStatus(job.status)}
                      </span>
                    </td>
                    <td className="garage-dashboard-eta">{formatDateTime(job.estimatedDelivery)}</td>
                    <td className="garage-dashboard-amount">{formatCurrency(job.estimatedCost)}</td>
                  </tr>
                ))}
              </tbody>
            </table>
          </div>
        </div>

        <div className="garage-dashboard-right">
          <div className="garage-dashboard-panel garage-dashboard-workload">
            <div className="garage-dashboard-panel-title">MECHANIC WORKLOAD</div>
            <div className="garage-dashboard-mechanics">
              {dashboard.mechanics.status !== "success" ? (
                <div role={dashboard.mechanics.status === "error" ? "alert" : undefined}>
                  {statusText(dashboard.mechanics, "Loading mechanics…", "Mechanic data unavailable")}
                </div>
              ) : mechanics.length === 0 ? (
                <div>No mechanics found.</div>
              ) : mechanics.map((mechanic) => (
                <div className="garage-dashboard-mechanic-row" key={mechanic.id}>
                  <div className="garage-dashboard-mechanic-person">
                    <span className={`garage-dashboard-mechanic-dot ${
                      (activeJobsByMechanic.get(`id:${mechanic.id}`) ||
                        activeJobsByMechanic.get(`name:${String(mechanic.name || "").trim().toLowerCase()}`) || 0) > 0
                        ? "busy"
                        : "available"
                    }`} />
                    <span>{mechanic.name}</span>
                  </div>
                  <span className="garage-dashboard-active-count">
                    {activeJobsByMechanic.get(`id:${mechanic.id}`) ||
                      activeJobsByMechanic.get(`name:${String(mechanic.name || "").trim().toLowerCase()}`) || 0} active
                  </span>
                </div>
              ))}
            </div>
          </div>

          <div className="garage-dashboard-panel garage-dashboard-ai">
            <div className="garage-dashboard-panel-title">AI ALERTS</div>
            <div className="garage-dashboard-alert-list">
              {dashboard.insights.status !== "success" ? (
                <div role={dashboard.insights.status === "error" ? "alert" : undefined}>
                  {statusText(dashboard.insights, "Loading AI insights…", "AI insights unavailable")}
                </div>
              ) : insights.length === 0 ? (
                <div>No stored AI insights in the last 30 days.</div>
              ) : insights.slice(0, 3).map((insight) => (
                <div className="garage-dashboard-alert" key={insight.id}>
                  <div className="garage-dashboard-alert-icon">◇</div>
                  <div>
                    <strong>{insight.featureType || "AI insight"}</strong>
                    <span>{insight.inputRef || "No subject supplied"}</span>
                    {insight.confidence !== null && insight.confidence !== undefined && (
                      <span>Confidence score: {insight.confidence}</span>
                    )}
                  </div>
                </div>
              ))}
            </div>
          </div>
        </div>
      </section>

      <section className="garage-dashboard-bottom">
        <div className="garage-dashboard-panel garage-dashboard-revenue">
          <div className="garage-dashboard-panel-title">REVENUE — THIS WEEK</div>
          <div className="garage-dashboard-chart">
            <div className="garage-dashboard-chart-area">
              {dashboard.revenue.status === "success" && revenueDays.length > 0 ? (
                <svg
                  viewBox="0 0 700 100"
                  preserveAspectRatio="none"
                  role="img"
                  aria-label="Daily invoiced revenue trend from Monday through Sunday"
                  style={{ width: "100%", height: "100%", overflow: "visible" }}
                >
                  <title>
                    {revenueDays.map((day) => `${day.date}: ${formatCurrency(day.total)}`).join(", ")}
                  </title>
                  <polyline
                    points={revenuePolyline}
                    fill="none"
                    stroke="#c9ff20"
                    strokeWidth="3"
                    vectorEffect="non-scaling-stroke"
                  />
                </svg>
              ) : (
                <div role={dashboard.revenue.status === "error" ? "alert" : undefined}>
                  {statusText(dashboard.revenue, "Loading revenue…", "No invoiced revenue recorded for this week")}
                </div>
              )}
            </div>
            <div className="garage-dashboard-chart-days">
              {weekDays.map((date) => {
                const dayKey = dateString(date);
                return (
                  <span key={dayKey} title={`${dayKey}: ${formatCurrency(revenueByDate.get(dayKey) || 0)}`}>
                    {date.toLocaleDateString("en", { weekday: "short" })}
                  </span>
                );
              })}
            </div>
          </div>
        </div>

        <div className="garage-dashboard-panel garage-dashboard-service">
          <div className="garage-dashboard-panel-title">SERVICE DISTRIBUTION</div>
          <div className="garage-dashboard-service-list">
            {dashboard.jobs.status !== "success" ? (
              <div role={dashboard.jobs.status === "error" ? "alert" : undefined}>
                {statusText(dashboard.jobs, "Loading service data…", "Service data unavailable")}
              </div>
            ) : serviceDistribution.length === 0 ? (
              <div>{activeJobs.length ? "Service types are unavailable for active jobs." : "No active jobs to distribute."}</div>
            ) : serviceDistribution.slice(0, 5).map(({ name, count }, index) => (
              <div className="garage-dashboard-service-row" key={name}>
                <div className="garage-dashboard-service-info">
                  <strong>{name}</strong>
                  <span>{count}</span>
                </div>
                <div className="garage-dashboard-service-bar">
                  <div
                    className={`garage-dashboard-service-fill ${["full", "mechanical", "electrical", "tyres"][index % 4]}`}
                    style={{ width: `${(count / maxServiceCount) * 100}%` }}
                  />
                </div>
              </div>
            ))}
          </div>
        </div>
      </section>
    </div>
  );
}

export default GarageOwnerDashboard;
