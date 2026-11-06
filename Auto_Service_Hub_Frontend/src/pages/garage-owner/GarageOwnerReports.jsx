import React, { useCallback, useEffect, useMemo, useRef, useState } from "react";
import { getMechanics } from "../../services/mechanicService";
import { getVehicles } from "../../services/vehicleService";
import {
  REPORT_KEYS,
  downloadReportExport,
  getCustomerGrowthReport,
  getDailyWorkshopReport,
  getMechanicPerformanceReport,
  getPartsUsageReport,
  getProfitAnalysisReport,
  getRevenuePaymentReport,
} from "../../services/reportService";
import "./GarageOwnerReports.css";

/* =========================================
   REPORT DEFINITIONS
========================================= */

const TABS = [
  {
    key: "daily",
    label: "TODAY",
    report: REPORT_KEYS.DAILY_WORKSHOP,
    kicker: "FR-REP-1 · DAILY WORKSHOP",
    title: "Daily workshop report",
  },
  {
    key: "revenue",
    label: "REVENUE",
    report: REPORT_KEYS.REVENUE_PAYMENT,
    kicker: "FR-REP-4 · REVENUE & PAYMENTS",
    title: "Revenue and payment report",
  },
  {
    key: "mechanics",
    label: "MECHANICS",
    report: REPORT_KEYS.MECHANIC_PERFORMANCE,
    kicker: "FR-REP-2 · MECHANIC PERFORMANCE",
    title: "Mechanic performance report",
  },
  {
    key: "parts",
    label: "PARTS",
    report: REPORT_KEYS.PARTS_USAGE,
    kicker: "FR-REP-3 · PARTS USAGE",
    title: "Parts usage report",
  },
  {
    key: "customers",
    label: "CUSTOMERS",
    report: REPORT_KEYS.CUSTOMER_GROWTH,
    kicker: "FR-REP-5 · CUSTOMER GROWTH",
    title: "Customer growth report",
  },
  {
    key: "profit",
    label: "PROFIT",
    report: REPORT_KEYS.PROFIT_ANALYSIS,
    kicker: "FR-REP-4 · PROFIT ANALYSIS",
    title: "Profit analysis report",
  },
];

/**
 * Which filters each endpoint actually reads.
 *
 * Taken from ReportController, not from the shared ReportFilterDTO: the DTO carries
 * every field, but a report that does not query on one (the revenue report has no
 * service-type branch, for instance) must not be offered a control that would be
 * silently ignored.
 */
const TAB_FILTERS = {
  daily: ["date", "mechanicId", "vehicleId", "serviceType", "status"],
  revenue: ["from", "to", "mechanicId", "vehicleId", "status"],
  mechanics: ["from", "to", "mechanicId"],
  parts: ["from", "to", "mechanicId"],
  customers: ["from", "to"],
  profit: ["from", "to"],
};

/** The roles each report endpoint is guarded by, so a 403 is never a mystery. */
const TAB_ROLES = {
  daily: "OWNER, MANAGER, SERVICE ADVISOR, MECHANIC",
  revenue: "OWNER, MANAGER, SERVICE ADVISOR, BILLING",
  mechanics: "OWNER, MANAGER, SERVICE ADVISOR, MECHANIC",
  parts: "OWNER, MANAGER, SERVICE ADVISOR, INVENTORY MANAGER",
  customers: "OWNER, MANAGER, SERVICE ADVISOR, BILLING",
  profit: "OWNER, MANAGER, BILLING",
};

/** Job-card statuses, the same set the job card workspace moves through. */
const JOB_STATUS_OPTIONS = [
  "RECEIVED",
  "INSPECTION",
  "IN_REPAIR",
  "QUALITY_CHECK",
  "DELIVERED",
];

/** The service catalogue the appointment module offers. Job cards may hold free text. */
const SERVICE_TYPE_OPTIONS = [
  "Basic Service",
  "Full Service",
  "AC Service",
  "Battery Replacement",
  "Tyre Rotation",
  "Denting & Painting",
  "Suspension & Tyres",
];

const RANGE_PRESETS = [
  { key: "today", label: "TODAY" },
  { key: "7d", label: "7 DAYS" },
  { key: "30d", label: "30 DAYS" },
  { key: "month", label: "THIS MONTH" },
];

/** Tabs whose endpoints take a from/to range instead of a single date. */
const RANGE_TABS = ["revenue", "mechanics", "parts", "customers", "profit"];

const FETCHERS = {
  daily: getDailyWorkshopReport,
  revenue: getRevenuePaymentReport,
  mechanics: getMechanicPerformanceReport,
  parts: getPartsUsageReport,
  customers: getCustomerGrowthReport,
  profit: getProfitAnalysisReport,
};

/** The request-loading state every panel reads from. */
const IDLE_SECTION = { status: "idle", data: null, error: null, statusCode: null };

/* =========================================
   FORMATTING HELPERS
========================================= */

const pad = (value) => String(value).padStart(2, "0");

/**
 * Local calendar date as yyyy-MM-dd. Deliberately not toISOString(), which would
 * shift the day for anyone east or west of UTC and silently move the report window.
 */
const toDateValue = (date) =>
  `${date.getFullYear()}-${pad(date.getMonth() + 1)}-${pad(date.getDate())}`;

const addDays = (date, amount) => {
  const next = new Date(date.getFullYear(), date.getMonth(), date.getDate());
  next.setDate(next.getDate() + amount);
  return next;
};

const toNumber = (value) => {
  const parsed = Number(value);
  return Number.isFinite(parsed) ? parsed : 0;
};

const isBlank = (value) => value === null || value === undefined;

/** Whole rupees — used on KPI tiles, where decimals are noise. */
const formatCurrency = (value) =>
  isBlank(value)
    ? "—"
    : new Intl.NumberFormat("en-IN", {
        style: "currency",
        currency: "INR",
        maximumFractionDigits: 0,
      }).format(toNumber(value));

/** Rupees to two decimals — used inside tables and tooltips, where paise can matter. */
const formatMoney = (value) =>
  isBlank(value)
    ? "—"
    : new Intl.NumberFormat("en-IN", {
        style: "currency",
        currency: "INR",
        maximumFractionDigits: 2,
      }).format(toNumber(value));

const formatCount = (value) =>
  isBlank(value) ? "—" : new Intl.NumberFormat("en-IN").format(toNumber(value));

/** The API sends rates as fractions (0.625), so the UI is the only place doing the x100. */
const formatShare = (fraction) =>
  isBlank(fraction) ? "—" : `${(toNumber(fraction) * 100).toFixed(1)}%`;

