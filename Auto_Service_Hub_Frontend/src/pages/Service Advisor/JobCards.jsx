import React, { useEffect, useMemo, useState } from "react";

import {
  createJobCard,
  getJobCards,
  getJobCardAppointments,
} from "../../services/jobCardService";
import { getCustomers, getVehicles } from "../../services/vehicleService";
import { getMechanics } from "../../services/mechanicService";

import "./ServiceAdvisor.css";


/* =========================================================
   BACKEND CONTRACT
   POST /api/v1/job-cards -> JobCardRequestDTO (@Valid):
     customerId        @NotNull Long
     vehicleId         @NotNull Long
     serviceType       @NotBlank String
     complaint            String
     technicianNotes      String
     odometerReading      Integer
     mechanicId           Long
     appointmentId        Long
     inspectionId         Long
     estimatedDelivery    LocalDateTime
     estimatedCost        BigDecimal
     status               String
   Every field the modal collects maps onto that DTO and is sent.
   `status` is deliberately omitted: JobCardServiceImpl.create hardcodes
   "RECEIVED" and overwrites anything sent, so sending it would be
   misleading rather than useful.
   ========================================================= */

/* serviceType is a free-text @NotBlank String with no backend enum, so the
   modal offers the same SmartGarage service list the Appointments page uses
   rather than inventing a second vocabulary. */
const SERVICE_TYPES = [
  "Basic Service",
  "Full Service",
  "Premium Service",
  "Engine Repair",
  "AC Service",
  "Brake Replacement",
  "Tyre Change",
  "Battery Replacement",
  "Denting & Painting",
  "Other",
];

const DEFAULT_SERVICE_TYPE = SERVICE_TYPES[0];

const STEPS = ["Received", "Inspection", "In Repair", "QC", "Delivered"];

const ALL_DAYS = "ALL";

/* Sentinel option values. Neither is a real id, so they are never sent: the
   appointment is simply omitted, and the mechanic is left unassigned. */
const NO_APPOINTMENT = "";
const AUTO_ASSIGN = "";

/* The tab strip is unchanged from the original layout; each entry now also
   carries the exact status string the backend stores. */
const STATUS_FILTERS = [
  { key: ALL_DAYS, label: "ALL", match: null },
  { key: "RECEIVED", label: "RECEIVED", match: "RECEIVED" },
  { key: "INSPECTION", label: "INSPECTION", match: "INSPECTION" },
  { key: "REPAIR", label: "REPAIR", match: "IN_REPAIR" },
  { key: "QC", label: "QC", match: "QUALITY_CHECK" },
  { key: "DELIVERED", label: "DELIVERED", match: "DELIVERED" },
];

const STATUS_CLASS = {
  RECEIVED: "status-received",
  INSPECTION: "status-inspection",
  IN_REPAIR: "status-repair",
  "IN REPAIR": "status-repair",
  QUALITY_CHECK: "status-qc",
  "QUALITY CHECK": "status-qc",
  QC: "status-qc",
  DELIVERED: "status-delivered",
};

const NOT_PROVIDED = "—";

const CREATE_WITHOUT_APPOINTMENT = "Create without appointment";
const AUTO_ASSIGN_LABEL = "Auto-assign via AI";

const EMPTY_FORM = {
  appointmentId: NO_APPOINTMENT,
  customerId: "",
  vehicleId: "",
  odometerReading: "",
  serviceType: DEFAULT_SERVICE_TYPE,
  mechanicId: AUTO_ASSIGN,
  complaint: "",
  estimatedDelivery: "",
  estimatedCost: "0",
  technicianNotes: "",
};


/* The API returns Long ids as JSON numbers while the selects hold strings, so
   both sides are normalised before comparison. An absent id on either side is
   a real mismatch, never a loose "undefined === undefined" match. */
const sameId = (left, right) => {
  if (left === null || left === undefined) return false;
  if (right === null || right === undefined) return false;
  const a = String(left).trim();
  const b = String(right).trim();
  return a !== "" && b !== "" && a === b;
};

const normalizeStatus = (status) => String(status || "").trim().toUpperCase();

const statusClass = (status) => STATUS_CLASS[normalizeStatus(status)] || "";

const statusLabel = (status) =>
  normalizeStatus(status)
    .toLowerCase()
    .split(/[_\s]+/)
    .map((word) => word.charAt(0).toUpperCase() + word.slice(1))
    .join(" ") || NOT_PROVIDED;

