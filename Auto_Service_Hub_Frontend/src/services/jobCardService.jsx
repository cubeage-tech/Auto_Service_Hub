import { fetchWithAuth } from "./api";

const API_BASE_URL = (
  import.meta.env.VITE_API_BASE_URL ||
  (import.meta.env.DEV ? "/api/v1" : "http://localhost:8081/api/v1")
).replace(/\/+$/, "");

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
    throw new Error(`Unable to reach the job card API at ${url}.${detail}`);
  }

  let payload;
  try {
    payload = response.status === 204 ? null : await response.json();
  } catch {
    throw new Error(
      response.ok
        ? `Job card API returned invalid JSON (${response.status}).`
        : `Job card API request failed (${response.status}).`
    );
  }

  if (!response.ok) {
    const message = payload?.message || `Job card API request failed (${response.status}).`;
    if (response.status === 401 || response.status === 403) {
      throw new Error(`${message} Sign in with an authorized account and try again.`);
    }
    throw new Error(message);
  }

  if (response.status === 204) return null;
  if (!payload?.success || payload.data === undefined) {
    throw new Error(payload?.message || "Job card API returned an unexpected response.");
  }
  return payload.data;
}

async function getPages(path, sort = "id,desc") {
  const results = [];
  const pageSize = 100;
  for (let pageNumber = 0; ; pageNumber += 1) {
    const page = await requestData(
      `${path}?page=${pageNumber}&size=${pageSize}&sort=${encodeURIComponent(sort)}`
    );
    if (Array.isArray(page)) return page;
    if (!Array.isArray(page?.content)) {
      throw new Error("List API returned an unexpected page format.");
    }
    results.push(...page.content);
    if (pageNumber + 1 >= (page.totalPages ?? 1) || page.content.length === 0) {
      return results;
    }
  }
}

const sendJson = (method, body) => ({
  method,
  body: JSON.stringify(body),
});

export const getJobCards = () => getPages("/job-cards", "createdAt,desc");
export const getJobCard = (id) => requestData(`/job-cards/${id}`);
export const createJobCard = (jobCard) =>
  requestData("/job-cards", sendJson("POST", jobCard));
export const updateJobCard = (id, jobCard) =>
  requestData(`/job-cards/${id}`, sendJson("PUT", jobCard));

/**
 * Applied service packages, newest first.
 *
 * Used to offer the advisor the packages a vehicle already holds when a job
 * card is raised. Only applications for the selected vehicle are linkable — the
 * server re-checks that, and rejects a mismatch with a 409.
 */
export const getJobCardPackageApplications = () =>
  getPages("/package-applications", "id,desc");

export const getJobCardTasks = (id) => getPages(`/job-cards/${id}/tasks`, "id,asc");
export const createJobCardTask = (id, task) =>
  requestData(`/job-cards/${id}/tasks`, sendJson("POST", task));
export const updateJobCardTask = (id, task) =>
  requestData(`/job-tasks/${id}`, sendJson("PUT", task));
export const updateJobTaskStatus = (id, status) =>
  requestData(`/job-tasks/${id}/status`, sendJson("PUT", { status }));
export const updateJobTaskNotes = (id, workNotes) =>
  requestData(`/job-tasks/${id}/work-notes`, sendJson("PUT", { workNotes }));
export const assignJobTaskMechanic = (id, mechanicId) =>
  requestData(`/job-tasks/${id}/mechanic`, sendJson("PUT", { mechanicId }));

export const getJobCardParts = (id) => getPages(`/job-cards/${id}/parts`, "createdAt,asc");
export const consumeJobCardPart = (id, partId, quantity, reason) =>
  requestData(
    `/job-cards/${id}/parts`,
    sendJson("POST", { partId: Number(partId), quantity: Number(quantity), reason: reason || null })
  );
export const getJobCardCustomers = () => getPages("/customers");
export const getJobCardVehicles = () => getPages("/vehicles");
export const getJobCardMechanics = () => getPages("/mechanics");
export const getJobCardAppointments = () => getPages("/appointments", "appointmentAt,asc");
export const getJobCardPartCatalog = () => getPages("/parts", "name,asc");