const formatDays = (value) =>
  isBlank(value) ? "Not measured" : `${toNumber(value).toFixed(2)} days`;

/**
 * A rating, with the number of ratings behind it when one was supplied.
 *
 * Null means "not rated", which is deliberately different from a rating of zero:
 * an unrated mechanic must not read as the worst in the workshop.
 */
const formatRating = (value, ratingCount) => {
  if (isBlank(value)) {
    return "Not rated";
  }
  const score = `${toNumber(value).toFixed(2)} / 5`;
  return isBlank(ratingCount) ? score : `${score} (${formatCount(ratingCount)})`;
};


const readableStatus = (status) => {
  if (!status) {
    return "—";
  }
  return String(status)
    .replace(/_/g, " ")
    .toLowerCase()
    .replace(/\b[a-z]/g, (letter) => letter.toUpperCase());
};

/** "2026-10-26" -> "26 Oct 2026", read as a plain calendar date. */
const formatDayLabel = (value) => {
  if (!value) {
    return "—";
  }
  const parsed = new Date(`${value}T00:00:00`);
  return Number.isNaN(parsed.getTime())
    ? String(value)
    : parsed.toLocaleDateString("en-IN", {
        day: "2-digit",
        month: "short",
        year: "numeric",
      });
};

/** "2026-10-26" -> "26 Oct", for chart axes. */
const formatAxisLabel = (value) => {
  if (!value) {
    return "";
  }
  const parsed = new Date(`${value}T00:00:00`);
  return Number.isNaN(parsed.getTime())
    ? String(value)
    : parsed.toLocaleDateString("en-IN", { day: "2-digit", month: "short" });
};

const mechanicLabel = (mechanic) =>
  mechanic.name || mechanic.mechanicName || `Mechanic #${mechanic.id}`;

const vehicleLabel = (vehicle) =>
  [vehicle.registrationNo, vehicle.make, vehicle.model].filter(Boolean).join(" — ") ||
  `Vehicle #${vehicle.id}`;

/**
 * Hands a fetched blob to the browser. Same approach the billing screen uses for
 * invoice PDFs: an object URL on a temporary anchor, revoked once the click is
 * dispatched.
 */
const triggerBrowserDownload = (blob, filename) => {
  const url = URL.createObjectURL(blob);
  const link = document.createElement("a");
  link.href = url;
  link.download = filename;
  document.body.appendChild(link);
  link.click();
  link.remove();
  URL.revokeObjectURL(url);
};

/* =========================================
   PRESENTATIONAL PIECES
========================================= */

function KpiGrid({ children }) {
  return <div className="reports-kpi-grid">{children}</div>;
}

function KpiCard({ label, value, hint, tone }) {
  return (
    <div className={`reports-kpi${tone ? ` reports-kpi-${tone}` : ""}`}>
      <span className="reports-kpi-label">{label}</span>
      <strong className="reports-kpi-value">{value}</strong>
      {hint ? <small className="reports-kpi-hint">{hint}</small> : null}
    </div>
  );
}

function ReportPanel({ title, subtitle, children }) {
  return (
    <section className="reports-panel">
      <header className="reports-panel-head">
        <h2>{title}</h2>
        {subtitle ? <p>{subtitle}</p> : null}
      </header>
      <div className="reports-panel-body">{children}</div>
    </section>
  );
}

/**
 * The one block of feedback a panel shows while it has nothing to render: a
 * loading line, an error with a retry, a role denial with the roles that would
 * work, or a valid-but-empty result explained in the panel's own terms.
 */
function PanelState({ state, loadingText, emptyText, isEmpty = false, onRetry, extra }) {
  if (state.status === "loading" || state.status === "idle") {
    return <p className="reports-state">{loadingText}</p>;
  }

  if (state.status === "error") {
    return (
      <div className="reports-state reports-state-error" role="alert">
        <span>{state.error || "This report could not be loaded."}</span>
        {extra ? <span className="reports-state-extra">{extra}</span> : null}
        {onRetry ? (
          <button type="button" className="reports-state-retry" onClick={onRetry}>
            RETRY
          </button>
        ) : null}
      </div>
    );
  }

  if (isEmpty) {
    return <p className="reports-state">{emptyText}</p>;
  }

  return null;
}

/**
 * Categorical chart drawn with plain elements instead of a charting library: every
 * bar carries its own value as text, so the numbers survive a screenshot, a print
 * and a screen reader. Widths are relative to the largest value in the list.
 */
function BarList({ rows, formatValue, ariaLabel }) {
  const peak = rows.reduce(
    (high, row) => Math.max(high, Math.abs(toNumber(row.value))),
    0
  );

  return (
    <div className="reports-bars" role="list" aria-label={ariaLabel}>
      {rows.map((row) => (
        <div className="reports-bar" role="listitem" key={row.key}>
          <span className="reports-bar-label" title={row.label}>
            {row.label}
          </span>
          <span className="reports-bar-track">
            <span
              className="reports-bar-fill"
              style={{
                width:
                  peak > 0
                    ? `${(Math.abs(toNumber(row.value)) / peak) * 100}%`
                    : "0%",
              }}
            />
          </span>
          <span className="reports-bar-value">{formatValue(row.value)}</span>
          <span className="reports-bar-meta">{row.meta || ""}</span>
        </div>
      ))}
    </div>
  );
}

/**
 * Invoiced value per day as an inline SVG polyline — the same technique the
 * dashboard uses for its weekly trend, which is why the reports page needs no
 * charting dependency. The stroke is non-scaling, so it stays 3px however wide the
 * panel is stretched.
 */
function TrendChart({ points, formatMoney: money, ariaLabel }) {
  const values = points.map((point) => toNumber(point.value));
  const peak = values.reduce((high, value) => Math.max(high, value), 0);
  const lastIndex = Math.max(points.length - 1, 1);
  const polyline = values
    .map((value, index) => {
      const x = (index / lastIndex) * 700;
      const y = 92 - (peak > 0 ? (value / peak) * 80 : 0);
      return `${x.toFixed(1)},${y.toFixed(1)}`;
    })
    .join(" ");
  const labelEvery = Math.max(Math.ceil(points.length / 8), 1);

  return (
    <div className="reports-chart">
      <div className="reports-chart-head">
        <span>PEAK DAY</span>
        <strong>{money(peak)}</strong>
      </div>
      <div className="reports-chart-area">
        <svg
          viewBox="0 0 700 100"
          preserveAspectRatio="none"
          role="img"
          aria-label={ariaLabel}
        >
          <polyline
            points={polyline}
            fill="none"
            stroke="#c9ff20"
            strokeWidth="3"
            vectorEffect="non-scaling-stroke"
          />
        </svg>
      </div>
      <div className="reports-chart-axis">
        {points
          .filter((_, index) => index % labelEvery === 0)
          .map((point) => (
            <span key={point.key}>{point.axisLabel}</span>
          ))}
      </div>
    </div>
  );
}

