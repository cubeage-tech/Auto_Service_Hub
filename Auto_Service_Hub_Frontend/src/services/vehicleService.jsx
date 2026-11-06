import { fetchWithAuth } from "./api";

const API_BASE_URL = (
  import.meta.env.VITE_API_BASE_URL ||
  (import.meta.env.DEV ? "/api/v1" : "http://localhost:8081/api/v1")
).replace(/\/+$/, "");

async function requestData(path, options = {}) {
  let response;
  try {
    response = await fetchWithAuth(`${API_BASE_URL}${path}`, {
      ...options,
      headers: {
        ...(options.body ? { "Content-Type": "application/json" } : {}),
        ...options.headers,
      },
    });
  } catch (error) {
    const detail = error instanceof Error ? ` (${error.message})` : "";
    throw new Error(
      `Unable to reach the vehicle API at ${API_BASE_URL}${path}. Check that the backend is running and, for cross-origin deployments, that CORS allows this frontend.${detail}`
    );
  }

  let body;
  try {
    body = response.status === 204 ? null : await response.json();
  } catch {
    if (!response.ok) {
      throw new Error(`Vehicle API request failed (${response.status}).`);
    }
    throw new Error(`Vehicle API returned invalid JSON (${response.status}).`);
  }

  if (!response.ok) {
    const message = body?.message || `Vehicle API request failed (${response.status}).`;
    if (response.status === 401 || response.status === 403) {
      throw new Error(`${message} Sign in with an authorized account and try again.`);
    }
    throw new Error(message);
  }

  if (body && body.success === false) {
    throw new Error(body.message || "Vehicle API returned an unsuccessful response.");
  }

  return body?.data;
}

async function getAllPages(path, filters = {}) {
  const pageSize = 100;
  const allItems = [];
  let pageNumber = 0;

  while (true) {
    const params = new URLSearchParams({
      page: String(pageNumber),
      size: String(pageSize),
      sort: "id,desc",
    });
    if (filters.fuelType) params.set("fuelType", filters.fuelType);
    if (filters.status) params.set("status", filters.status);
    const page = await requestData(
      `${path}?${params.toString()}`
    );

    if (Array.isArray(page)) {
      return page;
    }
    if (!Array.isArray(page?.content)) {
      throw new Error("List API returned an unexpected page format.");
    }

    allItems.push(...page.content);
    pageNumber += 1;
    const hasMorePages =
      typeof page.totalPages === "number"
        ? pageNumber < page.totalPages
        : page.content.length === pageSize;
    if (!hasMorePages || page.content.length === 0) {
      return allItems;
    }
  }
}

export const getVehicles = (filters) => getAllPages("/vehicles", filters);

export const getCustomers = () => getAllPages("/customers");

export const createVehicle = (vehicle) =>
  requestData("/vehicles", {
    method: "POST",
    body: JSON.stringify(vehicle),
  });

export const updateVehicle = (id, vehicle) =>
  requestData(`/vehicles/${id}`, {
    method: "PUT",
    body: JSON.stringify(vehicle),
  });

export const getVehicle = (id) => requestData(`/vehicles/${id}`);

export const getVehicleServiceHistory = (id) =>
  requestData(`/vehicles/${id}/service-history`);

/**
 * GET /api/v1/customers/{customerId}/vehicles
 *
 * The backend filters by owner, so a picker can load only the chosen customer's
 * vehicles instead of pulling every vehicle in the garage and filtering on the
 * client. Returns the array directly (the endpoint answers ApiResponse<List>,
 * not a page).
 */
export const getCustomerVehicles = (customerId) =>
  requestData(`/customers/${customerId}/vehicles`);