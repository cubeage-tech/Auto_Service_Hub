import { fetchWithAuth } from "./api";

const API_BASE_URL = (
  import.meta.env.VITE_API_BASE_URL ||
  (import.meta.env.DEV ? "/api/v1" : "http://localhost:8081/api/v1")
).replace(/\/+$/, "");

/**
 * Billing service (SRS 4.9).
 *
 * Wraps the three billing controllers that already exist on the backend —
 * /api/v1/invoices, /api/v1/estimates and /api/v1/payments. Nothing here is
 * new: no endpoint is invented and no request body carries a figure the server
 * owns.
 *
 * Successful calls are wrapped by the backend in ApiResponse<T>
 * ({ success, message, data }). Failures are NOT wrapped - GlobalExceptionHandler
 * returns a bare ApiErrorResponse ({ code, message, correlationId, timestamp }).
 * Both shapes carry a `message`, so one read path handles either.
 */
async function requestData(path, options = {}) {
  const url = `${API_BASE_URL}${path}`;
  let response;

  try {
    response = await fetchWithAuth(url, {
      ...options,
      headers: {
        ...(options.body ? { "Content-Type": "application/json" } : {}),
        ...options.headers,
      },
    });
  } catch (error) {
    // fetchWithAuth throws its own Error when a 401 refresh fails. That is a
    // session problem, not a connectivity one, so it is passed through intact
    // rather than rewritten as "unable to reach the API".
    if (!(error instanceof TypeError)) {
      throw error;
    }
    throw new Error(`Unable to reach the billing API at ${url}.`);
  }

  let body = null;
  try {
    body = response.status === 204 ? null : await response.json();
  } catch {
    if (!response.ok) {
      throw new Error(
        `Billing API request failed (${response.status} ${response.statusText}).`
      );
    }
    throw new Error(`Billing API returned invalid JSON (${response.status}).`);
  }

  if (!response.ok) {
    const message =
      body?.message ||
      `Billing API request failed (${response.status} ${response.statusText}).`;

    if (response.status === 401 || response.status === 403) {
      throw new Error(`${message} Sign in with an authorized account and try again.`);
    }

    if (body?.correlationId) {
      throw new Error(`${message} (Reference: ${body.correlationId})`);
    }

    throw new Error(message);
  }

  if (!body?.success || body.data == null) {
    throw new Error(body?.message || "Billing API returned an unexpected response.");
  }

  return body.data;
}

/** DELETE returns 204 with no body, so it skips the envelope check. */
async function requestVoid(path) {
  const url = `${API_BASE_URL}${path}`;
  let response;

  try {
    response = await fetchWithAuth(url, { method: "DELETE" });
  } catch (error) {
    // fetchWithAuth throws its own Error when a 401 refresh fails. That is a
    // session problem, not a connectivity one, so it is passed through intact
    // rather than rewritten as "unable to reach the API".
    if (!(error instanceof TypeError)) {
      throw error;
    }
    throw new Error(`Unable to reach the billing API at ${url}.`);
  }

  if (response.ok) {
    return;
  }

  let body = null;
  try {
    body = await response.json();
  } catch {
    /* fall through to the status-based message below */
  }

  const message =
    body?.message ||
    `Billing API request failed (${response.status} ${response.statusText}).`;

  if (response.status === 401 || response.status === 403) {
    throw new Error(`${message} Sign in with an authorized account and try again.`);
  }

  if (body?.correlationId) {
    throw new Error(`${message} (Reference: ${body.correlationId})`);
  }

  throw new Error(message);
}

async function getAllPages(path, sort) {
  const pageSize = 100;
  const rows = [];

  for (let pageNumber = 0; ; pageNumber += 1) {
    const separator = path.includes("?") ? "&" : "?";
    const page = await requestData(
      `${path}${separator}page=${pageNumber}&size=${pageSize}&sort=${sort}`
    );

    if (!Array.isArray(page?.content)) {
      throw new Error("Billing API returned an unexpected page format.");
    }

    rows.push(...page.content);
    if (pageNumber + 1 >= (page.totalPages ?? 1) || page.content.length === 0) {
      return rows;
    }
  }
}

const sendJson = (method, body) => ({ method, body: JSON.stringify(body) });

/* ==================================================================
   INVOICES — /api/v1/invoices
   ================================================================== */

/**
 * The backend has no invoice-number column, so nothing is generated here. The
 * page derives a display label ("INV-<id>") from the id it receives.
 */
export function getInvoices() {
  return getAllPages("/invoices", "invoiceDate,desc");
}

export function getInvoice(id) {
  return requestData(`/invoices/${id}`);
}

export function getInvoicesByJobCard(jobCardId) {
  return getAllPages(`/invoices/job-card/${jobCardId}`, "invoiceDate,desc");
}

/**
 * POST /api/v1/invoices
 *
 * Only `jobCardId`, `discount`, `status` and `items` are sent. `subtotal`,
 * `gst` and `total` are computed by BillingCalculator on the server and any
 * values sent for them are ignored, so they are deliberately omitted.
 */
export function createInvoice(invoice) {
  return requestData("/invoices", sendJson("POST", invoice));
}

/**
 * PUT /api/v1/invoices/{id} - full replace.
 *
 * `status` is restricted server-side to PENDING and CANCELLED; PAID and
 * PARTIALLY_PAID are derived from recorded payments and are rejected with 409.
 */
export function updateInvoice(id, invoice) {
  return requestData(`/invoices/${id}`, sendJson("PUT", invoice));
}