/**
 * Report caveats, taken from the same DTO fields that produced the figures.
 *
 * These are rendered rather than hidden: several of the reports are deliberately
 * explicit about what the schema cannot measure, and an owner looking at a blank
 * column deserves to know it is a data limit rather than a broken screen.
 */
function ReportNotes({ title, notes, roles }) {
  const lines = (notes || []).filter(Boolean);

  if (lines.length === 0) {
    return null;
  }

  return (
    <aside className="reports-notes">
      <h3>{title}</h3>
      <ul>
        {lines.map((line) => (
          <li key={line}>{line}</li>
        ))}
      </ul>
      {roles ? <p className="reports-notes-roles">Readable by: {roles}</p> : null}
    </aside>
  );
}

/* =========================================
   REPORTS PAGE
========================================= */

function GarageOwnerReports() {
  const [activeTabKey, setActiveTabKey] = useState("daily");
  const [filters, setFilters] = useState(() => {
    const today = new Date();
    return {
      date: toDateValue(today),
      from: toDateValue(addDays(today, -29)),
      to: toDateValue(today),
      mechanicId: "",
      vehicleId: "",
      serviceType: "",
      status: "",
    };
  });
  const [rangePreset, setRangePreset] = useState("30d");
  const [report, setReport] = useState(IDLE_SECTION);
  const [mechanicOptions, setMechanicOptions] = useState([]);
  const [vehicleOptions, setVehicleOptions] = useState([]);
  const [referenceNotice, setReferenceNotice] = useState(null);
  const [exporting, setExporting] = useState(null);
  const [exportNotice, setExportNotice] = useState(null);
  const [refreshedAt, setRefreshedAt] = useState(null);

  const requestIdRef = useRef(0);
  const noticeTimerRef = useRef(null);

  const activeTab = useMemo(
    () => TABS.find((tab) => tab.key === activeTabKey) || TABS[0],
    [activeTabKey]
  );
  const supportsRange = RANGE_TABS.includes(activeTab.key);

  /* ---- filter dropdown reference data ---- */

  useEffect(() => {
    let cancelled = false;

    const loadReferenceData = async () => {
      const [mechanics, vehicles] = await Promise.allSettled([
        getMechanics(),
        getVehicles(),
      ]);

      if (cancelled) {
        return;
      }

      const failed = [];

      if (mechanics.status === "fulfilled") {
        setMechanicOptions(Array.isArray(mechanics.value) ? mechanics.value : []);
      } else {
        failed.push("mechanic");
      }

      if (vehicles.status === "fulfilled") {
        setVehicleOptions(Array.isArray(vehicles.value) ? vehicles.value : []);
      } else {
        failed.push("vehicle");
      }

      // A missing dropdown is not worth failing the report over — the report itself
      // is fetched independently — so the filter falls back to ALL and the gap is
      // stated plainly under the filter bar.
      setReferenceNotice(
        failed.length
          ? `The ${failed.join(" and ")} list could not be loaded, so that filter offers ALL only.`
          : null
      );
    };

    loadReferenceData();

    return () => {
      cancelled = true;
    };
  }, []);

  /* ---- the filters this tab's endpoint actually reads ---- */

  const requestFilters = useMemo(() => {
    const allowed = TAB_FILTERS[activeTabKey] || [];
    const payload = {};

    allowed.forEach((field) => {
      const value = filters[field];
      if (value !== "" && value !== undefined && value !== null) {
        payload[field] = value;
      }
    });

    return payload;
  }, [activeTabKey, filters]);

  const rangeInvalid =
    supportsRange &&
    Boolean(requestFilters.from) &&
    Boolean(requestFilters.to) &&
    requestFilters.from > requestFilters.to;

  /* ---- one request per tab + filter combination ---- */

  const loadReport = useCallback(async () => {
    if (rangeInvalid) {
      // Caught here rather than shipped to the API, which rejects a reversed range
      // with a 409. Naming the reason keeps the two paths reading alike.
      setReport({
        status: "error",
        data: null,
        statusCode: 409,
        error: "The start date is after the end date, so the range was not requested.",
      });
      return;
    }

    const requestId = requestIdRef.current + 1;
    requestIdRef.current = requestId;
    setReport({ status: "loading", data: null, error: null, statusCode: null });

    try {
      const data = await FETCHERS[activeTabKey](requestFilters);

      // A slower earlier request must not overwrite a newer tab or filter selection.
      if (requestIdRef.current !== requestId) {
        return;
      }

      setReport({
        status: "success",
        data,
        error: null,
        statusCode: null,
        empty: Boolean(data?.empty),
      });
      setRefreshedAt(new Date());
    } catch (error) {
      if (requestIdRef.current !== requestId) {
        return;
      }

      setReport({
        status: "error",
        data: null,
        error: error?.message || "The report could not be loaded.",
        statusCode: error?.status ?? null,
      });
    }
  }, [activeTabKey, requestFilters, rangeInvalid]);

  useEffect(() => {
    loadReport();
  }, [loadReport]);

  useEffect(
    () => () => {
      if (noticeTimerRef.current) {
        clearTimeout(noticeTimerRef.current);
      }
    },
    []
  );

  /* ---- handlers ---- */

  const showExportNotice = (notice) => {
    setExportNotice(notice);

    if (noticeTimerRef.current) {
      clearTimeout(noticeTimerRef.current);
    }

    noticeTimerRef.current = setTimeout(() => setExportNotice(null), 8000);
  };

  const applyPreset = (presetKey) => {
    const base = new Date();
    const ranges = {
      today: [base, base],
      "7d": [addDays(base, -6), base],
      "30d": [addDays(base, -29), base],
      month: [new Date(base.getFullYear(), base.getMonth(), 1), base],
    };
    const [from, to] = ranges[presetKey] || ranges["30d"];

    setRangePreset(presetKey);
    setFilters((previous) => ({
      ...previous,
      from: toDateValue(from),
      to: toDateValue(to),
    }));
  };

  const handleFilterChange = (field) => (event) => {
    const { value } = event.target;

    if (field === "from" || field === "to") {
      // Typing a date by hand means the quick range no longer describes what is
      // selected, so the highlight is dropped rather than left lying.
      setRangePreset("");
    }

    setFilters((previous) => ({ ...previous, [field]: value }));
  };

  const optionFilterFields = ["mechanicId", "vehicleId", "serviceType", "status"];
  const hasOptionFilters = optionFilterFields.some((field) => filters[field] !== "");

  const clearOptionFilters = () => {
    setFilters((previous) => ({
      ...previous,
      mechanicId: "",
      vehicleId: "",
      serviceType: "",
      status: "",
    }));
  };

  const handleExport = async (format) => {
    setExporting(format);

    try {
      const { blob, filename } = await downloadReportExport(
        activeTab.report,
        format,
        requestFilters
      );

      triggerBrowserDownload(blob, filename);
      showExportNotice({
        tone: "success",
        message: `${filename} was rendered server-side from the same ${activeTab.title.toLowerCase()} shown here.`,
      });
    } catch (error) {
      showExportNotice({
        tone: "error",
        message: error?.message || "The export could not be generated.",
      });
    } finally {
      setExporting(null);
    }
  };

  /* ---- derived view state ---- */

  const data = report.status === "success" ? report.data : null;
  const isReady = report.status === "success";
  const kpi = (value) =>
    isReady ? value : report.status === "loading" ? "…" : "—";
  const roleHint =
    report.status === "error" && (report.statusCode === 401 || report.statusCode === 403)
      ? `Reports of this type are readable by: ${TAB_ROLES[activeTab.key]}.`
      : null;

  const scopeLabel = useMemo(() => {
    if (activeTabKey === "daily") {
      return `Jobs assigned on ${formatDayLabel(requestFilters.date)}`;
    }
    return `${formatDayLabel(requestFilters.from)} to ${formatDayLabel(requestFilters.to)}`;
  }, [activeTabKey, requestFilters]);

  const activeFilterFields = TAB_FILTERS[activeTab.key] || [];
  const reload = () => loadReport();

  /* FR-REP-1 — jobs on one day, with the status breakdown that sums to the total. */
  const renderDailyWorkshop = () => {
    const statusRows = Object.entries(data?.statusBreakdown || {}).map(
      ([status, count]) => ({
        key: status,
        label: readableStatus(status),
        value: count,
        meta:
          toNumber(data?.totalJobs) > 0
            ? `${formatShare(toNumber(count) / toNumber(data.totalJobs))} of jobs`
            : "",
      })
    );
    const hasStatusRows = statusRows.length > 0;

    return (
      <>
        <KpiGrid>
          <KpiCard
            label="JOBS ASSIGNED"
            value={kpi(data?.totalJobs ?? 0)}
            hint={formatDayLabel(data?.date || requestFilters.date)}
          />
          <KpiCard
            label="COMPLETED"
            value={kpi(data?.completedJobs ?? 0)}
            hint="Reached the terminal Delivered state"
          />
          <KpiCard
            label="PENDING"
            value={kpi(data?.pendingJobs ?? 0)}
            hint="Awaited or under way at the report time"
          />
          <KpiCard
            label="INVOICED"
            value={kpi(formatCurrency(data?.invoicedAmount))}
            hint="Invoices raised for these jobs"
            tone="accent"
          />
          <KpiCard
            label="COLLECTED"
            value={kpi(formatCurrency(data?.collectedAmount))}
            hint="Successful payments against them"
          />
        </KpiGrid>

        <PanelState
          state={report}
          loadingText="Loading the daily workshop report…"
          emptyText="No job cards were assigned on this day, so there is nothing to break down."
          isEmpty={!hasStatusRows}
          onRetry={reload}
          extra={roleHint}
        />

        {hasStatusRows ? (
          <ReportPanel
            title="STATUS BREAKDOWN"
            subtitle={`Job cards assigned on ${formatDayLabel(data?.date || requestFilters.date)}`}
          >
            <BarList
              rows={statusRows}
              formatValue={formatCount}
              ariaLabel="Job cards by status"
            />
            <p className="reports-panel-foot">
              These counts sum to {formatCount(data?.totalJobs)} — the same figure
              shown as jobs assigned, because both come from the same query.
            </p>
          </ReportPanel>
        ) : null}

        <ReportNotes
          title="How to read this report"
          notes={[data?.limitation]}
          roles={TAB_ROLES.daily}
        />
      </>
    );
  };

  /* FR-REP-4 — revenue billed, cash collected, and how the two split by mode. */
  const renderRevenuePayments = () => {
    const trendPoints = (data?.dailyTrend || []).map((day) => ({
      key: String(day.date),
      value: day.total,
      invoiceCount: day.invoiceCount,
      axisLabel: formatAxisLabel(day.date),
    }));
    const modeRows = (data?.paymentModes || []).map((mode) => ({
      key: mode.mode,
      label: mode.mode ? String(mode.mode) : "Unspecified",
      value: mode.total,
      meta: toNumber(data?.collectedAmount) > 0
        ? `${formatCount(mode.paymentCount)} payments · ${formatShare(
            toNumber(mode.total) / toNumber(data.collectedAmount)
          )} of collections`
        : `${formatCount(mode.paymentCount)} payments`,
    }));
    const statusRows = (data?.invoiceStatuses || []).map((row) => ({
      status: row.status,
      invoiceCount: row.invoiceCount,
      total: row.total,
    }));
    const hasAnyRows = trendPoints.length > 0 || modeRows.length > 0 || statusRows.length > 0;

    return (
      <>
        <KpiGrid>
          <KpiCard
            label="INVOICED REVENUE"
            value={kpi(formatCurrency(data?.invoicedRevenue))}
            hint={`${formatCount(data?.invoiceCount)} invoices by invoice date`}
            tone="accent"
          />
          <KpiCard
            label="COLLECTED"
            value={kpi(formatCurrency(data?.collectedAmount))}
            hint={`${formatCount(data?.paymentCount)} successful payments`}
          />
          <KpiCard
            label="OUTSTANDING"
            value={kpi(formatCurrency(data?.outstandingAmount))}
            hint="Invoiced less collected"
            tone={toNumber(data?.outstandingAmount) < 0 ? "warning" : undefined}
          />
          <KpiCard
            label="AVERAGE INVOICE"
            value={kpi(formatMoney(data?.averageInvoiceValue))}
            hint="Invoiced over invoice count"
          />
          <KpiCard
            label="AVERAGE PAYMENT"
            value={kpi(formatMoney(data?.averagePaymentValue))}
            hint="Collected over payment count"
          />
          <KpiCard label="GST CHARGED" value={kpi(formatCurrency(data?.totalGst))} hint="Across these invoices" />
        </KpiGrid>

        <PanelState
          state={report}
          loadingText="Loading the revenue report…"
          emptyText="No invoices were raised and no payments were received in this range, so there is no revenue to report."
          isEmpty={!hasAnyRows}
          onRetry={reload}
          extra={roleHint}
        />

        {hasAnyRows ? (
          <>
            {trendPoints.length > 0 ? (
              <ReportPanel
                title="INVOICED VALUE PER DAY"
                subtitle="Dated by invoice date, so a payment that arrives later still counts against the day it was billed."
              >
                <TrendChart
                  points={trendPoints}
                  formatMoney={formatMoney}
                  ariaLabel="Invoiced revenue per day"
                />
                <p className="reports-panel-foot">
                  {formatCount(trendPoints.length)} day
                  {trendPoints.length === 1 ? "" : "s"} of invoiced value. Total discount
                  recorded in the range: {formatMoney(data?.totalDiscount)}.
                </p>
              </ReportPanel>
            ) : null}

            {modeRows.length > 0 ? (
              <ReportPanel
                title="COLLECTIONS BY PAYMENT MODE"
                subtitle="Sums to the collected figure above, dated by when the payment was taken."
              >
                <BarList
                  rows={modeRows}
                  formatValue={formatMoney}
                  ariaLabel="Collections by payment mode"
                />
              </ReportPanel>
            ) : null}

            {statusRows.length > 0 ? (
              <ReportPanel title="INVOICES BY STATUS" subtitle="Count and value of the invoices raised in this range.">
                <div className="reports-table-wrap">
                  <table className="reports-table">
                    <thead>
                      <tr>
                        <th>STATUS</th>
                        <th>INVOICES</th>
                        <th>VALUE</th>
                      </tr>
                    </thead>
                    <tbody>
                      {statusRows.map((row) => (
                        <tr key={row.status}>
                          <td>{readableStatus(row.status)}</td>
                          <td>{formatCount(row.invoiceCount)}</td>
                          <td>{formatMoney(row.total)}</td>
                        </tr>
                      ))}
                    </tbody>
                  </table>
                </div>
              </ReportPanel>
            ) : null}

            <ReportNotes
              title="How to read this report"
              notes={[
                "Invoiced revenue is dated by invoice date; collected is dated by the payment timestamp. The two will disagree whenever a payment crosses the boundary of the selected range — that is a fact about the data, not a rounding error.",
                "Outstanding is invoiced less collected and is therefore not a per-period figure: it can go negative when payments for earlier invoices arrive inside this range.",
                "Only successful payments are counted. Pending, failed and refunded payments are excluded by the reporting query.",
              ]}
              roles={TAB_ROLES.revenue}
            />

          </>
        ) : null}
      </>
    );
  };

  /* FR-REP-2 — one row per mechanic. The endpoint returns a list, not an object. */
  const renderMechanicPerformance = () => {
    const rows = Array.isArray(data) ? data : [];
    const totals = rows.reduce(
      (accumulator, row) => ({
        assigned: accumulator.assigned + toNumber(row.assignedJobs),
        completed: accumulator.completed + toNumber(row.completedJobs),
        open: accumulator.open + toNumber(row.openJobs),
        revenue: accumulator.revenue + toNumber(row.totalRevenue),
      }),
      { assigned: 0, completed: 0, open: 0, revenue: 0 }
    );
    const completionRows = rows
      .map((row) => ({
        key: String(row.mechanicId),
        label: row.mechanicName || `Mechanic #${row.mechanicId}`,
        value: row.completedJobs,
        meta: `${formatCount(row.assignedJobs)} assigned`,
      }))
      .sort((left, right) => toNumber(right.value) - toNumber(left.value));
    const unsupported = [
      ...new Set(rows.map((row) => row.unsupportedMetrics).filter(Boolean)),
    ];

    return (
      <>
        <KpiGrid>
          <KpiCard label="MECHANICS" value={kpi(formatCount(rows.length))} hint="With jobs in this range" />
          <KpiCard label="JOBS ASSIGNED" value={kpi(formatCount(totals.assigned))} hint="Across those mechanics" />
          <KpiCard label="JOBS COMPLETED" value={kpi(formatCount(totals.completed))} hint="Reached Delivered" />
          <KpiCard label="STILL OPEN" value={kpi(formatCount(totals.open))} hint="Not delivered by the range end" />
          <KpiCard
            label="COMPLETION RATE"
            value={kpi(totals.assigned > 0 ? formatShare(totals.completed / totals.assigned) : "—")}
            hint="Completed over assigned"
            tone="accent"
          />
          <KpiCard label="REVENUE ON COMPLETED JOBS" value={kpi(formatCurrency(totals.revenue))} hint="Summed across mechanics" />
        </KpiGrid>

        <PanelState
          state={report}
          loadingText="Loading mechanic performance…"
          emptyText="No mechanic had a job card assigned inside this range."
          isEmpty={rows.length === 0}
          onRetry={reload}
          extra={roleHint}
        />

        {rows.length > 0 ? (
          <>
            <ReportPanel
              title="COMPLETED JOBS BY MECHANIC"
              subtitle="Delivered job cards only — open work is shown in the table below."
            >
              <BarList
                rows={completionRows}
                formatValue={formatCount}
                ariaLabel="Completed jobs by mechanic"
              />
            </ReportPanel>

            <ReportPanel
              title="EVERY MECHANIC"
              subtitle="Turnaround and rating come only from completed jobs that carry a delivered timestamp and a customer rating."
            >
              <div className="reports-table-wrap">
                <table className="reports-table">
                  <thead>
                    <tr>
                      <th>MECHANIC</th>
                      <th>CODE</th>
                      <th>ASSIGNED</th>
                      <th>COMPLETED</th>
                      <th>OPEN</th>
                      <th>RATE</th>
                      <th>REVENUE</th>
                      <th>AVG / JOB</th>
                      <th>TURNAROUND</th>
                      <th>RATING</th>
                    </tr>
                  </thead>
                  <tbody>
                    {rows.map((row) => {
                      const rate =
                        toNumber(row.assignedJobs) > 0
                          ? formatShare(row.completionRate)
                          : "—";
                      const averagePerJob =
                        toNumber(row.completedJobs) > 0
                          ? formatMoney(row.averageRevenuePerJob)
                          : "—";
                      return (
                        <tr key={row.mechanicId}>
                          <td>{row.mechanicName || `Mechanic #${row.mechanicId}`}</td>
                          <td>{row.employeeCode || "—"}</td>
                          <td>{formatCount(row.assignedJobs)}</td>
                          <td>{formatCount(row.completedJobs)}</td>
                          <td>{formatCount(row.openJobs)}</td>
                          <td>{rate}</td>
                          <td>{formatMoney(row.totalRevenue)}</td>
                          <td>{averagePerJob}</td>
                          <td>
                            {isBlank(row.averageTurnaroundDays)
                              ? "—"
                              : formatDays(row.averageTurnaroundDays)}
                          </td>
                          <td>
                            {formatRating(row.averageCustomerRating, row.ratingCount)}
                          </td>
                        </tr>
                      );
                    })}
                  </tbody>
                </table>
              </div>
              <p className="reports-panel-foot">
                A dash means there was nothing to average: no completed job with the
                timestamp or rating the metric needs. It never means zero.
              </p>
            </ReportPanel>

            <ReportNotes
              title="How to read this report"
              notes={unsupported}
              roles={TAB_ROLES.mechanics}
            />

          </>
        ) : null}
      </>
    );
  };

  /* FR-REP-3 — parts leaving the shelf, from OUT stock movements only. */
  const renderPartsUsage = () => {
    const rows = data?.parts || [];
    const ranked = [...rows].sort(
      (left, right) => toNumber(right.quantityConsumed) - toNumber(left.quantityConsumed)
    );
    const chartRows = ranked.slice(0, 12).map((row) => ({
      key: String(row.partId),
      label: row.partName || row.sku || `Part #${row.partId}`,
      value: row.quantityConsumed,
      meta: [row.unit, `${formatCount(row.movementCount)} OUT movements`]
        .filter(Boolean)
        .join(" · "),
    }));

    return (
      <>
        <KpiGrid>
          <KpiCard
            label="DISTINCT PARTS"
            value={kpi(formatCount(data?.distinctPartCount ?? rows.length))}
            hint="Consumed at least once in this range"
          />
          <KpiCard
            label="UNITS CONSUMED"
            value={kpi(formatCount(data?.totalQuantityConsumed))}
            hint="Summed across every part"
          />
          <KpiCard
            label="ESTIMATED PARTS COST"
            value={kpi(formatCurrency(data?.totalEstimatedCost))}
            hint="Quantity at each part's current purchase price"
            tone="accent"
          />
          <KpiCard
            label="PARTS ON THE LIST"
            value={kpi(formatCount(rows.length))}
            hint="Rows in the table below"
          />
        </KpiGrid>

        <PanelState
          state={report}
          loadingText="Loading parts usage…"
          emptyText="No parts left the shelf as OUT stock movements in this range."
          isEmpty={rows.length === 0}
          onRetry={reload}
          extra={roleHint}
        />

        {rows.length > 0 ? (
          <>
            <ReportPanel
              title="MOST CONSUMED PARTS"
              subtitle={
                ranked.length > chartRows.length
                  ? `Top ${chartRows.length} of ${formatCount(ranked.length)} parts by units consumed.`
                  : "Ranked by units consumed in this range."
              }
            >
              <BarList
                rows={chartRows}
                formatValue={formatCount}
                ariaLabel="Units consumed per part"
              />
            </ReportPanel>

            <ReportPanel
              title="EVERY PART CONSUMED"
              subtitle="Ordered by units consumed. Job cards and mechanics are listed only where the stock movement recorded them."
            >
              <div className="reports-table-wrap">
                <table className="reports-table">
                  <thead>
                    <tr>
                      <th>PART</th>
                      <th>SKU</th>
                      <th>QTY</th>
                      <th>MOVEMENTS</th>
                      <th>UNIT COST</th>
                      <th>ESTIMATED COST</th>
                      <th>JOB CARDS</th>
                      <th>MECHANICS</th>
                    </tr>
                  </thead>
                  <tbody>
                    {ranked.map((row) => (
                      <tr key={row.partId}>
                        <td>{row.partName || `Part #${row.partId}`}</td>
                        <td>{row.sku || "—"}</td>
                        <td>
                          {formatCount(row.quantityConsumed)}
                          {row.unit ? ` ${row.unit}` : ""}
                        </td>
                        <td>{formatCount(row.movementCount)}</td>
                        <td>{formatMoney(row.currentPurchasePrice)}</td>
                        <td>{formatMoney(row.estimatedCost)}</td>
                        <td>
                          {(row.jobCardNumbers || []).length > 0
                            ? row.jobCardNumbers.join(", ")
                            : "—"}
                        </td>
                        <td>
                          {(row.mechanicNames || []).length > 0
                            ? row.mechanicNames.join(", ")
                            : "—"}
                        </td>
                      </tr>
                    ))}
                  </tbody>
                </table>
              </div>
            </ReportPanel>

            <ReportNotes
              title="Method"
              notes={[
                data?.methodology,
                "Unit cost is each part's current purchase price, so estimated cost is an approximation rather than a historical cost — the schema stores no cost as of the date a part was used.",
              ]}
              roles={TAB_ROLES.parts}
            />

          </>
        ) : null}
      </>
    );
  };

  /* FR-REP-5 — two populations kept apart: customers created, and customers served. */
  const renderCustomerGrowth = () => {
    const newCount = toNumber(data?.newCustomerCount);
    const repeatCount = toNumber(data?.repeatCustomerCount);
    const servedCount = toNumber(data?.customersServedInPeriod);
    const jobsCount = toNumber(data?.jobsInPeriod);
    const mixRows = [
      { key: "new", label: "New customers", value: newCount, meta: "Created in this range" },
      {
        key: "repeat",
        label: "Returning customers",
        value: repeatCount,
        meta: "On record before it, served during it",
      },
    ];
    const isEmptyReport = newCount === 0 && servedCount === 0 && jobsCount === 0;

    return (
      <>
        <KpiGrid>
          <KpiCard
            label="NEW CUSTOMERS"
            value={kpi(formatCount(data?.newCustomerCount))}
            hint="Created inside this range"
            tone="accent"
          />
          <KpiCard
            label="RETURNING CUSTOMERS"
            value={kpi(formatCount(data?.repeatCustomerCount))}
            hint="Existed before the range, served in it"
          />
          <KpiCard
            label="SERVED IN RANGE"
            value={kpi(formatCount(data?.customersServedInPeriod))}
            hint="New plus returning"
          />
          <KpiCard
            label="REPEAT RATE"
            value={kpi(
              data?.repeatRate === null || data?.repeatRate === undefined
                ? "—"
                : formatShare(data.repeatRate)
            )}
            hint={
              data?.repeatRate === null || data?.repeatRate === undefined
                ? "Not measurable: nobody was served, so there is no base"
                : "Returning over everyone served"
            }
          />
          <KpiCard
            label="CUSTOMERS ON RECORD"
            value={kpi(formatCount(data?.totalCustomersAtEndOfPeriod))}
            hint="At the end of the range"
          />
          <KpiCard
            label="JOB CARDS RAISED"
            value={kpi(formatCount(data?.jobsInPeriod))}
            hint="For the customers above"
          />
        </KpiGrid>

        <PanelState
          state={report}
          loadingText="Loading customer growth…"
          emptyText="Nobody was created or served in this range, so there is no growth to report."
          isEmpty={isEmptyReport}
          onRetry={reload}
          extra={roleHint}
        />

        {!isEmptyReport ? (
          <>
            <ReportPanel
              title="WHO WAS SERVED"
              subtitle="Growth is the first bar; the second is customers who were already on the books and came back."
            >
              <BarList
                rows={mixRows}
                formatValue={formatCount}
                ariaLabel="Customers created and customers returning"
              />
              <p className="reports-panel-foot">
                {formatCount(servedCount)} customer
                {servedCount === 1 ? "" : "s"} served across {formatCount(jobsCount)} job card
                {jobsCount === 1 ? "" : "s"}. A dash in the repeat rate means nobody was served
                in the range, not that nobody came back.
              </p>
            </ReportPanel>

            <ReportNotes
              title="How to read this report"
              notes={[data?.notes]}
              roles={TAB_ROLES.customers}
            />
          </>
        ) : null}
      </>
    );
  };

  /* FR-REP-4 — revenue against the one cost the schema can actually measure. */
  const renderProfitAnalysis = () => {
    const invoiceCount = toNumber(data?.invoiceCount);
    const paymentCount = toNumber(data?.paymentCount);
    const partsQuantity = toNumber(data?.partsConsumedQuantity);
    const isEmptyReport = invoiceCount === 0 && paymentCount === 0 && partsQuantity === 0;
    const profitAvailable = Boolean(data?.profitAvailable);
    const revenueLessKnownCost =
      toNumber(data?.invoicedRevenue) - toNumber(data?.partsCostEstimate);

    return (
      <>
        <KpiGrid>
          <KpiCard
            label="INVOICED REVENUE"
            value={kpi(formatCurrency(data?.invoicedRevenue))}
            hint={`${formatCount(data?.invoiceCount)} invoices in this range`}
            tone="accent"
          />
          <KpiCard
            label="COLLECTED"
            value={kpi(formatCurrency(data?.collectedAmount))}
            hint={`${formatCount(data?.paymentCount)} successful payments`}
          />
          <KpiCard
            label="OUTSTANDING"
            value={kpi(formatCurrency(data?.outstandingAmount))}
            hint="Invoiced less collected"
          />
          <KpiCard
            label="PARTS COST (LOWER BOUND)"
            value={kpi(formatCurrency(data?.partsCostEstimate))}
            hint={`${formatCount(data?.partsConsumedQuantity)} units consumed`}
          />
          <KpiCard
            label="GROSS PROFIT"
            value={kpi(profitAvailable ? formatCurrency(data?.grossProfit) : "Not computable")}
            hint={
              profitAvailable
                ? "Revenue less the costs the data holds"
                : "Labour cost is not stored against a job card, so no true profit figure exists"
            }
            tone="warning"
          />
        </KpiGrid>

        <PanelState
          state={report}
          loadingText="Loading profit analysis…"
          emptyText="Nothing was invoiced, collected or consumed in this range, so there is no result to analyse."
          isEmpty={isEmptyReport}
          onRetry={reload}
          extra={roleHint}
        />

        {!isEmptyReport ? (
          <>
            <ReportPanel
              title="FROM REVENUE TO COST"
              subtitle="Every line below is either a measured figure or an explicit gap — nothing is inferred."
            >
              <div className="reports-table-wrap">
                <table className="reports-table">
                  <thead>
                    <tr>
                      <th>LINE</th>
                      <th>VALUE</th>
                      <th>WHERE IT COMES FROM</th>
                    </tr>
                  </thead>
                  <tbody>
                    <tr>
                      <td>Invoiced revenue</td>
                      <td>{formatMoney(data?.invoicedRevenue)}</td>
                      <td>
                        Invoice totals raised in the range ({formatCount(data?.invoiceCount)}{" "}
                        invoices), dated by invoice date.
                      </td>
                    </tr>
                    <tr>
                      <td>Collected</td>
                      <td>{formatMoney(data?.collectedAmount)}</td>
                      <td>
                        Successful payments taken in the range (
                        {formatCount(data?.paymentCount)} payments), dated by payment time.
                      </td>
                    </tr>
                    <tr>
                      <td>Outstanding</td>
                      <td>{formatMoney(data?.outstandingAmount)}</td>
                      <td>Invoiced less collected. Derived, so it is not a per-period figure.</td>
                    </tr>
                    <tr>
                      <td>Parts consumed</td>
                      <td>{formatCount(data?.partsConsumedQuantity)}</td>
                      <td>Units leaving the shelf via OUT stock movements in the range.</td>
                    </tr>
                    <tr>
                      <td>Parts cost estimate</td>
                      <td>{formatMoney(data?.partsCostEstimate)}</td>
                      <td>
                        Those units valued at each part's current purchase price — a lower bound
                        on cost, not a historical one.
                      </td>
                    </tr>
                    <tr>
                      <td>Labour cost</td>
                      <td>Not recorded</td>
                      <td>
                        No column on a job card holds the labour cost incurred, so it cannot be
                        added here.
                      </td>
                    </tr>
                    <tr>
                      <td>Revenue less known cost</td>
                      <td>{formatMoney(revenueLessKnownCost)}</td>
                      <td>
                        Subtraction of the two lines above. It is not profit, and is not labelled
                        as such, because the labour half of the cost is missing.
                      </td>
                    </tr>
                    <tr>
                      <td>Gross profit</td>
                      <td>{profitAvailable ? formatMoney(data?.grossProfit) : "Not computable"}</td>
                      <td>
                        Reported by the server as unavailable when the cost side is incomplete,
                        which is the case for this schema.
                      </td>
                    </tr>
                  </tbody>
                </table>
              </div>
            </ReportPanel>

            <ReportNotes
              title="What this report cannot tell you"
              notes={data?.limitations}
              roles={TAB_ROLES.profit}
            />

          </>
        ) : null}
      </>
    );
  };

  return (
    <div className="reports-page">
      <header className="reports-header">
        <div className="reports-header-copy">
          <span className="reports-eyebrow">{activeTab.kicker}</span>
          <h1>REPORTS</h1>
          <p className="reports-header-sub">
            Every figure below is read from the live database through the reporting
            API. Nothing on this screen is estimated, sampled or seeded.
          </p>
        </div>

        <div className="reports-header-actions">
          <div className="reports-export-group">
            <span className="reports-export-label">EXPORT THIS REPORT</span>
            <div className="reports-export-buttons">
              <button
                type="button"
                className="reports-button-primary"
                onClick={() => handleExport("pdf")}
                disabled={exporting !== null || rangeInvalid}
              >
                {exporting === "pdf" ? "GENERATING…" : "PDF"}
              </button>
              <button
                type="button"
                className="reports-button-ghost"
                onClick={() => handleExport("xlsx")}
                disabled={exporting !== null || rangeInvalid}
              >
                {exporting === "xlsx" ? "GENERATING…" : "EXCEL"}
              </button>
            </div>
          </div>

          <div className="reports-refresh">
            <span>
              {refreshedAt
                ? `Loaded ${refreshedAt.toLocaleTimeString("en-IN", {
                    hour: "2-digit",
                    minute: "2-digit",
                  })}`
                : "Not loaded yet"}
            </span>
            <button
              type="button"
              className="reports-button-ghost"
              onClick={reload}
              disabled={report.status === "loading"}
            >
              {report.status === "loading" ? "LOADING…" : "REFRESH"}
            </button>
          </div>
        </div>
      </header>

      {exportNotice ? (
        <p
          className={`reports-feedback reports-feedback-${exportNotice.tone}`}
          role={exportNotice.tone === "error" ? "alert" : "status"}
        >
          {exportNotice.message}
        </p>
      ) : null}

      <nav className="reports-tabs" aria-label="Report types">
        {TABS.map((tab) => (
          <button
            key={tab.key}
            type="button"
            className={`reports-tab${tab.key === activeTab.key ? " active" : ""}`}
            onClick={() => setActiveTabKey(tab.key)}
            aria-current={tab.key === activeTab.key ? "true" : undefined}
          >
            {tab.label}
          </button>
        ))}
      </nav>

      <section className="reports-filters" aria-label={`${activeTab.title} filters`}>
        {supportsRange ? (
          <div className="reports-presets">
            <span className="reports-filter-label">QUICK RANGE</span>
            <div className="reports-preset-group">
              {RANGE_PRESETS.map((preset) => (
                <button
                  key={preset.key}
                  type="button"
                  className={`reports-preset${
                    rangePreset === preset.key ? " active" : ""
                  }`}
                  onClick={() => applyPreset(preset.key)}
                >
                  {preset.label}
                </button>
              ))}
            </div>
          </div>
        ) : null}

        <div className="reports-filter-grid">
          {activeFilterFields.includes("date") ? (
            <label className="reports-field">
              <span>DAY</span>
              <input
                type="date"
                value={filters.date}
                max={toDateValue(new Date())}
                onChange={handleFilterChange("date")}
              />
            </label>
          ) : null}

          {activeFilterFields.includes("from") ? (
            <label className="reports-field">
              <span>FROM</span>
              <input
                type="date"
                value={filters.from}
                onChange={handleFilterChange("from")}
              />
            </label>
          ) : null}

          {activeFilterFields.includes("to") ? (
            <label className="reports-field">
              <span>TO</span>
              <input
                type="date"
                value={filters.to}
                onChange={handleFilterChange("to")}
              />
            </label>
          ) : null}

          {activeFilterFields.includes("mechanicId") ? (
            <label className="reports-field">
              <span>MECHANIC</span>
              <select
                value={filters.mechanicId}
                onChange={handleFilterChange("mechanicId")}
              >
                <option value="">ALL MECHANICS</option>
                {mechanicOptions.map((mechanic) => (
                  <option key={mechanic.id} value={mechanic.id}>
                    {mechanicLabel(mechanic)}
                  </option>
                ))}
              </select>
            </label>
          ) : null}

          {activeFilterFields.includes("vehicleId") ? (
            <label className="reports-field">
              <span>VEHICLE</span>
              <select
                value={filters.vehicleId}
                onChange={handleFilterChange("vehicleId")}
              >
                <option value="">ALL VEHICLES</option>
                {vehicleOptions.map((vehicle) => (
                  <option key={vehicle.id} value={vehicle.id}>
                    {vehicleLabel(vehicle)}
                  </option>
                ))}
              </select>
            </label>
          ) : null}

          {activeFilterFields.includes("serviceType") ? (
            <label className="reports-field">
              <span>SERVICE TYPE</span>
              <select
                value={filters.serviceType}
                onChange={handleFilterChange("serviceType")}
              >
                <option value="">ALL SERVICES</option>
                {SERVICE_TYPE_OPTIONS.map((service) => (
                  <option key={service} value={service}>
                    {service}
                  </option>
                ))}
              </select>
            </label>
          ) : null}

          {activeFilterFields.includes("status") ? (
            <label className="reports-field">
              <span>JOB STATUS</span>
              <select value={filters.status} onChange={handleFilterChange("status")}>
                <option value="">ALL STATUSES</option>
                {JOB_STATUS_OPTIONS.map((status) => (
                  <option key={status} value={status}>
                    {readableStatus(status)}
                  </option>
                ))}
              </select>
            </label>
          ) : null}

          <button
            type="button"
            className="reports-button-ghost reports-clear"
            onClick={clearOptionFilters}
            disabled={!hasOptionFilters}
          >
            CLEAR FILTERS
          </button>
        </div>

        <p className="reports-filter-scope">
          {scopeLabel}
          {rangeInvalid ? " · start date is after end date" : ""}
        </p>

        {referenceNotice ? (
          <p className="reports-filter-warning">{referenceNotice}</p>
        ) : null}
      </section>

      {activeTab.key === "daily" ? renderDailyWorkshop() : null}
      {activeTab.key === "revenue" ? renderRevenuePayments() : null}
      {activeTab.key === "mechanics" ? renderMechanicPerformance() : null}
      {activeTab.key === "parts" ? renderPartsUsage() : null}
      {activeTab.key === "customers" ? renderCustomerGrowth() : null}
      {activeTab.key === "profit" ? renderProfitAnalysis() : null}

    </div>
  );
}

export default GarageOwnerReports;
