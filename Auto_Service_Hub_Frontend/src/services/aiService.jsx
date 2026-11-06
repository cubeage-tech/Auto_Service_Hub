import { fetchWithAuth } from "./api";

const API_BASE_URL = (
  import.meta.env.VITE_API_BASE_URL ||
  (import.meta.env.DEV ? "/api/v1" : "http://localhost:8081/api/v1")
).replace(/\/+$/, "");

/**
 * AI Center calls.
 *
 * Successful responses are wrapped by the backend in ApiResponse<T>
 * ({ success, message, data }). Failures are NOT wrapped - GlobalExceptionHandler
 * returns a bare ApiErrorResponse ({ code, message, correlationId, timestamp }).
 * Both shapes carry a `message`, so one read path handles either.
 *
 * Every endpoint here is advisory only. The backend never creates or modifies a
 * job card, estimate or stock record on the strength of an AI result, so nothing
 * in this module performs an operational write.
 */
async function requestAiData(path, options = {}) {
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
    throw new Error(
      `Unable to reach the AI API at ${url}.${detail}`
    );
  }

  let body = null;
  try {
    body = response.status === 204 ? null : await response.json();
  } catch {
    if (!response.ok) {
      throw new Error(
        `AI API request failed (${response.status} ${response.statusText}).`
      );
    }
    throw new Error(
      `AI API returned invalid JSON (${response.status}).`
    );
  }

  if (!response.ok) {
    const message =
      body?.message ||
      `AI API request failed (${response.status} ${response.statusText}).`;

    if (response.status === 401 || response.status === 403) {
      throw new Error(
        `${message} AI diagnosis requires an account with a diagnostic role.`
      );
    }

    if (body?.correlationId) {
      throw new Error(`${message} (Reference: ${body.correlationId})`);
    }

    throw new Error(message);
  }

  if (body && body.success === false) {
    throw new Error(body.message || "AI API returned an unsuccessful response.");
  }

  return body?.data ?? null;
}

/**
 * POST /api/v1/ai/diagnosis
 *
 * Body follows DiagnosisRequestDTO exactly - only fields the DTO declares are
 * ever sent, and blank optional fields are omitted rather than sent as null:
 *
 *   symptoms           (String, required, @NotBlank, max 2000)  - at least 5
 *                      characters, enforced again server-side.
 *   vehicleId          (Long, optional) - when supplied the backend enriches
 *                      the AI context with make/model/year/mileage from the
 *                      database and 404s if the vehicle does not exist.
 *   jobCardId          (Long, optional) - an existing job card for context.
 *   inspectionFindings (String, optional, max 2000)
 *   technicianNotes    (String, optional, max 2000)
 *
 * Returns the DiagnosisResponseDTO body:
 *   { vehicleId, possibleIssue, recommendation, confidence (0.00-1.00),
 *     disclaimer, humanReviewRequired (always true), providerUnavailable,
 *     generatedAt }
 *
 * `providerUnavailable` is true whenever the configured provider did not answer
 * - including the default NoopAiProviderClient. When it is true, possibleIssue
 * and recommendation are placeholders and `confidence` is 0.00; the caller must
 * present that state rather than a diagnosis.
 */
export function diagnoseVehicle({
  vehicleId,
  jobCardId,
  symptoms,
  inspectionFindings,
  technicianNotes,
} = {}) {
  const trimmedSymptoms = typeof symptoms === "string" ? symptoms.trim() : "";

  if (trimmedSymptoms.length < 5) {
    throw new Error(
      "Describe the reported symptoms in at least 5 characters before running an AI diagnosis."
    );
  }

  const payload = { symptoms: trimmedSymptoms };

  if (Number.isInteger(vehicleId) && vehicleId > 0) {
    payload.vehicleId = vehicleId;
  }
  if (Number.isInteger(jobCardId) && jobCardId > 0) {
    payload.jobCardId = jobCardId;
  }

  const optionalText = { inspectionFindings, technicianNotes };
  for (const [field, value] of Object.entries(optionalText)) {
    const trimmed = typeof value === "string" ? value.trim() : "";
    if (trimmed) {
      payload[field] = trimmed;
    }
  }

  return requestAiData("/ai/diagnosis", {
    method: "POST",
    body: JSON.stringify(payload),
  });
}