/** DELETE /api/v1/invoices/{id} - 204, or 409 if the invoice is already PAID. */
export function deleteInvoice(id) {
  return requestVoid(`/invoices/${id}`);
}

/** GET /api/v1/invoices/{id}/outstanding - total minus successful payments. */
export function getInvoiceOutstanding(id) {
  return requestData(`/invoices/${id}/outstanding`);
}

/**
 * POST /api/v1/invoices/{id}/labour
 *
 * Bills a repair task's labour onto the invoice. Only the jobTaskId is sent:
 * the description, quantity (1) and unit price (JobTask.labourCost) are derived
 * from the task and the invoice totals are recalculated server-side, so a line
 * entered this way is checked against double-billing.
 *
 * 409 if the task belongs to a different job card, is already billed on this
 * invoice, or the invoice is already PAID.
 */
export function addInvoiceLabour(id, jobTaskId) {
  return requestData(`/invoices/${id}/labour`, sendJson("POST", { jobTaskId: Number(jobTaskId) }));
}

/* ==================================================================
   ESTIMATES — /api/v1/estimates
   ================================================================== */

export function getEstimates() {
  return getAllPages("/estimates", "createdAt,desc");
}

export function getEstimate(id) {
  return requestData(`/estimates/${id}`);
}

export function getEstimatesByJobCard(jobCardId) {
  return getAllPages(`/estimates/job-card/${jobCardId}`, "createdAt,desc");
}

/**
 * POST /api/v1/estimates
 *
 * Same contract as invoices except the money field is `tax`, and there is no
 * invoiceDate. `subtotal`, `tax` and `total` are server-calculated.
 */
export function createEstimate(estimate) {
  return requestData("/estimates", sendJson("POST", estimate));
}

/** PUT /api/v1/estimates/{id} - refused with 409 once the estimate is CONVERTED. */
export function updateEstimate(id, estimate) {
  return requestData(`/estimates/${id}`, sendJson("PUT", estimate));
}

/** DELETE /api/v1/estimates/{id} - 204, or 409 once the estimate is CONVERTED. */
export function deleteEstimate(id) {
  return requestVoid(`/estimates/${id}`);
}

/**
 * POST /api/v1/estimates/{id}/convert - no request body by design.
 *
 * Returns the created InvoiceResponseDTO. 409 if the estimate was already
 * converted or has no lines. Not permitted for SERVICE_ADVISOR.
 */
export function convertEstimateToInvoice(id) {
  return requestData(`/estimates/${id}/convert`, { method: "POST" });
}

/* ==================================================================
   PAYMENTS — /api/v1/payments
   ================================================================== */

export function getPayments() {
  return getAllPages("/payments", "paidAt,desc");
}

/** Payments against one invoice, oldest first. */
export function getInvoicePayments(invoiceId) {
  return getAllPages(`/payments/invoice/${invoiceId}`, "id,asc");
}

/**
 * POST /api/v1/payments
 *
 * `status` is omitted so the backend applies its SUCCESS default - only a
 * SUCCESS payment reduces the outstanding balance. `amount` must be greater
 * than zero and, once settled, must not exceed the outstanding amount (409).
 */
export function createPayment(payment) {
  return requestData("/payments", sendJson("POST", payment));
}

/** PUT /api/v1/payments/{id} */
export function updatePayment(id, payment) {
  return requestData(`/payments/${id}`, sendJson("PUT", payment));
}

/** DELETE /api/v1/payments/{id} - the invoice status is recomputed afterwards. */
export function deletePayment(id) {
  return requestVoid(`/payments/${id}`);
}

/* ==================================================================
   DOCUMENTS — /api/v1/invoices/{id}/pdf | /xlsx
   ================================================================== */

/**
 * Fetches a generated document through the authenticated client, so the
 * download carries the same Bearer token (and the same 401 refresh/retry) as
 * every other call.
 *
 * These two endpoints are the only server-side rendering this module has:
 * InvoiceController serves an OpenPDF document and a POI workbook. There is no
 * equivalent endpoint for estimates, and no print endpoint - the browser's own
 * print dialog is the only way to print one of these, once downloaded.
 */
async function downloadDocument(id, extension) {
  const url = `${API_BASE_URL}/invoices/${id}/${extension}`;
  let response;

  try {
    response = await fetchWithAuth(url);
  } catch (error) {
    // fetchWithAuth throws its own Error when a 401 refresh fails. That is a
    // session problem, not a connectivity one, so it is passed through intact
    // rather than rewritten as "unable to reach the API".
    if (!(error instanceof TypeError)) {
      throw error;
    }
    throw new Error(`Unable to reach the billing API at ${url}.`);
  }

  if (!response.ok) {
    let body = null;
    try {
      body = await response.json();
    } catch {
      /* a document endpoint may answer with an empty error body */
    }

    const message =
      body?.message || `Billing API request failed (${response.status} ${response.statusText}).`;

    if (response.status === 401 || response.status === 403) {
      throw new Error(`${message} Sign in with an authorized account and try again.`);
    }

    throw new Error(message);
  }

  const blob = await response.blob();
  const filename =
    parseContentDispositionFilename(response.headers.get("Content-Disposition")) ||
    `invoice-${id}.${extension}`;

  return { blob, filename };
}

/**
 * The filename is sent as RFC 5987 `filename*=UTF-8''...` by Spring's
 * ContentDisposition builder. Falls back to a quoted `filename="..."`.
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

export function downloadInvoicePdf(id) {
  return downloadDocument(id, "pdf");
}

export function downloadInvoiceExcel(id) {
  return downloadDocument(id, "xlsx");
}
