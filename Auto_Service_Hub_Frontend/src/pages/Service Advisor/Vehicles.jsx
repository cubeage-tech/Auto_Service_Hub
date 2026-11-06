import React, { useEffect, useMemo, useState } from "react";

import {
  createVehicle,
  getCustomers,
  getVehicle,
  getVehicles,
} from "../../services/vehicleService";

import "./ServiceAdvisor.css";


/* =========================================================
   ALLOWED VALUES
   Taken from the @Pattern constraints on VehicleRequestDTO, so the
   dropdowns can only ever produce values the backend accepts.
   ========================================================= */

const FUEL_TYPES = ["PETROL", "DIESEL", "CNG", "ELECTRIC", "HYBRID", "OTHER"];

/* Make / Brand options shown in the form.
   VehicleRequestDTO.make is an unconstrained String, so every one of these
   is a value the backend will accept and store verbatim. */
const VEHICLE_MAKES = [
  "Maruti Suzuki",
  "Hyundai",
  "Honda",
  "Toyota",
  "Tata Motors",
  "Mahindra",
  "Kia",
  "Volkswagen",
  "Other",
];

const VEHICLE_STATUSES = ["IN_SERVICE", "WAITING", "DELIVERED"];

/* `notes` is submitted to the backend as VehicleRequestDTO.notes. Blank or
   whitespace-only input is omitted rather than sent as an empty string. */
const EMPTY_FORM = {
  registrationNo: "",
  make: "",
  model: "",
  year: "",
  fuelType: "",
  customerId: "",
  mileage: "",
  engineNo: "",
  chassisNo: "",
  insuranceExpiry: "",
  warrantyExpiry: "",
  notes: "",
};

/* VehicleResponseDTO is the only shape the list endpoint returns. It has no
   last-service date, so that column renders a dash rather than a guess. */
const NOT_PROVIDED = "—";

const addDays = (date, amount) => {
  const result = new Date(date);
  result.setDate(result.getDate() + amount);
  return result;
};

const formatDate = (value) => {
  if (!value) return null;
  const date = new Date(value);
  if (Number.isNaN(date.getTime())) return null;
  return date.toLocaleDateString("en-GB", {
    day: "2-digit",
    month: "short",
    year: "numeric",
  });
};

const formatMileage = (value) => {
  if (value === null || value === undefined) return NOT_PROVIDED;
  const number = Number(value);
  if (!Number.isFinite(number)) return NOT_PROVIDED;
  return `${new Intl.NumberFormat("en-IN").format(number)} km`;
};

const statusLabel = (status) =>
  (status || "Unknown")
    .toLowerCase()
    .split(/[_\s]+/)
    .map((word) => word.charAt(0).toUpperCase() + word.slice(1))
    .join(" ");

const statusClass = (status) => {
  const normalized = (status || "").toUpperCase();
  if (normalized.includes("SERVICE")) return "status-repair";
  if (normalized.includes("DELIVER")) return "status-delivered";
  if (normalized.includes("WAIT")) return "status-inspection";
  return "";
};

/* Optional detail fields render as a dash rather than a blank cell. */
const detailText = (value) => {
  if (value === null || value === undefined) return NOT_PROVIDED;
  const text = String(value).trim();
  return text === "" ? NOT_PROVIDED : text;
};

const formatDateTime = (value) => {
  if (!value) return null;
  const date = new Date(value);
  if (Number.isNaN(date.getTime())) return null;
  return `${date.toLocaleDateString("en-GB", {
    day: "2-digit",
    month: "short",
    year: "numeric",
  })}, ${date.toLocaleTimeString("en-GB", {
    hour: "2-digit",
    minute: "2-digit",
  })}`;
};