const formatDateTime = (value) => {
  if (!value) return null;
  const date = new Date(value);
  if (Number.isNaN(date.getTime())) return null;
  return date.toLocaleString("en-GB", {
    day: "2-digit",
    month: "short",
    hour: "2-digit",
    minute: "2-digit",
    hour12: true,
  });
};

const formatCost = (value) => {
  if (value === null || value === undefined || value === "") return null;
  const amount = Number(value);
  if (!Number.isFinite(amount)) return null;
  return `₹${amount.toLocaleString("en-IN")}`;
};

/* Enough to tell two appointments apart in the picker. */
const appointmentLabel = (appointment) =>
  [
    formatDateTime(appointment.appointmentAt),
    appointment.vehicleInfo,
    appointment.serviceType,
  ]
    .filter(Boolean)
    .join(" — ") || `Appointment ${appointment.id}`;


export default function JobCards() {
  /* List state. `refreshKey` drives a re-read so a created job card shows up
     from backend data rather than being spliced in by hand. `loadedAt` is the
     moment the current records were fetched, so "overdue" is measured against
     a fixed instant instead of a clock read during render. */
  const [list, setList] = useState({
    status: "loading",
    data: [],
    error: "",
    loadedAt: 0,
  });
  const [refreshKey, setRefreshKey] = useState(0);
  const [statusFilter, setStatusFilter] = useState(ALL_DAYS);

  /* Modal state. */
  const [showForm, setShowForm] = useState(false);
  const [formData, setFormData] = useState(EMPTY_FORM);
  const [isSaving, setIsSaving] = useState(false);
  const [createError, setCreateError] = useState("");
  const [successMessage, setSuccessMessage] = useState("");

  /* Customer / vehicle / mechanic / appointment lookups, needed only by the
     modal. Each tracks its own status so one failing request cannot stall the
     others. */
  const [options, setOptions] = useState({
    customersStatus: "idle",
    customers: [],
    customersError: "",
    vehiclesStatus: "idle",
    vehicles: [],
    vehiclesError: "",
    mechanicsStatus: "idle",
    mechanics: [],
    mechanicsError: "",
    appointmentsStatus: "idle",
    appointments: [],
    appointmentsError: "",
  });

  /* Re-fetching is driven by this key, never by a status field. Depending on
     the status would cancel the in-flight request the instant it flips to
     "loading", because the cleanup would run before the response arrived. */
  const [optionsRefreshKey, setOptionsRefreshKey] = useState(0);


  useEffect(() => {
    let cancelled = false;

    const load = async () => {
      try {
        const records = await getJobCards();
        if (!cancelled) {
          setList({
            status: "success",
            data: Array.isArray(records) ? records : [],
            error: "",
            loadedAt: Date.now(),
          });
        }
      } catch (error) {
        if (!cancelled) {
          setList({
            status: "error",
            data: [],
            error:
              error instanceof Error ? error.message : "Unable to load job cards.",
            loadedAt: 0,
          });
        }
      }
    };

    load();

    return () => {
      cancelled = true;
    };
  }, [refreshKey]);

  const handleRetry = () => {
    setList({ status: "loading", data: [], error: "", loadedAt: 0 });
    setRefreshKey((value) => value + 1);
  };

  useEffect(() => {
    if (!showForm) return;
    let cancelled = false;

    const loadList = async (load, keys, fallback) => {
      setOptions((current) => ({
        ...current,
        [keys.status]: "loading",
        [keys.error]: "",
      }));

      try {
        const records = await load();
        if (cancelled) return;
        setOptions((current) => ({
          ...current,
          [keys.status]: "success",
          [keys.items]: Array.isArray(records) ? records : [],
          [keys.error]: "",
        }));
      } catch (error) {
        if (cancelled) return;
        setOptions((current) => ({
          ...current,
          [keys.status]: "error",
          [keys.items]: [],
          [keys.error]: error instanceof Error ? error.message : fallback,
        }));
      }
    };

    loadList(
      getCustomers,
      { status: "customersStatus", items: "customers", error: "customersError" },
      "Unable to load customers."
    );
    loadList(
      getVehicles,
      { status: "vehiclesStatus", items: "vehicles", error: "vehiclesError" },
      "Unable to load vehicles."
    );
    loadList(
      getMechanics,
      { status: "mechanicsStatus", items: "mechanics", error: "mechanicsError" },
      "Unable to load mechanics."
    );
    loadList(
      getJobCardAppointments,
      {
        status: "appointmentsStatus",
        items: "appointments",
        error: "appointmentsError",
      },
      "Unable to load appointments."
    );

    return () => {
      cancelled = true;
      /* A loader that returns early on `cancelled` never writes a terminal
         status, which would strand a dropdown at "loading". */
      setOptions((current) => ({
        ...current,
        customersStatus: "idle",
        vehiclesStatus: "idle",
        mechanicsStatus: "idle",
        appointmentsStatus: "idle",
      }));
    };
  }, [showForm, optionsRefreshKey]);

  const handleOptionsRetry = () => {
    setOptions((current) => ({
      ...current,
      customersError: "",
      vehiclesError: "",
      mechanicsError: "",
      appointmentsError: "",
    }));
    setOptionsRefreshKey((value) => value + 1);
  };


  /* Stats are derived from the loaded records only. */
  const stats = useMemo(() => {
    let total = 0;
    let inRepair = 0;
    let qualityCheck = 0;
    let delivered = 0;
    let overdue = 0;
    const now = list.loadedAt;

    list.data.forEach((item) => {
      total += 1;
      const status = normalizeStatus(item.status);
      if (status === "IN_REPAIR") inRepair += 1;
      if (status === "QUALITY_CHECK") qualityCheck += 1;
      if (status === "DELIVERED") {
        delivered += 1;
        return;
      }
      if (item.estimatedDelivery) {
        const due = new Date(item.estimatedDelivery).getTime();
        if (!Number.isNaN(due) && due < now) overdue += 1;
      }
    });

    return { total, inRepair, qualityCheck, delivered, overdue };
  }, [list.data, list.loadedAt]);

  const visibleJobs = useMemo(() => {
    const active = STATUS_FILTERS.find((filter) => filter.key === statusFilter);
    if (!active || !active.match) return list.data;
    return list.data.filter((item) => normalizeStatus(item.status) === active.match);
  }, [list.data, statusFilter]);

  /* VehicleResponseDTO and AppointmentResponseDTO both carry customerId, so
     ownership is enforced here rather than letting an unrelated vehicle reach
     the backend. */
  const vehiclesForCustomer = useMemo(() => {
    if (!formData.customerId) return [];
    return options.vehicles.filter((vehicle) =>
      sameId(vehicle.customerId, formData.customerId)
    );
  }, [options.vehicles, formData.customerId]);

  const appointmentsForCustomer = useMemo(() => {
    if (!formData.customerId) return [];
    return options.appointments.filter((appointment) =>
      sameId(appointment.customerId, formData.customerId)
    );
  }, [options.appointments, formData.customerId]);

  const vehiclesUnassigned = useMemo(
    () =>
      options.vehicles.filter(
        (vehicle) => vehicle.customerId === null || vehicle.customerId === undefined
      ).length,
    [options.vehicles]
  );

  const vehiclesLoading = options.vehiclesStatus === "loading";

  const vehiclePlaceholder = !formData.customerId
    ? "Select a customer first"
    : vehiclesLoading
      ? "Loading vehicles…"
      : options.vehiclesStatus === "error"
        ? "Unable to load vehicles"
        : vehiclesForCustomer.length
          ? "Select vehicle"
          : "No vehicles registered for this customer.";


  const updateField = (field) => (event) => {
    setCreateError("");
    setFormData((current) => ({ ...current, [field]: event.target.value }));
  };

  const openForm = () => {
    setFormData(EMPTY_FORM);
    setCreateError("");
    setSuccessMessage("");
    /* A failed lookup is retried the next time the dialog opens. */
    if (
      options.customersError ||
      options.vehiclesError ||
      options.mechanicsError ||
      options.appointmentsError
    ) {
      setOptionsRefreshKey((value) => value + 1);
    }
    setShowForm(true);
  };

  const closeForm = () => {
    if (!isSaving) {
      setShowForm(false);
      setCreateError("");
    }
  };

  const handleCustomerChange = (event) => {
    setCreateError("");
    setFormData((current) => ({
      ...current,
      customerId: event.target.value,
      /* Changing the customer invalidates the vehicle and appointment choices,
         since both are scoped to that customer. */
      vehicleId: "",
      appointmentId: NO_APPOINTMENT,
    }));
  };

  /* Picking an appointment pre-fills the customer and vehicle from the same
     backend record, so the linked job card matches what was booked. Both stay
     editable afterwards. */
  const handleAppointmentChange = (event) => {
    setCreateError("");
    const appointmentId = event.target.value;

    if (!appointmentId) {
      setFormData((current) => ({ ...current, appointmentId: NO_APPOINTMENT }));
      return;
    }

    const chosen = options.appointments.find((appointment) =>
      sameId(appointment.id, appointmentId)
    );
    setFormData((current) => ({
      ...current,
      appointmentId,
      customerId: chosen?.customerId ? String(chosen.customerId) : current.customerId,
      vehicleId: chosen?.vehicleId ? String(chosen.vehicleId) : current.vehicleId,
      serviceType: SERVICE_TYPES.includes(chosen?.serviceType)
        ? chosen.serviceType
        : current.serviceType,
    }));
  };

  /* Mirrors the DTO constraints so an avoidable round trip is not made. The
     backend remains the authority — its error is what the user sees. */
  const validate = () => {
    if (!formData.customerId) return "Select a customer.";
    if (!formData.vehicleId) return "Select a vehicle.";
    if (!formData.serviceType.trim()) return "Service type is required.";
    if (!SERVICE_TYPES.includes(formData.serviceType)) {
      return "Select a service from the list.";
    }

    const odometer = formData.odometerReading.trim();
    if (odometer) {
      const value = Number(odometer);
      if (!Number.isInteger(value) || value < 0) {
        return "Odometer reading must be a whole number of kilometres.";
      }
    }

    const cost = formData.estimatedCost.trim();
    if (cost) {
      const value = Number(cost);
      if (!Number.isFinite(value) || value < 0) {
        return "Estimated cost must be a positive amount.";
      }
    }

    const chosenVehicle = options.vehicles.find((vehicle) =>
      sameId(vehicle.id, formData.vehicleId)
    );
    if (chosenVehicle && !sameId(chosenVehicle.customerId, formData.customerId)) {
      return "That vehicle does not belong to the selected customer.";
    }

    const chosenAppointment = options.appointments.find((appointment) =>
      sameId(appointment.id, formData.appointmentId)
    );
    if (
      chosenAppointment &&
      !sameId(chosenAppointment.customerId, formData.customerId)
    ) {
      return "That appointment does not belong to the selected customer.";
    }

    return "";
  };

  const handleCreateJobCard = async (event) => {
    event.preventDefault();

    if (isSaving) return;

    const validationError = validate();
    if (validationError) {
      setCreateError(validationError);
      return;
    }

    setIsSaving(true);
    setCreateError("");

    /* Only fields declared by JobCardRequestDTO are sent. Optional blanks are
       omitted rather than sent as "". `status` is left out on purpose: the
       backend forces RECEIVED on create. */
    const payload = {
      customerId: Number(formData.customerId),
      vehicleId: Number(formData.vehicleId),
      serviceType: formData.serviceType.trim(),
    };
    if (formData.appointmentId) {
      payload.appointmentId = Number(formData.appointmentId);
    }
    if (formData.mechanicId) payload.mechanicId = Number(formData.mechanicId);
    if (formData.odometerReading.trim()) {
      payload.odometerReading = Number(formData.odometerReading.trim());
    }
    if (formData.complaint.trim()) payload.complaint = formData.complaint.trim();
    if (formData.technicianNotes.trim()) {
      payload.technicianNotes = formData.technicianNotes.trim();
    }
    if (formData.estimatedDelivery) {
      payload.estimatedDelivery = `${formData.estimatedDelivery}:00`;
    }
    if (formData.estimatedCost.trim()) {
      payload.estimatedCost = Number(formData.estimatedCost.trim());
    }

    try {
      const created = await createJobCard(payload);

      /* Re-read the list rather than inserting by hand, so the new job card
         appears exactly once and only with backend data. */
      const reference = created?.jobCardNumber ? ` ${created.jobCardNumber}` : "";
      setSuccessMessage(`Job card${reference} created successfully.`);
      setShowForm(false);
      setFormData(EMPTY_FORM);
      setStatusFilter(ALL_DAYS);
      setRefreshKey((value) => value + 1);
    } catch (error) {
      setCreateError(
        error instanceof Error ? error.message : "Unable to create the job card."
      );
    } finally {
      setIsSaving(false);
    }
  };


  return (
    <div className="sa-page">

      <div className="sa-page-head">
        <div>
          <h1 className="sa-title">JOB CARDS</h1>
        </div>
        <button
          type="button"
          className="sa-primary"
          onClick={openForm}
          disabled={isSaving}
        >
          + NEW JOB CARD
        </button>
      </div>

      {successMessage && (
        <div className="sa-success" role="status">
          {successMessage}
        </div>
      )}

      <div className="sa-stats">
        <div className="sa-stat">
          <div className="sa-stat-label">Total Today</div>
          <div className="sa-stat-value">
            {list.status === "success" ? stats.total : list.status === "loading" ? "…" : NOT_PROVIDED}
          </div>
        </div>
        <div className="sa-stat">
          <div className="sa-stat-label">In Repair</div>
          <div className="sa-stat-value" style={{ color: "var(--sa-lime)" }}>
            {list.status === "success" ? stats.inRepair : list.status === "loading" ? "…" : NOT_PROVIDED}
          </div>
        </div>
        <div className="sa-stat">
          <div className="sa-stat-label">QC</div>
          <div className="sa-stat-value">
            {list.status === "success" ? stats.qualityCheck : list.status === "loading" ? "…" : NOT_PROVIDED}
          </div>
        </div>
        <div className="sa-stat">
          <div className="sa-stat-label">Delivered</div>
          <div className="sa-stat-value">
            {list.status === "success" ? stats.delivered : list.status === "loading" ? "…" : NOT_PROVIDED}
          </div>
        </div>
        <div className="sa-stat">
          <div className="sa-stat-label">Overdue</div>
          <div className="sa-stat-value">
            {list.status === "success" ? stats.overdue : list.status === "loading" ? "…" : NOT_PROVIDED}
          </div>
        </div>
      </div>

      <div className="sa-date-tabs">
        {STATUS_FILTERS.map((filter) => (
          <button
            key={filter.key}
            type="button"
            className={`sa-tab ${statusFilter === filter.key ? "active" : ""}`}
            onClick={() => setStatusFilter(filter.key)}
          >
            {filter.label}
          </button>
        ))}
      </div>

      <div className="sa-job-list">
        {list.status === "loading" ? (
          <p className="sa-job">Loading job cards…</p>
        ) : list.status === "error" ? (
          <div className="sa-job">
            <div className="sa-list-error" role="alert">
              <span>{list.error}</span>
              <button type="button" className="sa-retry" onClick={handleRetry}>
                Retry
              </button>
            </div>
          </div>
        ) : visibleJobs.length === 0 ? (
          <p className="sa-job">
            {list.data.length === 0
              ? "No job cards have been raised yet."
              : "No job cards match the selected status."}
          </p>
        ) : (
          visibleJobs.map((job) => {
            const status = normalizeStatus(job.status);
            const isDelivered = status === "DELIVERED";
            const progress = Number.isFinite(Number(job.progress))
              ? Number(job.progress)
              : 1;

            return (
              <article className="sa-job" key={job.id}>
                <div className="sa-job-top">
                  <div>
                    <div className="sa-id">{job.jobCardNumber || NOT_PROVIDED}</div>
                    <div className="sa-job-value" style={{ fontSize: 14, marginTop: 4 }}>
                      {job.customerName || NOT_PROVIDED}
                    </div>
                    <div className="sa-job-sub">{job.vehicleInfo || NOT_PROVIDED}</div>
                  </div>
                  <div>
                    <div className="sa-job-label">Service</div>
                    <div className="sa-job-value">{job.serviceType || NOT_PROVIDED}</div>
                    <div className="sa-job-sub">
                      Mechanic: {job.mechanicName || "Unassigned"}
                    </div>
                  </div>
                  <div>
                    <span className={`sa-status ${statusClass(job.status)}`}>
                      {statusLabel(job.status)}
                    </span>
                  </div>
                  <div>
                    <div className="sa-job-label">{isDelivered ? "" : "ETA"}</div>
                    <div className="sa-job-sub">
                      {isDelivered
                        ? "Delivered"
                        : formatDateTime(job.estimatedDelivery) || NOT_PROVIDED}
                    </div>
                  </div>
                  <div>
                    <div className="sa-job-amount">
                      {formatCost(job.estimatedCost) || NOT_PROVIDED}
                    </div>
                    <button
                      type="button"
                      className="sa-details"
                      disabled
                      title="Opening a job card is handled by the Job Card workspace."
                    >
                      Details
                    </button>
                  </div>
                </div>
                <div className="sa-stepper">
                  {STEPS.map((step, index) => (
                    <div
                      key={step}
                      className={`sa-step ${index < progress ? "done" : ""}`}
                    >
                      {step}
                    </div>
                  ))}
                </div>
              </article>
            );
          })
        )}
      </div>

      {/* ── NEW JOB CARD MODAL ── */}

      {showForm && (
        <div
          className="sa-modal-overlay"
          role="presentation"
          onClick={(event) => {
            /* Only a click on the overlay itself closes; clicks inside the
               dialog are ignored so the form is never dismissed by accident. */
            if (event.target === event.currentTarget) closeForm();
          }}
        >
          <div
            className="sa-modal sa-modal--scroll sa-modal--jobcard"
            role="dialog"
            aria-modal="true"
            aria-labelledby="sa-new-job-card-title"
          >
            <div className="sa-modal-header">
              <h2 className="sa-modal-title" id="sa-new-job-card-title">
                NEW JOB CARD
              </h2>
              <button
                type="button"
                className="sa-modal-close"
                onClick={closeForm}
                disabled={isSaving}
                aria-label="Close"
              >
                ×
              </button>
            </div>

            <form
              className="sa-modal-form"
              onSubmit={handleCreateJobCard}
              noValidate
            >
              {(options.customersStatus === "loading" ||
                options.vehiclesStatus === "loading" ||
                options.mechanicsStatus === "loading" ||
                options.appointmentsStatus === "loading") && (
                <p className="sa-modal-note">
                  Loading customers, vehicles, mechanics and appointments…
                </p>
              )}

              {(options.customersError ||
                options.vehiclesError ||
                options.mechanicsError ||
                options.appointmentsError) && (
                <div className="sa-list-error" role="alert">
                  <span>
                    {[
                      options.customersError && `Customers: ${options.customersError}`,
                      options.vehiclesError && `Vehicles: ${options.vehiclesError}`,
                      options.mechanicsError && `Mechanics: ${options.mechanicsError}`,
                      options.appointmentsError &&
                        `Appointments: ${options.appointmentsError}`,
                    ]
                      .filter(Boolean)
                      .join(" ")}
                  </span>
                  <button
                    type="button"
                    className="sa-retry"
                    onClick={handleOptionsRetry}
                    disabled={isSaving}
                  >
                    Retry
                  </button>
                </div>
              )}

              <label className="sa-modal-field">
                <span>From Appointment</span>
                <select
                  className="sa-modal-input"
                  value={formData.appointmentId}
                  onChange={handleAppointmentChange}
                  disabled={isSaving || options.appointmentsStatus !== "success"}
                >
                  <option value={NO_APPOINTMENT}>
                    {options.appointmentsStatus === "loading"
                      ? "Loading appointments…"
                      : CREATE_WITHOUT_APPOINTMENT}
                  </option>
                  {appointmentsForCustomer.map((appointment) => (
                    <option key={appointment.id} value={String(appointment.id)}>
                      {appointmentLabel(appointment)}
                    </option>
                  ))}
                </select>
              </label>

              <label className="sa-modal-field">
                <span>Customer *</span>
                <select
                  className="sa-modal-input"
                  value={formData.customerId}
                  onChange={handleCustomerChange}
                  disabled={isSaving || options.customersStatus !== "success"}
                >
                  <option value="">
                    {options.customersStatus === "loading"
                      ? "Loading customers…"
                      : "Select customer"}
                  </option>
                  {options.customers.map((customer) => (
                    <option key={customer.id} value={String(customer.id)}>
                      {customer.name || customer.phone || `Customer ${customer.id}`}
                    </option>
                  ))}
                </select>
              </label>

              <label className="sa-modal-field">
                <span>Vehicle *</span>
                <select
                  className="sa-modal-input"
                  value={formData.vehicleId}
                  onChange={updateField("vehicleId")}
                  /* Disabled only while the lookup is genuinely in flight, and
                     until a customer is picked. */
                  disabled={isSaving || !formData.customerId || vehiclesLoading}
                >
                  <option value="">{vehiclePlaceholder}</option>
                  {vehiclesForCustomer.map((vehicle) => (
                    <option key={vehicle.id} value={String(vehicle.id)}>
                      {[vehicle.make, vehicle.model, vehicle.registrationNo]
                        .filter(Boolean)
                        .join(" ") || `Vehicle ${vehicle.id}`}
                    </option>
                  ))}
                </select>

                {formData.customerId &&
                  options.vehiclesStatus === "success" &&
                  vehiclesForCustomer.length === 0 && (
                    <p className="sa-modal-note">
                      No vehicles registered for this customer.
                      {vehiclesUnassigned > 0
                        ? ` ${vehiclesUnassigned} loaded vehicle${
                            vehiclesUnassigned === 1 ? " is" : "s are"
                          } not linked to a customer and cannot be used here.`
                        : ""}
                    </p>
                  )}
              </label>

              <label className="sa-modal-field">
                <span>Odometer Reading (km)</span>
                <input
                  className="sa-modal-input"
                  type="number"
                  min="0"
                  step="1"
                  value={formData.odometerReading}
                  onChange={updateField("odometerReading")}
                  disabled={isSaving}
                  placeholder="e.g. 48250"
                />
              </label>

              <label className="sa-modal-field">
                <span>Service Type *</span>
                <select
                  className="sa-modal-input"
                  value={formData.serviceType}
                  onChange={updateField("serviceType")}
                  disabled={isSaving}
                >
                  {SERVICE_TYPES.map((serviceType) => (
                    <option key={serviceType} value={serviceType}>
                      {serviceType}
                    </option>
                  ))}
                </select>
              </label>

              <label className="sa-modal-field">
                <span>Assign Mechanic</span>
                <select
                  className="sa-modal-input"
                  value={formData.mechanicId}
                  onChange={updateField("mechanicId")}
                  disabled={isSaving || options.mechanicsStatus !== "success"}
                >
                  <option value={AUTO_ASSIGN}>
                    {options.mechanicsStatus === "loading"
                      ? "Loading mechanics…"
                      : AUTO_ASSIGN_LABEL}
                  </option>
                  {options.mechanics.map((mechanic) => (
                    <option key={mechanic.id} value={String(mechanic.id)}>
                      {mechanic.name || `Mechanic ${mechanic.id}`}
                    </option>
                  ))}
                </select>
              </label>

              <label className="sa-modal-field">
                <span>Customer Complaint</span>
                <textarea
                  className="sa-modal-input sa-modal-textarea"
                  value={formData.complaint}
                  onChange={updateField("complaint")}
                  disabled={isSaving}
                  maxLength={2000}
                  rows={3}
                  placeholder="What is the customer reporting?"
                />
              </label>

              <div className="sa-modal-row">
                <label className="sa-modal-field">
                  <span>Estimated Delivery</span>
                  <input
                    className="sa-modal-input"
                    type="datetime-local"
                    value={formData.estimatedDelivery}
                    onChange={updateField("estimatedDelivery")}
                    disabled={isSaving}
                  />
                </label>

                <label className="sa-modal-field">
                  <span>Estimated Cost (₹)</span>
                  <input
                    className="sa-modal-input"
                    type="number"
                    min="0"
                    step="0.01"
                    value={formData.estimatedCost}
                    onChange={updateField("estimatedCost")}
                    disabled={isSaving}
                    placeholder="0"
                  />
                </label>
              </div>

              <label className="sa-modal-field">
                <span>Technician Notes</span>
                <textarea
                  className="sa-modal-input sa-modal-textarea"
                  value={formData.technicianNotes}
                  onChange={updateField("technicianNotes")}
                  disabled={isSaving}
                  maxLength={2000}
                  rows={2}
                  placeholder="Notes for the technician…"
                />
              </label>

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
                  disabled={isSaving}
                >
                  Cancel
                </button>
                <button
                  type="submit"
                  className="sa-modal-submit"
                  disabled={isSaving}
                >
                  {isSaving ? "CREATING…" : "CREATE JOB CARD"}
                </button>
              </div>

            </form>
          </div>
        </div>
      )}

    </div>
  );
}