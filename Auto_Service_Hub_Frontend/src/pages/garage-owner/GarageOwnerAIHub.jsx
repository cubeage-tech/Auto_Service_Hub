import React, { useCallback, useEffect, useMemo, useState } from "react";

import { useAuth } from "../../hooks/useAuth";
import { diagnoseVehicle } from "../../services/aiService";
import { getVehicles } from "../../services/vehicleService";

import "./GarageOwnerAIHub.css";


/* =========================================================
   CONNECTED CARD IDENTIFIER
   Stable id - never the array index - so the connected card
   can be matched, refreshed and dismissed independently.
   ========================================================= */

const BATTERY_CARD_ID = "battery-diagnosis";


/* =========================================================
   DEFAULT REPORTED SYMPTOM
   This is REQUEST INPUT (what the garage is reporting), not an
   API response. It is pre-filled on the battery card and stays
   editable before the diagnosis is requested.
   ========================================================= */

const DEFAULT_BATTERY_SYMPTOMS =
  "Vehicle cranks slowly on start and struggles to turn over, especially in the morning.";


/* =========================================================
   ALLOWED ROLES
   Mirrors @PreAuthorize on AiController.diagnosis:
   hasAnyRole('OWNER','MANAGER','SERVICE_ADVISOR','MECHANIC','INVENTORY_MANAGER')
   plus GARAGE_OWNER, the stored spelling of the garage owner that the
   server authorises as ROLE_OWNER.
   Used only to explain a blocked request up front. The backend
   remains the authority - an unknown role is still sent and the
   server's own 403 is surfaced verbatim.
   ========================================================= */

const DIAGNOSIS_ALLOWED_ROLES = [
  "OWNER",
  "MANAGER",
  "SERVICE_ADVISOR",
  "MECHANIC",
  "INVENTORY_MANAGER",
  // Stored spelling of the garage owner where the roles table does not use
  // OWNER. The server maps it to ROLE_OWNER for @PreAuthorize, but the JWT
  // carries this raw name, so it is listed alongside OWNER, not instead of it.
  "GARAGE_OWNER",
];


const aiAlerts = [
  {
    id: BATTERY_CARD_ID,
    icon: "🔍",
    title: "Battery Problem Detected",
    button: "Recommend load test",
  },
  {
    id: "tyre-replacement",
    icon: "🗓️",
    title: "Tyre Replacement Due",
    description:
      "DL-01-CZ-9834 — 42,000 km. ~3,000 km until tyre life exhausted.",
    confidence: "78%",
    button: "Send reminder",
  },
  {
    id: "low-stock",
    icon: "📦",
    title: "Low Stock: Oil Filter",
    description:
      "OIF-3301 stock at 2 units. Stockout in ~2 days.",
    confidence: "92%",
    button: "Raise purchase order",
  },
  {
    id: "mechanic-assignment",
    icon: "🔧",
    title: "Mechanic Assignment",
    description:
      "JC-2408 engine overhaul → Ravi Kumar (8 yrs, 4.9★) recommended.",
    confidence: "96%",
    button: "Accept assignment",
  },
  {
    id: "cost-estimate",
    icon: "💰",
    title: "Cost Estimate: JC-2404",
    description:
      "Suspension + Tyres (Fortuner): Parts ₹24,000 + Labour ₹8,000.",
    confidence: "88%",
    button: "Create estimate",
  },
];


