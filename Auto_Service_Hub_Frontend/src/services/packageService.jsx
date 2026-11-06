import { fetchWithAuth } from "./api";

const API_BASE_URL = (
  import.meta.env.VITE_API_BASE_URL ||
  (import.meta.env.DEV ? "/api/v1" : "http://localhost:8081/api/v1")
).replace(/\/+$/, "");

/**
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
    const detail = error instanceof Error ? ` (${error.message})` : "";
    throw new Error(`Unable to reach the service package API at ${url}.${detail}`);
  }

  let body = null;
  try {
    body = response.status === 204 ? null : await response.json();
  } catch {
    if (!response.ok) {
      throw new Error(
        `Service package API request failed (${response.status} ${response.statusText}).`
      );
    }
    throw new Error(
      `Service package API returned invalid JSON (${response.status}).`
    );
  }

  if (!response.ok) {
    const message =
      body?.message ||
      `Service package API request failed (${response.status} ${response.statusText}).`;

    if (response.status === 401 || response.status === 403) {
      throw new Error(`${message} Sign in with an authorized account and try again.`);
    }

    if (body?.correlationId) {
      throw new Error(`${message} (Reference: ${body.correlationId})`);
    }

    throw new Error(message);
  }

  if (!body?.success || body.data == null) {
    throw new Error(body?.message || "Service package API returned an unexpected response.");
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
    const detail = error instanceof Error ? ` (${error.message})` : "";
    throw new Error(`Unable to reach the service package API at ${url}.${detail}`);
  }

  if (!response.ok) {
    let body = null;
    try {
      body = await response.json();
    } catch {
      /* fall through to the status-based message below */
    }

    const message =
      body?.message ||
      `Service package API request failed (${response.status} ${response.statusText}).`;

    if (response.status === 401 || response.status === 403) {
      throw new Error(`${message} Sign in with an authorized account and try again.`);
    }

    throw new Error(message);
  }
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
      throw new Error("Service package API returned an unexpected page format.");
    }

    rows.push(...page.content);
    if (pageNumber + 1 >= page.totalPages || page.content.length === 0) {
      return rows;
    }
  }
}

/** GET /api/v1/service-packages - every package, newest first. */
export function getServicePackages() {
  return getAllPages("/service-packages", "id,desc");
}

/** GET /api/v1/service-packages/{id} */
export function getServicePackage(id) {
  return requestData(`/service-packages/${id}`);
}

/**
 * POST /api/v1/service-packages
 *
 * `type` is the package code shown on the card ("BASIC", "PREM-PLUS"), which is
 * what the form's "Package code" input collects.
 *
 * `items` is a list of { itemName, itemType } objects rather than the comma
 * separated text the form uses, because the backend stores the two kinds
 * separately and the card renders them differently.
 */
export function createServicePackage(servicePackage) {
  return requestData("/service-packages", {
    method: "POST",
    body: JSON.stringify(servicePackage),
  });
}

/**
 * PUT /api/v1/service-packages/{id} - full replace.
 *
 * There is no PATCH, so `active` is sent explicitly on every save. Omitting it
 * would leave the stored value alone (the backend preserves it), but sending it
 * keeps the request self-describing.
 */
export function updateServicePackage(id, servicePackage) {
  return requestData(`/service-packages/${id}`, {
    method: "PUT",
    body: JSON.stringify(servicePackage),
  });
}

/**
 * Flips a package's active flag through the existing PUT endpoint.
 *
 * <p>There is no dedicated activate/deactivate endpoint on the backend, so this
 * resends the whole record rather than inventing one. `ServicePackageRequestDTO`
 * requires name, type and price, which is why they are passed through from the
 * package the UI already holds.
 */
export function setServicePackageActive(pkg, active) {
  return updateServicePackage(pkg.id, {
    name: pkg.name,
    type: pkg.type,
    price: Number(pkg.price),
    active,
    duration: pkg.duration ?? null,
    gstRate: pkg.gstRate ?? null,
    discountPercentage: pkg.discountPercentage ?? null,
    color: pkg.color ?? null,
    popular: pkg.popular ?? false,
    items: (pkg.items ?? []).map((item) => ({
      itemName: item.itemName,
      itemType: item.itemType,
    })),
  });
}

/** DELETE /api/v1/service-packages/{id} - a hard delete, returns 204. */
export function deleteServicePackage(id) {
  return requestVoid(`/service-packages/${id}`);
}

/**
 * POST /api/v1/package-applications
 *
 * <p>Only the three ids are sent. The price, GST rate, discount and duration are
 * copied from the package server-side and returned as a stored snapshot, so the
 * client cannot quote terms the catalogue never agreed to — repricing the package
 * later must not restate what this customer already agreed to.
 *
 * <p>The backend answers 409 if the package is inactive, if the vehicle is not
 * the selected customer's, or if that package is already applied to that
 * vehicle; all three arrive here as a readable Error.
 */
export function applyServicePackage({ servicePackageId, customerId, vehicleId }) {
  return requestData("/package-applications", {
    method: "POST",
    body: JSON.stringify({ servicePackageId, customerId, vehicleId }),
  });
}

/** GET /api/v1/package-applications - every application, newest first. */
export function getPackageApplications() {
  return getAllPages("/package-applications", "id,desc");
}