export default function Vehicles() {
  const [list, setList] = useState({ status: "loading", data: [], error: "" });
  const [refreshKey, setRefreshKey] = useState(0);

  const [q, setQ] = useState("");
  const [fuelFilter, setFuelFilter] = useState("");
  const [statusFilter, setStatusFilter] = useState("");

  const [showForm, setShowForm] = useState(false);
  const [formData, setFormData] = useState(EMPTY_FORM);
  const [isCreating, setIsCreating] = useState(false);
  const [createError, setCreateError] = useState("");
  const [successMessage, setSuccessMessage] = useState("");

  const [customers, setCustomers] = useState({ status: "idle", data: [], error: "" });

  /* View modal. `summary` is the row already in hand, shown until the
     authoritative record arrives so the dialog is never empty. */
  const [details, setDetails] = useState({
    status: "idle",
    id: null,
    summary: null,
    data: null,
    error: "",
    attempt: 0,
  });

  /* Fuel type and status are pushed to the backend, which supports both as
     list parameters. Only non-empty filters are sent. */
  const filters = useMemo(() => {
    const value = {};
    if (fuelFilter) value.fuelType = fuelFilter;
    if (statusFilter) value.status = statusFilter;
    return value;
  }, [fuelFilter, statusFilter]);

  useEffect(() => {
    let cancelled = false;

    const load = async () => {
      try {
        const vehicles = await getVehicles(filters);
        if (!cancelled) {
          setList({
            status: "success",
            data: Array.isArray(vehicles) ? vehicles : [],
            error: "",
          });
        }
      } catch (error) {
        if (!cancelled) {
          setList({
            status: "error",
            data: [],
            error:
              error instanceof Error
                ? error.message
                : "Unable to load vehicles.",
          });
        }
      }
    };

    load();

    return () => {
      cancelled = true;
    };
  }, [filters, refreshKey]);

  const handleRetry = () => {
    setList({ status: "loading", data: [], error: "" });
    setRefreshKey((value) => value + 1);
  };

  /* Customers are only needed for the owner dropdown, so they are fetched
     when the form opens rather than on every page load. */
  useEffect(() => {
    if (!showForm || customers.status !== "idle") return;
    let cancelled = false;

    const load = async () => {
      try {
        const records = await getCustomers();
        if (!cancelled) {
          setCustomers({
            status: "success",
            data: Array.isArray(records) ? records : [],
            error: "",
          });
        }
      } catch (error) {
        if (!cancelled) {
          setCustomers({
            status: "error",
            data: [],
            error:
              error instanceof Error
                ? error.message
                : "Unable to load customers.",
          });
        }
      }
    };

    load();

    return () => {
      cancelled = true;
    };
  }, [showForm, customers.status]);

  /* ── VIEW VEHICLE ──
     The list already carries the full VehicleResponseDTO, so the row is shown
     immediately and refreshed from GET /vehicles/{id}. A failed refresh keeps
     the row data on screen behind an inline, retryable error instead of
     blanking the dialog. */
  const openDetails = (vehicle) => {
    if (!vehicle || vehicle.id === null || vehicle.id === undefined) return;
    setDetails({
      status: "loading",
      id: vehicle.id,
      summary: vehicle,
      data: vehicle,
      error: "",
      attempt: 0,
    });
  };

  const closeDetails = () => {
    setDetails({
      status: "idle",
      id: null,
      summary: null,
      data: null,
      error: "",
      attempt: 0,
    });
  };

  const retryDetails = () => {
    if (details.id === null || details.id === undefined) return;
    setDetails((current) => ({ ...current, status: "loading", error: "", attempt: current.attempt + 1 }));
  };

  useEffect(() => {
    if (details.status !== "loading" || details.id === null || details.id === undefined) {
      return;
    }

    let cancelled = false;

    const load = async () => {
      try {
        const record = await getVehicle(details.id);
        if (!cancelled) {
          setDetails((current) =>
            current.id === details.id
              ? {
                  ...current,
                  status: "success",
                  data: { ...current.summary, ...(record || {}) },
                  error: "",
                }
              : current
          );
        }
      } catch (error) {
        if (!cancelled) {
          setDetails((current) =>
            current.id === details.id
              ? {
                  ...current,
                  status: "error",
                  error:
                    error instanceof Error
                      ? error.message
                      : "Unable to load vehicle details.",
                }
              : current
          );
        }
      }
    };

    load();

    return () => {
      cancelled = true;
    };
  }, [details.status, details.id, details.attempt]);

  /* Fields come from VehicleResponseDTO only — nothing here is guessed. */
  const detailVehicle = details.data;

  /* Search runs across the real record fields only. */
  const vehicles = useMemo(() => {
    const needle = q.trim().toLowerCase();
    if (!needle) return list.data;
    return list.data.filter((vehicle) =>
      [
        vehicle.registrationNo,
        vehicle.make,
        vehicle.model,
        vehicle.variant,
        vehicle.year,
        vehicle.customerName,
        vehicle.status,
      ]
        .map((value) => (value === null || value === undefined ? "" : String(value)))
        .join(" ")
        .toLowerCase()
        .includes(needle)
    );
  }, [list.data, q]);

  /* Stats are derived from the loaded records only. */
  const stats = useMemo(() => {
    const today = new Date();
    const horizon = addDays(today, 60);
    let inService = 0;
    let expiringSoon = 0;
    let mileageTotal = 0;
    let mileageCount = 0;

    list.data.forEach((vehicle) => {
      if (String(vehicle.status || "").toUpperCase() === "IN_SERVICE") {
        inService += 1;
      }
      if (vehicle.insuranceExpiry) {
        const expiry = new Date(vehicle.insuranceExpiry);
        if (
          !Number.isNaN(expiry.getTime()) &&
          expiry >= today &&
          expiry <= horizon
        ) {
          expiringSoon += 1;
        }
      }
      if (vehicle.mileage !== null && vehicle.mileage !== undefined) {
        const number = Number(vehicle.mileage);
        if (Number.isFinite(number)) {
          mileageTotal += number;
          mileageCount += 1;
        }
      }
    });

    return {
      total: list.data.length,
      inService,
      expiringSoon,
      avgMileage:
        mileageCount > 0 ? Math.round(mileageTotal / mileageCount) : null,
    };
  }, [list.data]);

  /* Vehicles whose cover lapses within 60 days are flagged in amber. */
  const isExpiringSoon = (value) => {
    if (!value) return false;
    const expiry = new Date(value);
    const today = new Date();
    return (
      !Number.isNaN(expiry.getTime()) &&
      expiry >= today &&
      expiry <= addDays(today, 60)
    );
  };

  const updateField = (field) => (event) =>
    setFormData((current) => ({ ...current, [field]: event.target.value }));

  const openForm = () => {
    setFormData(EMPTY_FORM);
    setCreateError("");
    setSuccessMessage("");
    setShowForm(true);
  };

  const closeForm = () => {
    if (!isCreating) {
      setShowForm(false);
      setCreateError("");
    }
  };

  /* Mirrors the DTO constraints so an avoidable round trip is not made.
     The backend remains the authority — its error is what the user sees. */
  const validate = () => {
    if (!formData.registrationNo.trim()) return "Registration number is required.";

    if (formData.year.trim()) {
      const year = Number(formData.year);
      if (!Number.isInteger(year) || year < 1900 || year > 2100) {
        return "Enter a valid four-digit year.";
      }
    }

    if (formData.mileage.trim()) {
      const mileage = Number(formData.mileage);
      if (!Number.isInteger(mileage) || mileage < 0) {
        return "Enter mileage as a whole number of kilometres.";
      }
    }

    if (formData.fuelType && !FUEL_TYPES.includes(formData.fuelType)) {
      return "Fuel type must be PETROL, DIESEL, CNG, ELECTRIC, HYBRID, or OTHER.";
    }

    if (formData.customerId && !Number.isInteger(Number(formData.customerId))) {
      return "Select a valid customer.";
    }

    return "";
  };

  const handleRegisterVehicle = async (e) => {
    e.preventDefault();

    if (isCreating) return;

    const validationError = validate();
    if (validationError) {
      setCreateError(validationError);
      return;
    }

    setIsCreating(true);
    setCreateError("");

    /* Only fields declared by VehicleRequestDTO are sent. Blank optional
       fields are omitted rather than sent as "", because Integer and
       LocalDate properties cannot be deserialised from an empty string. */
    const payload = { registrationNo: formData.registrationNo.trim() };

    const textFields = ["make", "model", "engineNo", "chassisNo", "notes"];
    for (const field of textFields) {
      const value = formData[field].trim();
      if (value) payload[field] = value;
    }
    if (formData.year.trim()) payload.year = Number(formData.year);
    if (formData.mileage.trim()) payload.mileage = Number(formData.mileage);
    if (formData.fuelType) payload.fuelType = formData.fuelType;
    if (formData.insuranceExpiry) payload.insuranceExpiry = formData.insuranceExpiry;
    if (formData.warrantyExpiry) payload.warrantyExpiry = formData.warrantyExpiry;
    if (formData.customerId) payload.customerId = Number(formData.customerId);

    const registeredName = formData.registrationNo.trim().toUpperCase();

    try {
      await createVehicle(payload);

      /* Re-read the list rather than inserting by hand, so the vehicle
         appears exactly once and only with backend data. */
      setQ("");
      setSuccessMessage(`${registeredName} registered successfully.`);
      setShowForm(false);
      setFormData(EMPTY_FORM);
      setRefreshKey((value) => value + 1);

    } catch (error) {
      setCreateError(
        error instanceof Error
          ? error.message
          : "Unable to register the vehicle."
      );
    } finally {
      setIsCreating(false);
    }
  };


  return (
    <div className="sa-page">

      <div className="sa-page-head">
        <div>
          <h1 className="sa-title">VEHICLES</h1>
        </div>
        <button
          type="button"
          className="sa-primary"
          onClick={openForm}
          disabled={isCreating}
        >
          + REGISTER VEHICLE
        </button>
      </div>


      {successMessage && (
        <div className="sa-success" role="status">
          {successMessage}
        </div>
      )}


      <div className="sa-stats">
        <div className="sa-stat">
          <div className="sa-stat-label">Total Vehicles</div>
          <div className="sa-stat-value">
            {list.status === "success" ? stats.total : list.status === "loading" ? "…" : NOT_PROVIDED}
          </div>
        </div>
        <div className="sa-stat">
          <div className="sa-stat-label">In Service</div>
          <div className="sa-stat-value" style={{ color: "var(--sa-lime)" }}>
            {list.status === "success" ? stats.inService : list.status === "loading" ? "…" : NOT_PROVIDED}
          </div>
        </div>
        <div className="sa-stat">
          <div className="sa-stat-label">Insurance Expiring</div>
          <div className="sa-stat-value">
            {list.status === "success" ? stats.expiringSoon : list.status === "loading" ? "…" : NOT_PROVIDED}
          </div>
          <div className="sa-stat-note">within 60 days</div>
        </div>
        <div className="sa-stat">
          <div className="sa-stat-label">Avg Mileage</div>
          <div className="sa-stat-value">
            {list.status !== "success"
              ? list.status === "loading"
                ? "…"
                : NOT_PROVIDED
              : stats.avgMileage === null
                ? NOT_PROVIDED
                : `${new Intl.NumberFormat("en-IN").format(stats.avgMileage)} km`}
          </div>
          <div className="sa-stat-note">across recorded odometer readings</div>
        </div>
      </div>


      <div className="sa-toolbar">
        <input
          className="sa-search"
          placeholder="Search by reg, owner, model..."
          value={q}
          onChange={(e) => setQ(e.target.value)}
        />

        <select
          className="sa-filter"
          value={fuelFilter}
          onChange={(e) => setFuelFilter(e.target.value)}
        >
          <option value="">All Fuel Types</option>
          {FUEL_TYPES.map((fuel) => (
            <option key={fuel} value={fuel}>
              {fuel}
            </option>
          ))}
        </select>

        <select
          className="sa-filter"
          value={statusFilter}
          onChange={(e) => setStatusFilter(e.target.value)}
        >
          <option value="">All Statuses</option>
          {VEHICLE_STATUSES.map((status) => (
            <option key={status} value={status}>
              {statusLabel(status)}
            </option>
          ))}
        </select>
      </div>


      <div className="sa-wide-table">
        <table className="sa-table">
          <thead>
            <tr>
              <th>Reg. No.</th>
              <th>Make &amp; Model</th>
              <th>Year</th>
              <th>Owner</th>
              <th>Mileage</th>
              <th>Insurance Expiry</th>
              <th>Last Service</th>
              <th>Status</th>
              <th></th>
            </tr>
          </thead>
          <tbody>
            {list.status === "loading" ? (
              <tr>
                <td colSpan={9}>Loading vehicles…</td>
              </tr>
            ) : list.status === "error" ? (
              <tr>
                <td colSpan={9}>
                  <div className="sa-list-error" role="alert">
                    <span>{list.error}</span>
                    <button type="button" className="sa-retry" onClick={handleRetry}>
                      Retry
                    </button>
                  </div>
                </td>
              </tr>
            ) : vehicles.length === 0 ? (
              <tr>
                <td colSpan={9}>
                  {list.data.length === 0
                    ? "No vehicles have been registered yet."
                    : "No vehicles match your search or filters."}
                </td>
              </tr>
            ) : (
              vehicles.map((vehicle) => (
                <tr key={vehicle.id}>
                  <td className="sa-id">{vehicle.registrationNo || NOT_PROVIDED}</td>
                  <td>
                    <strong>{vehicle.model || NOT_PROVIDED}</strong>
                    <br />
                    <small>{vehicle.make || NOT_PROVIDED}</small>
                  </td>
                  <td>{vehicle.year ?? NOT_PROVIDED}</td>
                  <td><strong>{vehicle.customerName || NOT_PROVIDED}</strong></td>
                  <td><strong>{formatMileage(vehicle.mileage)}</strong></td>
                  <td style={{ color: isExpiringSoon(vehicle.insuranceExpiry) ? "#eaa735" : undefined }}>
                    {formatDate(vehicle.insuranceExpiry) || NOT_PROVIDED}
                  </td>
                  <td>{NOT_PROVIDED}</td>
                  <td>
                    <span className={`sa-status ${statusClass(vehicle.status)}`}>
                      {vehicle.status ? statusLabel(vehicle.status) : NOT_PROVIDED}
                    </span>
                  </td>
                  <td>
                    <button
                      type="button"
                      className="sa-details"
                      onClick={() => openDetails(vehicle)}
                      title="View vehicle details"
                    >
                      View
                    </button>
                  </td>
                </tr>
              ))
            )}
          </tbody>
        </table>
      </div>


      {/* ── REGISTER VEHICLE MODAL ── */}

      {showForm && (
        <div
          className="sa-modal-overlay"
          role="presentation"
          onClick={(e) => {
            /* Only a click on the overlay itself closes; clicks inside the
               dialog are ignored so the form is never dismissed by accident. */
            if (e.target === e.currentTarget) closeForm();
          }}
        >
          <div
            className="sa-modal sa-modal--scroll"
            role="dialog"
            aria-modal="true"
            aria-labelledby="sa-register-vehicle-title"
          >
            <div className="sa-modal-header">
              <h2 className="sa-modal-title" id="sa-register-vehicle-title">
                REGISTER VEHICLE
              </h2>
              <button
                type="button"
                className="sa-modal-close"
                onClick={closeForm}
                disabled={isCreating}
                aria-label="Close"
              >
                ×
              </button>
            </div>

            <form
              className="sa-modal-form"
              onSubmit={handleRegisterVehicle}
              noValidate
            >

              <label className="sa-modal-field">
                <span>Registration Number *</span>
                <input
                  className="sa-modal-input"
                  value={formData.registrationNo}
                  onChange={updateField("registrationNo")}
                  disabled={isCreating}
                  maxLength={255}
                  autoComplete="off"
                />
              </label>

              <div className="sa-modal-row">
                <label className="sa-modal-field">
                  <span>Make / Brand</span>
                  <select
                    className="sa-modal-input"
                    value={formData.make}
                    onChange={updateField("make")}
                    disabled={isCreating}
                  >
                    <option value="">Select make</option>
                    {VEHICLE_MAKES.map((make) => (
                      <option key={make} value={make}>
                        {make}
                      </option>
                    ))}
                  </select>
                </label>

                <label className="sa-modal-field">
                  <span>Model</span>
                  <input
                    className="sa-modal-input"
                    value={formData.model}
                    onChange={updateField("model")}
                    disabled={isCreating}
                    maxLength={255}
                  />
                </label>
              </div>

              <div className="sa-modal-row">
                <label className="sa-modal-field">
                  <span>Year</span>
                  <input
                    className="sa-modal-input"
                    value={formData.year}
                    onChange={updateField("year")}
                    disabled={isCreating}
                    inputMode="numeric"
                    maxLength={4}
                  />
                </label>

                <label className="sa-modal-field">
                  <span>Fuel Type</span>
                  <select
                    className="sa-modal-input"
                    value={formData.fuelType}
                    onChange={updateField("fuelType")}
                    disabled={isCreating}
                  >
                    <option value="">Not specified</option>
                    {FUEL_TYPES.map((fuel) => (
                      <option key={fuel} value={fuel}>
                        {fuel}
                      </option>
                    ))}
                  </select>
                </label>
              </div>

              <label className="sa-modal-field">
                <span>Customer / Owner</span>
                <select
                  className="sa-modal-input"
                  value={formData.customerId}
                  onChange={updateField("customerId")}
                  disabled={isCreating || customers.status === "loading"}
                >
                  <option value="">Unassigned</option>
                  {customers.status === "loading" && (
                    <option value="">Loading customers…</option>
                  )}
                  {customers.data.map((customer) => (
                    <option key={customer.id} value={String(customer.id)}>
                      {customer.name}
                    </option>
                  ))}
                </select>
              </label>

              <div className="sa-modal-row">
                <label className="sa-modal-field">
                  <span>Current Mileage (KM)</span>
                  <input
                    className="sa-modal-input"
                    value={formData.mileage}
                    onChange={updateField("mileage")}
                    disabled={isCreating}
                    inputMode="numeric"
                  />
                </label>

                <div className="sa-modal-field">
                  <span>Engine / Chassis No.</span>
                  <div className="sa-modal-pair">
                    <input
                      className="sa-modal-input"
                      value={formData.engineNo}
                      onChange={updateField("engineNo")}
                      disabled={isCreating}
                      maxLength={255}
                      placeholder="Engine"
                      aria-label="Engine number"
                    />
                    <input
                      className="sa-modal-input"
                      value={formData.chassisNo}
                      onChange={updateField("chassisNo")}
                      disabled={isCreating}
                      maxLength={255}
                      placeholder="Chassis"
                      aria-label="Chassis number"
                    />
                  </div>
                </div>
              </div>

              <label className="sa-modal-field">
                <span>Insurance Expiry</span>
                <input
                  className="sa-modal-input"
                  type="date"
                  value={formData.insuranceExpiry}
                  onChange={updateField("insuranceExpiry")}
                  disabled={isCreating}
                />
              </label>

              <label className="sa-modal-field">
                <span>Warranty Expiry</span>
                <input
                  className="sa-modal-input"
                  type="date"
                  value={formData.warrantyExpiry}
                  onChange={updateField("warrantyExpiry")}
                  disabled={isCreating}
                />
              </label>

              <label className="sa-modal-field">
                <span>Notes</span>
                <textarea
                  className="sa-modal-input sa-modal-textarea"
                  value={formData.notes}
                  onChange={updateField("notes")}
                  disabled={isCreating}
                  maxLength={2000}
                  rows={3}
                  placeholder="Any remarks about this vehicle…"
                />
              </label>

              {customers.status === "error" && (
                <p className="sa-modal-note">
                  Customer list unavailable — you can still register the
                  vehicle without an owner. {customers.error}
                </p>
              )}

              {createError && (
                <p className="sa-modal-error" role="alert">
                  {createError}
                </p>
              )}

              <div className="sa-modal-footer">
                <button
                  type="button"
                  className="sa-modal-cancel"
                  onClick={closeForm}
                  disabled={isCreating}
                >
                  Cancel
                </button>
                <button
                  type="submit"
                  className="sa-modal-submit"
                  disabled={isCreating}
                >
                  {isCreating ? "REGISTERING…" : "REGISTER VEHICLE"}
                </button>
              </div>

            </form>
          </div>
        </div>
      )}


      {/* ── VEHICLE DETAILS MODAL ── */}

      {details.status !== "idle" && (
        <div
          className="sa-modal-overlay"
          role="presentation"
          onClick={(e) => {
            /* Only a click on the overlay itself closes; clicks inside the
               dialog are ignored so the dialog is never dismissed by accident. */
            if (e.target === e.currentTarget) closeDetails();
          }}
        >
          <div
            className="sa-modal sa-modal--details"
            role="dialog"
            aria-modal="true"
            aria-labelledby="sa-vehicle-details-title"
          >
            <div className="sa-modal-header">
              <h2 className="sa-modal-title" id="sa-vehicle-details-title">
                VEHICLE DETAILS
              </h2>
              <button
                type="button"
                className="sa-modal-close"
                onClick={closeDetails}
                aria-label="Close"
              >
                ×
              </button>
            </div>

            <div className="sa-modal-body">
              {details.status === "loading" && (
                <p className="sa-detail-status" role="status">
                  Loading vehicle details…
                </p>
              )}

              {details.status === "error" && (
                <div className="sa-list-error" role="alert">
                  <span>{details.error}</span>
                  <button type="button" className="sa-retry" onClick={retryDetails}>
                    Retry
                  </button>
                </div>
              )}

              {detailVehicle && (
                <div className="sa-detail-grid">

                  <div className="sa-detail-field sa-detail-wide">
                    <span>Registration Number</span>
                    <strong className="sa-detail-value sa-detail-reg">
                      {detailText(detailVehicle.registrationNo)}
                    </strong>
                  </div>

                  <div className="sa-detail-field">
                    <span>Make / Brand</span>
                    <strong className="sa-detail-value">
                      {detailText(detailVehicle.make)}
                    </strong>
                  </div>

                  <div className="sa-detail-field">
                    <span>Model</span>
                    <strong className="sa-detail-value">
                      {detailText(detailVehicle.model)}
                    </strong>
                  </div>

                  <div className="sa-detail-field">
                    <span>Variant</span>
                    <strong className="sa-detail-value">
                      {detailText(detailVehicle.variant)}
                    </strong>
                  </div>

                  <div className="sa-detail-field">
                    <span>Year</span>
                    <strong className="sa-detail-value">
                      {detailText(detailVehicle.year)}
                    </strong>
                  </div>

                  <div className="sa-detail-field">
                    <span>Fuel Type</span>
                    <strong className="sa-detail-value">
                      {detailVehicle.fuelType
                        ? statusLabel(detailVehicle.fuelType)
                        : NOT_PROVIDED}
                    </strong>
                  </div>

                  <div className="sa-detail-field">
                    <span>Status</span>
                    <strong className="sa-detail-value">
                      {detailVehicle.status ? (
                        <span className={`sa-status ${statusClass(detailVehicle.status)}`}>
                          {statusLabel(detailVehicle.status)}
                        </span>
                      ) : (
                        NOT_PROVIDED
                      )}
                    </strong>
                  </div>

                  <div className="sa-detail-field">
                    <span>Customer / Owner</span>
                    <strong className="sa-detail-value">
                      {detailText(detailVehicle.customerName)}
                    </strong>
                  </div>

                  <div className="sa-detail-field">
                    <span>Mileage</span>
                    <strong className="sa-detail-value">
                      {formatMileage(detailVehicle.mileage)}
                    </strong>
                  </div>

                  <div className="sa-detail-field">
                    <span>Engine Number</span>
                    <strong className="sa-detail-value">
                      {detailText(detailVehicle.engineNo)}
                    </strong>
                  </div>

                  <div className="sa-detail-field">
                    <span>Chassis Number</span>
                    <strong className="sa-detail-value">
                      {detailText(detailVehicle.chassisNo)}
                    </strong>
                  </div>

                  <div className="sa-detail-field">
                    <span>Insurance Expiry</span>
                    <strong
                      className="sa-detail-value"
                      style={{
                        color: isExpiringSoon(detailVehicle.insuranceExpiry)
                          ? "#eaa735"
                          : undefined,
                      }}
                    >
                      {formatDate(detailVehicle.insuranceExpiry) || NOT_PROVIDED}
                    </strong>
                  </div>

                  <div className="sa-detail-field">
                    <span>Warranty Expiry</span>
                    <strong className="sa-detail-value">
                      {formatDate(detailVehicle.warrantyExpiry) || NOT_PROVIDED}
                    </strong>
                  </div>

                  <div className="sa-detail-field sa-detail-wide">
                    <span>Last Updated</span>
                    <strong className="sa-detail-value">
                      {formatDateTime(detailVehicle.updatedAt) || NOT_PROVIDED}
                    </strong>
                  </div>

                  <div className="sa-detail-field sa-detail-wide">
                    <span>Notes</span>
                    <strong className="sa-detail-value sa-detail-notes">
                      {detailText(detailVehicle.notes)}
                    </strong>
                  </div>

                </div>
              )}
            </div>

            <div className="sa-modal-footer sa-modal-footer--plain">
              <button type="button" className="sa-modal-cancel" onClick={closeDetails}>
                Close
              </button>
            </div>
          </div>
        </div>
      )}

    </div>
  );
}