function GarageOwnerAIHub() {
  const [alerts, setAlerts] = useState(aiAlerts);

  const handleDismiss = (id) => {
    setAlerts((currentAlerts) =>
      currentAlerts.filter((item) => item.id !== id)
    );
  };

  const handleAction = (alert) => {
    alertMessage(alert.button);
  };

  return (
    <div className="garage-ai-page">

      {/* =====================================================
          PAGE HEADER
      ===================================================== */}

      <div className="garage-ai-page-header">

        <div className="garage-ai-heading">

          <div className="garage-ai-eyebrow">
            — AI ASSISTANCE LAYER
          </div>

          <h1>SMARTGARAGE AI</h1>

          <p>
            All AI outputs require human confirmation before affecting
            operational decisions.
          </p>

        </div>

      </div>


      {/* =====================================================
          AI ALERTS
      ===================================================== */}

      <div className="garage-ai-grid">

        {alerts.map((item) => {

          if (item.id === BATTERY_CARD_ID) {

            return (
              <BatteryDiagnosisCard
                key={item.id}
                card={item}
                onDismiss={() => handleDismiss(item.id)}
              />
            );

          }

          return (
            <div
              className="garage-ai-card"
              key={item.id}
            >

              {/* CARD TOP */}

              <div className="garage-ai-card-main">

                <div className="garage-ai-icon">
                  {item.icon}
                </div>

                <div className="garage-ai-card-content">

                  <h2>
                    {item.title}
                  </h2>

                  <p>
                    {item.description}
                  </p>

                </div>

                <div className="garage-ai-confidence">

                  <div className="garage-ai-confidence-bar">

                    <span
                      style={{
                        width: item.confidence,
                      }}
                    ></span>

                  </div>

                  <strong>
                    {item.confidence}
                  </strong>

                </div>

              </div>


              {/* CARD ACTIONS */}

              <div className="garage-ai-actions">

                <button
                  type="button"
                  className="garage-ai-primary-button"
                  onClick={() => handleAction(item)}
                >
                  {item.button}
                </button>

                <button
                  type="button"
                  className="garage-ai-dismiss-button"
                  onClick={() => handleDismiss(item.id)}
                >
                  Dismiss
                </button>

              </div>

            </div>
          );

        })}

      </div>

    </div>
  );
}


/* =========================================================
   BATTERY DIAGNOSIS CARD
   Live card backed by POST /api/v1/ai/diagnosis.

   ADVISORY ONLY (SRS BR-09 / FR-AI-04). This card never
   creates or modifies a vehicle, job card, estimate or stock
   record. The backend never does either - it returns a
   recommendation and a disclaimer, and a qualified human
   must confirm before any operational change is made.
   ========================================================= */

