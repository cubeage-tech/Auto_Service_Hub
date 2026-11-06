import { fetchWithAuth } from "./api";

const API_BASE_URL = (
  import.meta.env.VITE_API_BASE_URL ||
  (import.meta.env.DEV ? "/api/v1" : "http://localhost:8081/api/v1")
).replace(/\/+$/, "");

/**
 * Successful part calls are wrapped by the backend in ApiResponse<T>
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
    const detail = error instanceof Error ? ` (${error.message})` : "";
    throw new Error(`Unable to reach the inventory API at ${url}.${detail}`);
  }

  let body = null;
  try {
    body = response.status === 204 ? null : await response.json();
  } catch {
    if (!response.ok) {
      throw new Error(
        `Inventory API request failed (${response.status} ${response.statusText}).`
      );
    }
    throw new Error(
      `Inventory API returned invalid JSON (${response.status}).`
    );
  }

  if (!response.ok) {
    const message =
      body?.message ||
      `Inventory API request failed (${response.status} ${response.statusText}).`;

    if (response.status === 401 || response.status === 403) {
      throw new Error(`${message} Sign in with an authorized account and try again.`);
    }

    if (body?.correlationId) {
      throw new Error(`${message} (Reference: ${body.correlationId})`);
    }

    throw new Error(message);
  }

  if (!body?.success || body.data == null) {
    throw new Error(body?.message || "Inventory API returned an unexpected response.");
  }

  return body.data;
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
      throw new Error("Inventory API returned an unexpected page format.");
    }

    rows.push(...page.content);
    if (pageNumber + 1 >= page.totalPages || page.content.length === 0) {
      return rows;
    }
  }
}

/** GET /api/v1/parts - every spare part, newest first. */
export function getParts() {
  return getAllPages("/parts", "id,desc");
}

/**
 * GET /api/v1/parts/low-stock - parts at or below their own minimum quantity.
 */
export function getLowStockParts() {
  return getAllPages("/parts/low-stock", "id,desc");
}

/** GET /api/v1/suppliers - supplier names for the form's supplier dropdown. */
export function getSuppliers() {
  return getAllPages("/suppliers", "name,asc");
}

/**
 * POST /api/v1/parts
 *
 * The payload is the form's full state mapped onto PartRequestDTO's property
 * names. Optional fields are sent as null rather than being dropped, so the
 * backend applies its own "absent means clear" rule on update instead of
 * silently keeping whatever was there before.
 */
export function createPart(part) {
  return requestData("/parts", {
    method: "POST",
    body: JSON.stringify(part),
  });
}

/** POST /api/v1/suppliers - create a new supplier */
export function createSupplier(supplier) {
  return requestData("/suppliers", {
    method: "POST",
    body: JSON.stringify(supplier),
  });
}