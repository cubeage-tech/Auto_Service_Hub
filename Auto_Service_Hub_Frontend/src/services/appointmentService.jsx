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
    throw new Error(`Unable to reach the appointment API at ${url}.${detail}`);
  }

  let body;
  try {
    body = response.status === 204 ? null : await response.json();
  } catch {
    if (!response.ok) {
      throw new Error(`Appointment API request failed (${response.status}).`);
    }
    throw new Error(`Appointment API returned invalid JSON (${response.status}).`);
  }

  if (!response.ok) {
    const message = body?.message || `Appointment API request failed (${response.status}).`;
    if (response.status === 401 || response.status === 403) {
      throw new Error(`${message} Sign in with an authorized account and try again.`);
    }
    throw new Error(message);
  }

  if (!body?.success || body.data == null) {
    throw new Error(body?.message || "Appointment API returned an unexpected response.");
  }

  return body.data;
}

export async function getAppointments() {
  const pageSize = 100;
  const appointments = [];

  for (let pageNumber = 0; ; pageNumber += 1) {
    const page = await requestData(
      `/appointments?page=${pageNumber}&size=${pageSize}&sort=appointmentAt,asc`
    );

    if (!Array.isArray(page?.content)) {
      throw new Error("Appointment list API returned an unexpected page format.");
    }

    appointments.push(...page.content);
    if (pageNumber + 1 >= page.totalPages || page.content.length === 0) {
      return appointments;
    }
  }
}

export function createAppointment(appointment) {
  return requestData("/appointments", {
    method: "POST",
    body: JSON.stringify(appointment),
  });
}

/* PUT /api/v1/appointments/{id}. AppointmentRequestDTO is @Valid on the
   backend and is the same shape create uses — there is no separate edit DTO.
   `status` is always sent: AppointmentServiceImpl.mapToEntity falls back to
   "SCHEDULED" when it arrives null, so omitting it would silently reset the
   status of the appointment being edited. */
export function updateAppointment(id, appointment) {
  return requestData(`/appointments/${id}`, {
    method: "PUT",
    body: JSON.stringify(appointment),
  });
}

/* These three paths sit alongside the existing @GetMapping("/{id}") route on
   the backend. A server that predates them matches "advisors" against "{id}",
   cannot bind that to a Long, and answers 400 "Parameter 'id' has an invalid
   value." — or 404, if it was never routed at all. That message says nothing
   about the real cause, so it is annotated here instead of being left to look
   like a validation problem. The original text is kept. */
const STALE_BACKEND_SIGNATURE =
  /has an invalid value|\(404\)|status:404|request failed \(404\)/i;

async function optionRequest(path, label) {
  try {
    return await requestData(path);
  } catch (error) {
    const detail = error instanceof Error ? error.message : String(error);
    if (!STALE_BACKEND_SIGNATURE.test(detail)) {
      throw error;
    }
    throw new Error(
      `${label} could not be loaded from ${path}. ${detail} ` +
        "This endpoint is not present on the running backend, which matched the " +
        "path against /appointments/{id}. Rebuild and restart the backend."
    );
  }
}

/* Bays this garage can book into. Served by the backend so the picker cannot
   drift away from the list AppointmentServiceImpl actually accepts. */
export async function getAppointmentBays() {
  const bays = await optionRequest("/appointments/bays", "Bay list");
  return Array.isArray(bays) ? bays : [];
}

/* Active users eligible to be assigned as an appointment's advisor. Resolved
   from the SERVICE_ADVISOR role server-side, so no name is ever hardcoded. */
export async function getAppointmentAdvisors() {
  const advisors = await optionRequest("/appointments/advisors", "Advisor list");
  return Array.isArray(advisors) ? advisors : [];
}

/* Booking types the form may offer (WALK_IN / ONLINE / PICKUP_DROP). Served by
   the backend so the picker cannot offer a value the service would reject. */
export async function getAppointmentTypes() {
  const types = await optionRequest("/appointments/types", "Appointment type list");
  return Array.isArray(types) ? types : [];
}