function BatteryDiagnosisCard({ card, onDismiss }) {
  const { user } = useAuth();

  const [vehicles, setVehicles] = useState([]);
  const [vehiclesLoading, setVehiclesLoading] = useState(true);
  const [vehiclesError, setVehiclesError] = useState("");

  const [vehicleId, setVehicleId] = useState("");
  const [symptoms, setSymptoms] = useState(DEFAULT_BATTERY_SYMPTOMS);

  const [diagnosis, setDiagnosis] = useState(null);
  const [diagnosisLoading, setDiagnosisLoading] = useState(false);
  const [diagnosisError, setDiagnosisError] = useState("");
  const [advisoryNote, setAdvisoryNote] = useState("");

  const role = typeof user?.role === "string" ? user.role.toUpperCase() : null;
  const roleBlocked =
    role !== null && !DIAGNOSIS_ALLOWED_ROLES.includes(role);

  const loadVehicles = useCallback(async () => {
    setVehiclesLoading(true);
    setVehiclesError("");

    try {
      const rows = await getVehicles();
      setVehicles(Array.isArray(rows) ? rows : []);
    } catch (error) {
      setVehiclesError(
        error instanceof Error
          ? error.message
          : "Unable to load vehicles from the vehicle API."
      );
    } finally {
      setVehiclesLoading(false);
    }

  }, []);

  useEffect(() => {
    loadVehicles();
  }, [loadVehicles]);


  /* -------------------------------------------------------
     RESULT NORMALISATION

     confidence is a 0.00-1.00 decimal. It is converted to a
     percentage only for display. providerUnavailable wins
     over every other field: when it is true the backend
     returned placeholders, so no diagnosis is presented and
     no confidence figure is shown.
     ------------------------------------------------------- */

  const result = useMemo(() => {
    if (!diagnosis) {
      return null;
    }

    const providerUnavailable = diagnosis.providerUnavailable === true;

    const rawConfidence = Number(diagnosis.confidence);
    const hasConfidence =
      !providerUnavailable &&
      Number.isFinite(rawConfidence) &&
      rawConfidence > 0;

    const confidencePercent = hasConfidence
      ? `${Math.min(100, Math.max(0, Math.round(rawConfidence * 100)))}%`
      : "";

    const limitations = Array.isArray(diagnosis.dataLimitations)
      ? diagnosis.dataLimitations.filter(
          (entry) => typeof entry === "string" && entry.trim()
        )
      : typeof diagnosis.dataLimitations === "string" &&
          diagnosis.dataLimitations.trim()
        ? [diagnosis.dataLimitations.trim()]
        : [];

    return {
      providerUnavailable,
      possibleIssue: diagnosis.possibleIssue || "",
      recommendation: diagnosis.recommendation || "",
      confidencePercent,
      hasConfidence,
      disclaimer: diagnosis.disclaimer || "",
      humanReviewRequired: diagnosis.humanReviewRequired !== false,
      generatedAt: diagnosis.generatedAt || "",
      limitations,
    };

  }, [diagnosis]);


  const runDiagnosis = async () => {
    setAdvisoryNote("");
    setDiagnosisError("");

    if (roleBlocked) {
      setDiagnosisError(
        `Your role (${role}) cannot run AI diagnosis. Sign in as an account permitted to request one.`
      );
      return;
    }

    const selectedVehicleId = Number(vehicleId);

    if (!Number.isInteger(selectedVehicleId) || selectedVehicleId <= 0) {
      setDiagnosisError("Select a vehicle before requesting a diagnosis.");
      return;
    }

    const trimmedSymptoms = symptoms.trim();

    if (trimmedSymptoms.length < 5) {
      setDiagnosisError(
        "Describe the reported symptoms in at least 5 characters."
      );
      return;
    }

    setDiagnosisLoading(true);

    try {
      const data = await diagnoseVehicle({
        vehicleId: selectedVehicleId,
        symptoms: trimmedSymptoms,
      });

      setDiagnosis(data);
    } catch (error) {
      setDiagnosis(null);
      setDiagnosisError(
        error instanceof Error
          ? error.message
          : "The AI diagnosis request could not be completed."
      );
    } finally {
      setDiagnosisLoading(false);
    }

  };


  /* The primary action stays advisory. With no result it requests
     the diagnosis; with a result it only surfaces the human
     confirmation notice. Neither path writes to the backend. */

  const handlePrimaryAction = () => {
    if (!result) {
      runDiagnosis();
      return;
    }

    setAdvisoryNote(
      "Advisory recommendation recorded for human review only. " +
        "No vehicle, job card, estimate or stock record was changed. " +
        "A qualified technician must confirm this result before any work is authorised."
    );
  };


  const description = result
    ? result.providerUnavailable
      ? "AI provider unavailable — no diagnosis was produced."
      : result.possibleIssue
    : vehiclesLoading
      ? "Loading vehicles…"
      : "Select a vehicle and the reported symptoms to run an AI diagnosis.";


  return (
    <div className="garage-ai-card">

      {/* CARD TOP */}

      <div className="garage-ai-card-main">

        <div className="garage-ai-icon">
          {card.icon}
        </div>

        <div className="garage-ai-card-content">

          <h2>
            {card.title}
          </h2>

          <p>
            {description}
          </p>

        </div>

        <div className="garage-ai-confidence">

          <div className="garage-ai-confidence-bar">

            <span
              style={{
                width: result?.hasConfidence ? result.confidencePercent : "0%",
              }}
            ></span>

          </div>

          <strong>
            {result?.hasConfidence ? result.confidencePercent : "—"}
          </strong>

        </div>

      </div>


      {/* REQUEST INPUTS */}

      <div className="garage-ai-card-inputs">

        <label className="garage-ai-field">
          <span>Vehicle</span>

          <select
            className="garage-ai-select"
            value={vehicleId}
            onChange={(event) => {
              setVehicleId(event.target.value);
              setDiagnosisError("");
              setDiagnosis(null);
              setAdvisoryNote("");
            }}
            disabled={vehiclesLoading || diagnosisLoading}
          >
            <option value="">
              {vehiclesLoading
                ? "Loading vehicles…"
                : vehicles.length === 0
                  ? "No vehicles available"
                  : "Select a vehicle"}
            </option>

            {vehicles.map((vehicle) => (
              <option key={vehicle.id} value={String(vehicle.id)}>
                {[vehicle.registrationNo, vehicle.make, vehicle.model]
                  .filter(Boolean)
                  .join(" — ") || `Vehicle #${vehicle.id}`}
              </option>
            ))}
          </select>
        </label>

        <label className="garage-ai-field">
          <span>Reported symptoms</span>

          <textarea
            className="garage-ai-textarea"
            value={symptoms}
            rows={2}
            maxLength={2000}
            onChange={(event) => setSymptoms(event.target.value)}
            disabled={diagnosisLoading}
          ></textarea>
        </label>

      </div>


      {/* RESULT DETAIL */}

      {result && !result.providerUnavailable && (
        <div className="garage-ai-result">

          <p className="garage-ai-result-line">
            <strong>Recommended next step:</strong>{" "}
            {result.recommendation}
          </p>

          {result.limitations.length > 0 && (
            <p className="garage-ai-result-limitations">
              Data limitations: {result.limitations.join(" ")}
            </p>
          )}

          <p className="garage-ai-result-disclaimer">
            {result.disclaimer ||
              "This diagnosis is AI-generated and must be reviewed and confirmed by a qualified technician before any repair work is carried out."}
          </p>
        </div>
      )}

      {result?.providerUnavailable && (
        <div className="garage-ai-result">
          <p className="garage-ai-result-unavailable">
            The AI provider is not configured or did not respond, so no
            diagnosis was generated. No result is shown because none was
            produced. Please proceed with a manual inspection.
          </p>
        </div>
      )}


      {/* STATES */}

      {vehiclesError && (
        <p className="garage-ai-message garage-ai-message-error">
          {vehiclesError}
          <button
            type="button"
            className="garage-ai-retry-button"
            onClick={loadVehicles}
            disabled={vehiclesLoading}
          >
            Retry
          </button>
        </p>
      )}

      {!vehiclesLoading &&
        !vehiclesError &&
        vehicles.length === 0 && (
          <p className="garage-ai-message garage-ai-message-empty">
            No vehicles are available to diagnose. Add a vehicle to this
            garage before running an AI diagnosis.
          </p>
        )}

      {diagnosisLoading && (
        <p className="garage-ai-message garage-ai-message-loading">
          Requesting AI diagnosis…
        </p>
      )}

      {diagnosisError && (
        <p className="garage-ai-message garage-ai-message-error">
          {diagnosisError}
        </p>
      )}

      {advisoryNote && (
        <p className="garage-ai-message garage-ai-message-advisory">
          {advisoryNote}
        </p>
      )}


      {/* CARD ACTIONS */}

      <div className="garage-ai-actions">

        <button
          type="button"
          className="garage-ai-primary-button"
          onClick={handlePrimaryAction}
          disabled={
            diagnosisLoading ||
            vehiclesLoading ||
            vehicles.length === 0 ||
            roleBlocked
          }
        >
          {card.button}
        </button>

        <button
          type="button"
          className="garage-ai-dismiss-button"
          onClick={onDismiss}
        >
          Dismiss
        </button>

      </div>

    </div>
  );
}


/* =========================================================
   ACTION
   ========================================================= */

function alertMessage(message) {
  window.alert(`${message} will be connected later.`);
}

export default GarageOwnerAIHub;
