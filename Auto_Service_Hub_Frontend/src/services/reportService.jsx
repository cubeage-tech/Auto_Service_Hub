import { fetchWithAuth } from "./api";

const API_BASE_URL = (
  import.meta.env.VITE_API_BASE_URL ||
  (import.meta.env.DEV ? "/api/v1" : "http://localhost:8081/api/v1")
).replace(/\/+$/, "");

/* ==================================================================
   REPORTS & ANALYTICS — SRS FR-REP-1 .. FR-REP-8
   ================================================================== */

/**
 * Report slugs, exactly as ReportController and ReportExportController map them.
 *
 * Every report has two endpoints: a JSON one under /reports for the screen and a
 * PDF/Excel one under /reports/export for the download. The export endpoints
 * accept exactly the filters their JSON counterpart accepts and carry exactly the
 * same role guards, so a download can never be narrower, nor more permissive,
 * than the report it mirrors.
 */
export const REPORT_KEYS = {
  DAILY_WORKSHOP: "daily-workshop",
  REVENUE_PAYMENT: "revenue-payment",
  MECHANIC_PERFORMANCE: "mechanic-performance",
  PARTS_USAGE: "parts-usage",
  CUSTOMER_GROWTH: "customer-growth",
  PROFIT_ANALYSIS: "profit-analysis",
};

/** The two formats ReportExportService renders. */
const EXPORT_EXTENSIONS = { pdf: "pdf", xlsx: "xlsx" };

/**
 * Builds a query string from the filters that are actually set.
 *
 * Blank and nullish values are dropped rather than sent: the repository queries
 * read a null filter as "do not filter on this", but an empty string would arrive
 * as a filter on "" and match nothing at all.
 */
function buildQuery(filters = {}) {
  const params = new URLSearchParams();

  Object.entries(filters).forEach(([key, value]) => {
    if (value === null || value === undefined || value === "") {
      return;
    }
    params.set(key, String(value));
  });

  const query = params.toString();
  return query ? `?${query}` : "";
}

/**
 * Reads one ApiResponse envelope.
 *
 * A 401/403 is surfaced with the HTTP status attached to the Error, so the page
 * can tell "your signed-in role cannot read this report" apart from a genuine
 * failure without having to pattern-match on message text.
 */
async function requestData(path, noun) {
  const url = `${API_BASE_URL}${path}`;
  let response;

  try {
    response = await fetchWithAuth(url);
  } catch (error) {
    // fetchWithAuth throws its own Error when a 401 refresh fails; that is a
    // session problem, not a connectivity one, so it is passed through intact.
    if (!(error instanceof TypeError)) {
      throw error;
    }
    throw new Error(
      `${noun} could not be requested from ${url}. Check that the backend is running.`
    );
  }

  let body;
  try {
    body = response.status === 204 ? null : await response.json();
  } catch {
    throw new Error(
      response.ok
        ? `${noun} returned invalid JSON (${response.status}).`
        : `${noun} request failed (${response.status}).`
    );
  }

  if (!response.ok) {
    const message = body?.message || `${noun} request failed (${response.status}).`;
    const failure = new Error(
      response.status === 401 || response.status === 403
        ? `${message} Sign in with an account whose role may read this report.`
        : message
    );
    failure.status = response.status;
    throw failure;
  }

  if (!body?.success || body.data === undefined) {
    throw new Error(body?.message || `${noun} returned an unexpected response.`);
  }

  return body.data;
}

/* ==================================================================
   JSON REPORTS — /api/v1/reports
   ================================================================== */

/** FR-REP-1: one day's jobs and status breakdown. `date` defaults to today. */
export const getDailyWorkshopReport = (filters = {}) =>
  requestData(
    `/reports/daily-workshop${buildQuery(filters)}`,
    "Daily workshop report"
  );

/** FR-REP-4: invoiced revenue alongside payments actually collected. */
export const getRevenuePaymentReport = (filters = {}) =>
  requestData(`/reports/revenue${buildQuery(filters)}`, "Revenue report");

/** FR-REP-2: one row per mechanic for the period. */
export const getMechanicPerformanceReport = (filters = {}) =>
  requestData(
    `/reports/mechanic-performance${buildQuery(filters)}`,
    "Mechanic performance report"
  );

/** FR-REP-3: parts consumed (OUT movements only) in the period. */
export const getPartsUsageReport = (filters = {}) =>
  requestData(`/reports/parts-usage${buildQuery(filters)}`, "Parts usage report");

/** FR-REP-5: new customers, returning customers and jobs in the period. */
export const getCustomerGrowthReport = (filters = {}) =>
  requestData(
    `/reports/customer-growth${buildQuery(filters)}`,
    "Customer growth report"
  );

/** FR-REP-4: revenue, the cost that exists and the stated limits. */
export const getProfitAnalysisReport = (filters = {}) =>
  requestData(
    `/reports/profit-analysis${buildQuery(filters)}`,
    "Profit analysis report"
  );

/* ==================================================================
   PDF / EXCEL EXPORTS — /api/v1/reports/export
   ================================================================== */

/**
 * The filename is sent as RFC 5987 `filename*=UTF-8''...` by Spring's
 * ContentDisposition builder, and falls back to a quoted `filename="..."`.
 */
function parseContentDispositionFilename(header) {
  if (!header) {
    return null;
  }

  const extended = header.match(/filename\*=UTF-8''([^;]+)/i);
  if (extended) {
    try {
      return decodeURIComponent(extended[1].trim());
    } catch {
      return extended[1].trim();
    }
  }

  const quoted = header.match(/filename="([^"]+)"/i);
  if (quoted) {
    return quoted[1];
  }

  const bare = header.match(/filename=([^;]+)/i);
  return bare ? bare[1].trim() : null;
}

/**
 * Downloads one report as PDF or Excel.
 *
 * Fetched through the authenticated client rather than pointed at with a plain
 * link, so the request carries the same Bearer token (and the same 401
 * refresh/retry) as every other call in the app.
 *
 * Resolves to `{ blob, filename }`; the caller owns the browser download because
 * it also owns the button state and the success message.
 */
export async function downloadReportExport(reportKey, format, filters = {}) {
  const slug = REPORT_KEYS[reportKey];
  if (!slug) {
    throw new Error(`Unknown report "${reportKey}" cannot be exported.`);
  }

  const extension = EXPORT_EXTENSIONS[format];
  if (!extension) {
    throw new Error(`Unsupported export format "${format}".`);
  }

  const url =
    `${API_BASE_URL}/reports/export/${slug}.${extension}` + buildQuery(filters);
  let response;

  try {
    response = await fetchWithAuth(url);
  } catch (error) {
    if (!(error instanceof TypeError)) {
      throw error;
    }
    throw new Error(`Unable to reach the report export endpoint at ${url}.`);
  }

  if (!response.ok) {
    let body = null;
    try {
      body = await response.json();
    } catch {
      /* an export endpoint may answer with an empty error body */
    }

    const message =
      body?.message ||
      `Report export failed (${response.status} ${response.statusText}).`;

    if (response.status === 401 || response.status === 403) {
      throw new Error(
        `${message} Sign in with an account whose role may export this report.`
      );
    }

    throw new Error(message);
  }

  const blob = await response.blob();
  const filename =
    parseContentDispositionFilename(response.headers.get("Content-Disposition")) ||
    `${slug}.${extension}`;

  return { blob, filename };
}

