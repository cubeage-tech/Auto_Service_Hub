import React, { useEffect, useMemo, useState } from "react";

import { createAppointment, getAppointmentAdvisors, getAppointmentBays, getAppointmentTypes, getAppointments, updateAppointment } from "../../services/appointmentService";
import { getCustomers, getVehicles } from "../../services/vehicleService";

import "./ServiceAdvisor.css";


/* =========================================================
   ALLOWED VALUES
   AppointmentRequestDTO (backend) declares exactly nine fields:
     customerId    @NotNull Long
     vehicleId     @NotNull Long
     serviceType   @NotBlank String
     appointmentAt @NotNull LocalDateTime
     pickupDrop    Boolean
     notes         String
     status        String, defaulted server-side to "SCHEDULED" when null
     baySlot       String, validated against the garage's bay list
     advisorId     Long, must be an active user holding SERVICE_ADVISOR
     appointmentType String, one of WALK_IN / ONLINE / PICKUP_DROP
   Every column the list shows is now backed by the DTO; only the pickup
   marker and the derived ID are presentation-only.
   ========================================================= */

/* Fallback only. The real list comes from GET /appointments/bays so the picker
   cannot drift away from what the service accepts; this keeps the form usable
   if that lookup fails. */
const FALLBACK_BAYS = ["Bay 1", "Bay 2", "Bay 3", "Bay 4"];

/* AppointmentResponseDTO.status is a free string and the update endpoint
   replaces it wholesale, so these are the values the desk sets.
   IN_PROGRESS matches the vocabulary already used by JobTask, Inspection and
   Followup; AppointmentServiceImpl accepts it verbatim. `displayLabel` renders
   it as "In Progress" while the stored value stays IN_PROGRESS. */
const STATUSES = ["SCHEDULED", "CONFIRMED", "IN_PROGRESS", "PENDING"];

/* Booking types, as stored by AppointmentServiceImpl. The API is the source
   of truth (GET /appointments/types); this map only turns a stored value into
   the label the desk reads. "Pickup/Drop" is the label the reference uses. */
const APPOINTMENT_TYPE_LABELS = {
  WALK_IN: "Walk-in",
  ONLINE: "Online",
  PICKUP_DROP: "Pickup/Drop",
};

const APPOINTMENT_TYPES = [
  { value: "WALK_IN", label: "Walk-in" },
  { value: "ONLINE", label: "Online" },
  { value: "PICKUP_DROP", label: "Pickup/Drop" },
];

/* Stored values the backend currently accepts, used to validate before submit
   and to keep a stale option from being selected. */
const SUPPORTED_APPOINTMENT_TYPES = APPOINTMENT_TYPES.map((type) => type.value);

/* No default bay is forced here: leaving it blank means "unassigned", and the
   service treats a blank bay as null rather than as Bay 1. */

/* AppointmentRequestDTO.serviceType is a plain @NotBlank String mapped to a
   VARCHAR 255 column, and AppointmentServiceImpl passes it straight through —
   there is no backend enum or whitelist. These are the values the SmartGarage
   service desk books, offered in this fixed order and guarded on submit so no
   value outside the list ever reaches the API. */
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

const EMPTY_FORM = {
  appointmentType: "WALK_IN",
  customerId: "",
  vehicleId: "",
  serviceType: "",
  appointmentDate: "",
  appointmentTime: "",
baySlot: "",
advisorId: "",
  pickupDrop: false,
  /* The DTO defaults this server-side, but it is sent explicitly so the
     wire payload is unchanged from the previous revision of this form. */
  status: "SCHEDULED",
  notes: "",
};

/* AppointmentResponseDTO is the only shape the list endpoint returns. Columns
   it has no property for render a dash rather than sample data. */
const NOT_PROVIDED = "—";

const ALL_DAYS = "ALL DAYS";

const MAX_DAY_TABS = 5;


const getDateKey = (value) => {
  if (!value) return "";
  const date = new Date(value);
  if (Number.isNaN(date.getTime())) return "";
  return [
    date.getFullYear(),
    String(date.getMonth() + 1).padStart(2, "0"),
    String(date.getDate()).padStart(2, "0"),
  ].join("-");
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

const formatTime = (value) => {
  if (!value) return null;
  const date = new Date(value);
  if (Number.isNaN(date.getTime())) return null;
  return date.toLocaleTimeString("en-GB", {
    hour: "2-digit",
    minute: "2-digit",
    hour12: true,
  });
};

/* Splits a LocalDateTime into the values a <input type="date"> and
   <input type="time"> need, read in the viewer's own timezone so the saved
   time is the one they actually see. */
const toTimeInputValue = (value) => {
  if (!value) return "";
  const date = new Date(value);
  if (Number.isNaN(date.getTime())) return "";
  return `${String(date.getHours()).padStart(2, "0")}:${String(
    date.getMinutes()
  ).padStart(2, "0")}`;
};

const displayLabel = (status) =>
  (status || "Unknown")
    .toLowerCase()
    .split(/[_\s]+/)
    .map((word) => word.charAt(0).toUpperCase() + word.slice(1))
    .join(" ");

const normalizeStatus = (status) => String(status || "").trim().toUpperCase();

const statusClass = (status) => {
  const normalized = normalizeStatus(status);
  if (normalized.includes("CONFIRM")) return "status-confirmed";
  if (normalized.includes("PROGRESS")) return "status-progress";
  if (normalized.includes("PEND") || normalized.includes("SCHEDUL")) return "status-pending";
  if (normalized.includes("DELIVER") || normalized.includes("COMPLET")) return "status-delivered";
  return "";
};

const displayId = (id) =>
  id === null || id === undefined ? NOT_PROVIDED : `APT-${String(id).padStart(4, "0")}`;

/* The API returns Long ids as JSON numbers while the selects hold strings, so
   both sides are normalised before they are compared. A missing id on either
   side is a real mismatch, never a loose "undefined === undefined" match. */
const sameId = (left, right) => {
  if (left === null || left === undefined) return false;
  if (right === null || right === undefined) return false;
  const a = String(left).trim();
  const b = String(right).trim();
  return a !== "" && b !== "" && a === b;
};

/* Keys describing which slice of `choices` a lookup writes to. */
const CUSTOMER_KEYS = {
  status: "customersStatus",
  items: "customers",
  error: "customersError",
};

const VEHICLE_KEYS = {
  status: "vehiclesStatus",
  items: "vehicles",
  error: "vehiclesError",
};

const ADVISOR_KEYS = {
  status: "advisorsStatus",
  items: "advisors",
  error: "advisorsError",
};

/* Resolves a stored appointment type to its display label. Unknown or missing
   values render as a dash rather than being guessed at. */
const appointmentTypeLabel = (value) =>
  APPOINTMENT_TYPE_LABELS[normalizeStatus(value)] || NOT_PROVIDED;

const startOfWeek = (date) => {
  const start = new Date(date);
  start.setHours(0, 0, 0, 0);
  start.setDate(start.getDate() - ((start.getDay() + 6) % 7));
  return start;
};


export default function Appointments() {
  const [list, setList] = useState({ status: "loading", data: [], error: "" });
  const [refreshKey, setRefreshKey] = useState(0);

  const [selectedDay, setSelectedDay] = useState(ALL_DAYS);

  const [showForm, setShowForm] = useState(false);
  const [formData, setFormData] = useState(EMPTY_FORM);
  const [isSaving, setIsSaving] = useState(false);
  const [createError, setCreateError] = useState("");
  const [successMessage, setSuccessMessage] = useState("");

  /* Edit modal. `editTarget` is the row being edited; it stays set while the
     dialog is open so both dialogs share one customer/vehicle lookup. */
  const [editTarget, setEditTarget] = useState(null);
  const [editForm, setEditForm] = useState(EMPTY_FORM);
  const [isSavingEdit, setIsSavingEdit] = useState(false);
  const [editError, setEditError] = useState("");

  /* Customers and vehicles are only needed by the dialogs, so they are fetched
     when one opens rather than on every page load. The two lists are tracked
     separately: a slow or failing vehicle lookup must not hold the customer
     list hostage, and each dropdown leaves "loading" on its own. */
  const [choices, setChoices] = useState({
    customersStatus: "idle",
    customers: [],
    customersError: "",
    vehiclesStatus: "idle",
    vehicles: [],
    vehiclesError: "",
    bays: FALLBACK_BAYS,
    baysError: "",
    supportedTypes: SUPPORTED_APPOINTMENT_TYPES,
    typesError: "",
    advisorsStatus: "idle",
    advisors: [],
    advisorsError: "",
  });

  /* Re-fetching is driven by this key, never by a status field. Depending on
     the status would cancel the in-flight request the instant it flips to
     "loading", because the cleanup would run before the response arrived. */
  const [choicesRefreshKey, setChoicesRefreshKey] = useState(0);


  useEffect(() => {
    let cancelled = false;

    const load = async () => {
      try {
        const records = await getAppointments();
        if (!cancelled) {
          setList({
            status: "success",
            data: Array.isArray(records) ? records : [],
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
                : "Unable to load appointments.",
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
    setList({ status: "loading", data: [], error: "" });
    setRefreshKey((value) => value + 1);
  };

  /* Re-runs the customer and vehicle lookup. Replacing the current effect
     cancels anything still in flight, so the latest click always wins. */
  const handleChoicesRetry = () => {
    setChoices((current) => ({
      ...current,
      customersError: "",
      vehiclesError: "",
      advisorsError: "",
      baysError: "",
    }));
    setChoicesRefreshKey((value) => value + 1);
  };

  useEffect(() => {
    if (!showForm && !editTarget) return;
    let cancelled = false;

    /* Each lookup owns its slice of state and settles on its own, so one
       failing request can never leave the other dropdown spinning. */
    const loadList = async (load, keys, fallback) => {
      setChoices((current) => ({
        ...current,
        [keys.status]: "loading",
        [keys.error]: "",
      }));

      try {
        const records = await load();
        if (cancelled) return;
        setChoices((current) => ({
          ...current,
          [keys.status]: "success",
          [keys.items]: Array.isArray(records) ? records : [],
          [keys.error]: "",
        }));
      } catch (error) {
        if (cancelled) return;
        setChoices((current) => ({
          ...current,
          [keys.status]: "error",
          [keys.items]: [],
          [keys.error]:
            error instanceof Error ? error.message : fallback,
        }));
      }
    };

    loadList(getCustomers, CUSTOMER_KEYS, "Unable to load customers.");
    loadList(getVehicles, VEHICLE_KEYS, "Unable to load vehicles.");
    loadList(getAppointmentAdvisors, ADVISOR_KEYS, "Unable to load service advisors.");

    /* Bays and booking types are small, non-critical lists, so a failure keeps
       the built-in fallbacks rather than blocking the whole dialog. The error
       is surfaced so it is visible rather than silently masked. */
    (async () => {
      try {
        const bays = await getAppointmentBays();
        if (cancelled) return;
        setChoices((current) => ({
          ...current,
          bays: Array.isArray(bays) && bays.length ? bays : FALLBACK_BAYS,
          baysError: "",
        }));
      } catch (error) {
        if (cancelled) return;
        setChoices((current) => ({
          ...current,
          bays: FALLBACK_BAYS,
          baysError:
            error instanceof Error
              ? `${error.message} Using the default bay list.`
              : "Unable to load the bay list.",
        }));
      }
    })();

    (async () => {
      try {
        const types = await getAppointmentTypes();
        if (cancelled) return;
        setChoices((current) => ({
          ...current,
          supportedTypes:
            Array.isArray(types) && types.length
              ? types
              : SUPPORTED_APPOINTMENT_TYPES,
          typesError: "",
        }));
      } catch (error) {
        if (cancelled) return;
        setChoices((current) => ({
          ...current,
          supportedTypes: SUPPORTED_APPOINTMENT_TYPES,
          typesError:
            error instanceof Error
              ? `${error.message} Using the default type list.`
              : "Unable to load the appointment type list.",
        }));
      }
    })();

    return () => {
      cancelled = true;
      /* A loader that returns early on `cancelled` never writes a terminal
         status, which would strand the list at "loading" and leave the
         Vehicle select disabled with no way back. Resetting here means a
         reopened dialog always starts from a known state. */
      setChoices((current) => ({
        ...current,
customersStatus: "idle",
      vehiclesStatus: "idle",
      advisorsStatus: "idle",
    }));
    };
  }, [showForm, editTarget, choicesRefreshKey]);

  /* Day tabs are derived from the loaded records, newest first. */
  const dayTabs = useMemo(() => {
    const days = [
      ...new Set(list.data.map((item) => getDateKey(item.appointmentAt)).filter(Boolean)),
    ];
    return days.sort().slice(0, MAX_DAY_TABS);
  }, [list.data]);

  const appointments = useMemo(() => {
    if (selectedDay === ALL_DAYS) return list.data;
    return list.data.filter((item) => getDateKey(item.appointmentAt) === selectedDay);
  }, [list.data, selectedDay]);

  /* Stats are derived from the loaded records only. */
  const stats = useMemo(() => {
    const now = new Date();
    const todayKey = getDateKey(now);
    const tomorrowKey = getDateKey(new Date(new Date(now).setDate(now.getDate() + 1)));
    const weekStart = startOfWeek(now);
    const weekEnd = new Date(weekStart);
    weekEnd.setDate(weekEnd.getDate() + 7);

    let today = 0;
    let todayConfirmed = 0;
    let todayPending = 0;
    let tomorrow = 0;
    let thisWeek = 0;
    let pickupDrop = 0;

    list.data.forEach((item) => {
      const at = item.appointmentAt ? new Date(item.appointmentAt) : null;
      const valid = at && !Number.isNaN(at.getTime());

      if (valid && getDateKey(at) === todayKey) {
        today += 1;
        const status = String(item.status || "").toUpperCase();
        if (status.includes("CONFIRM")) todayConfirmed += 1;
        if (status.includes("PEND") || status.includes("SCHEDUL")) todayPending += 1;
      }
      if (valid && getDateKey(at) === tomorrowKey) tomorrow += 1;
      if (valid && at >= weekStart && at < weekEnd) thisWeek += 1;
      if (item.pickupDrop) pickupDrop += 1;
    });

    return { today, todayConfirmed, todayPending, tomorrow, thisWeek, pickupDrop };
  }, [list.data]);

  /* VehicleResponseDTO carries customerId, so ownership is enforced here rather
     than letting an unrelated vehicle reach the backend. A vehicle whose
     customerId is absent from the response cannot be tied to the selected
     customer, so it is left out instead of being offered to the wrong owner. */
  const vehiclesForCustomer = useMemo(() => {
    if (!formData.customerId) return [];
    return choices.vehicles.filter((vehicle) =>
      sameId(vehicle.customerId, formData.customerId)
    );
  }, [choices.vehicles, formData.customerId]);

  const vehiclesUnassigned = useMemo(
    () =>
      choices.vehicles.filter(
        (vehicle) => vehicle.customerId === null || vehicle.customerId === undefined
      ).length,
    [choices.vehicles]
  );

  /* "Loading" is the only vehicle state allowed to lock the control. Every
     other outcome must leave it clickable, so the empty, failed and ready
     states stay readable and retryable. */
  const vehiclesLoading = choices.vehiclesStatus === "loading";

  /* The empty case is stated plainly rather than left as a bare blank control. */
  const vehiclePlaceholder = !formData.customerId
    ? "Select a customer first"
    : vehiclesLoading
      ? "Loading vehicles…"
      : choices.vehiclesStatus === "error"
        ? "Unable to load vehicles"
        : vehiclesForCustomer.length
          ? "Select vehicle"
          : "No vehicles registered for this customer.";

  /* The pickable types come from the backend. A value with no known label is
     skipped rather than rendered as a raw enum constant. */
  const appointmentTypeOptions = choices.supportedTypes
    .filter((value) => APPOINTMENT_TYPE_LABELS[value])
    .map((value) => ({ value, label: APPOINTMENT_TYPE_LABELS[value] }));

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
      choices.customersError ||
      choices.vehiclesError ||
      choices.advisorsError ||
      choices.baysError ||
      choices.typesError
    ) {
      setChoicesRefreshKey((value) => value + 1);
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
      /* Changing the customer invalidates the previous vehicle choice. */
      vehicleId: "",
    }));
  };

  /* Mirrors the DTO constraints so an avoidable round trip is not made.
     The backend remains the authority — its error is what the user sees. */
  const validate = () => {
    if (!formData.customerId) return "Select a customer.";
    if (!formData.vehicleId) return "Select a vehicle.";
    if (!formData.serviceType.trim()) return "Service type is required.";
    /* The backend accepts any non-blank string, so the list is enforced here
       to keep the stored data consistent with what the desk can book. */
    if (!SERVICE_TYPES.includes(formData.serviceType)) {
      return "Select a service from the list.";
    }
    if (!formData.appointmentDate) return "Appointment date is required.";
    if (!formData.appointmentTime) return "Appointment time is required.";
    if (
      formData.appointmentType &&
      !choices.supportedTypes.includes(formData.appointmentType)
    ) {
      return "Select a supported appointment type.";
    }

    const chosen = choices.vehicles.find((vehicle) =>
      sameId(vehicle.id, formData.vehicleId)
    );
    if (chosen && !sameId(chosen.customerId, formData.customerId)) {
      return "That vehicle does not belong to the selected customer.";
    }

    return "";
  };

  const handleCreateAppointment = async (event) => {
    event.preventDefault();

    if (isSaving) return;

    const validationError = validate();
    if (validationError) {
      setCreateError(validationError);
      return;
    }

    setIsSaving(true);
    setCreateError("");

    /* Only fields declared by AppointmentRequestDTO are sent, and optional
       blanks are omitted rather than sent as "". appointmentAt is sent as a
       plain LocalDateTime, matching the DTO. Every control in the dialog now maps
       onto a DTO field. */
    const payload = {
      customerId: Number(formData.customerId),
      vehicleId: Number(formData.vehicleId),
      serviceType: formData.serviceType.trim(),
      appointmentAt: `${formData.appointmentDate}T${formData.appointmentTime}:00`,
      pickupDrop: formData.pickupDrop,
    };
    if (formData.status) payload.status = formData.status;
    if (formData.notes.trim()) payload.notes = formData.notes.trim();
    if (formData.baySlot) payload.baySlot = formData.baySlot;
    if (formData.advisorId) payload.advisorId = Number(formData.advisorId);
    /* Blank means "not specified": the service stores null, which is how
       appointments predating the column are represented. */
    if (formData.appointmentType) payload.appointmentType = formData.appointmentType;

    try {
      await createAppointment(payload);

      /* Re-read the list rather than inserting by hand, so the appointment
         appears exactly once and only with backend data. */
      setSuccessMessage(
        `Appointment booked for ${formatDate(payload.appointmentAt)} at ${formatTime(payload.appointmentAt)}.`
      );
      setShowForm(false);
      setFormData(EMPTY_FORM);
      setSelectedDay(ALL_DAYS);
      setRefreshKey((value) => value + 1);
    } catch (error) {
      setCreateError(
        error instanceof Error
          ? error.message
          : "Unable to create the appointment."
      );
    } finally {
      setIsSaving(false);
    }
  };


  /* Vehicles available in the edit dialog, scoped to its chosen customer. */
  const editVehiclesForCustomer = useMemo(() => {
    if (!editForm.customerId) return [];
    return choices.vehicles.filter((vehicle) =>
      sameId(vehicle.customerId, editForm.customerId)
    );
  }, [choices.vehicles, editForm.customerId]);

  const editVehiclesLoading = choices.vehiclesStatus === "loading";

  const editVehiclePlaceholder = !editForm.customerId
    ? "Select a customer first"
    : editVehiclesLoading
      ? "Loading vehicles…"
      : choices.vehiclesStatus === "error"
        ? "Unable to load vehicles"
        : editVehiclesForCustomer.length
          ? "Select vehicle"
          : "No vehicles registered for this customer.";

  /* Prefills from the row's own AppointmentResponseDTO. Nothing is guessed:
     every field comes from the record the list already rendered. */
  const openEdit = (appointment) => {
    setCreateError("");
    setEditError("");
    setEditForm({
      customerId: appointment.customerId ? String(appointment.customerId) : "",
      vehicleId: appointment.vehicleId ? String(appointment.vehicleId) : "",
      serviceType: appointment.serviceType || "",
      appointmentDate: getDateKey(appointment.appointmentAt),
      appointmentTime: toTimeInputValue(appointment.appointmentAt),
      pickupDrop: Boolean(appointment.pickupDrop),
      status: STATUSES.includes(normalizeStatus(appointment.status))
        ? normalizeStatus(appointment.status)
        : "SCHEDULED",
      notes: appointment.notes || "",
      /* Older rows carry neither column; those prefill as unassigned rather
         than as an empty control the advisor must re-pick to save. */
      baySlot: choices.bays.includes(appointment.baySlot) ? appointment.baySlot : "",
      /* A legacy row has no stored type; leave the control blank rather than
         silently writing WALK_IN over a value the desk never chose. */
      appointmentType: choices.supportedTypes.includes(
        normalizeStatus(appointment.appointmentType)
      )
        ? normalizeStatus(appointment.appointmentType)
        : "",
      advisorId:
        appointment.advisorId === null || appointment.advisorId === undefined
          ? ""
          : String(appointment.advisorId),
    });
    setEditTarget(appointment);
  };

  const closeEdit = () => {
    if (isSavingEdit) return;
    setEditTarget(null);
    setEditError("");
  };

  const updateEditField = (field) => (event) => {
    setEditError("");
    setEditForm((current) => ({ ...current, [field]: event.target.value }));
  };

  const handleEditCustomerChange = (event) => {
    setEditError("");
    setEditForm((current) => ({
      ...current,
      customerId: event.target.value,
      /* Changing the customer invalidates the previous vehicle choice. */
      vehicleId: "",
    }));
  };

  /* Same DTO constraints as create, so an avoidable round trip is not made.
     The backend remains the authority — its error is what the user sees. */
  const validateEdit = () => {
    if (!editForm.customerId) return "Select a customer.";
    if (!editForm.vehicleId) return "Select a vehicle.";
    if (!editForm.serviceType.trim()) return "Service type is required.";
    if (!SERVICE_TYPES.includes(editForm.serviceType)) {
      return "Select a service from the list.";
    }
    if (!editForm.appointmentDate) return "Appointment date is required.";
    if (!editForm.appointmentTime) return "Appointment time is required.";
    if (
      editForm.appointmentType &&
      !choices.supportedTypes.includes(editForm.appointmentType)
    ) {
      return "Select a supported appointment type.";
    }

    const chosen = choices.vehicles.find((vehicle) =>
      sameId(vehicle.id, editForm.vehicleId)
    );
    if (chosen && !sameId(chosen.customerId, editForm.customerId)) {
      return "That vehicle does not belong to the selected customer.";
    }

    return "";
  };

  const handleUpdateAppointment = async (event) => {
    event.preventDefault();

    if (isSavingEdit || !editTarget) return;

    const validationError = validateEdit();
    if (validationError) {
      setEditError(validationError);
      return;
    }

    setIsSavingEdit(true);
    setEditError("");

    /* Same AppointmentRequestDTO as create. `status` is always sent because
       the backend substitutes "SCHEDULED" when it arrives null. */
    const payload = {
      customerId: Number(editForm.customerId),
      vehicleId: Number(editForm.vehicleId),
      serviceType: editForm.serviceType.trim(),
      appointmentAt: `${editForm.appointmentDate}T${editForm.appointmentTime}:00`,
      pickupDrop: editForm.pickupDrop,
      status: editForm.status,
    };
    if (editForm.notes.trim()) payload.notes = editForm.notes.trim();
    if (editForm.baySlot) payload.baySlot = editForm.baySlot;
    if (editForm.advisorId) payload.advisorId = Number(editForm.advisorId);
    if (editForm.appointmentType) payload.appointmentType = editForm.appointmentType;

    try {
      await updateAppointment(editTarget.id, payload);

      /* Re-read the list so the row shows exactly what the backend stored. */
      setSuccessMessage(
        `Appointment ${
          editTarget.id === null || editTarget.id === undefined
            ? ""
            : displayId(editTarget.id)
        } updated for ${formatDate(payload.appointmentAt)} at ${formatTime(
          payload.appointmentAt
        )}.`
      );
      setEditTarget(null);
      setEditForm(EMPTY_FORM);
      setRefreshKey((value) => value + 1);
    } catch (error) {
      setEditError(
        error instanceof Error
          ? error.message
          : "Unable to update the appointment."
      );
    } finally {
      setIsSavingEdit(false);
    }
  };


  return (
    <div className="sa-page">

      <div className="sa-page-head">
        <div>
          <h1 className="sa-title">APPOINTMENTS</h1>
        </div>
        <button
          type="button"
          className="sa-primary"
          onClick={openForm}
          disabled={isSaving}
        >
          + NEW APPOINTMENT
        </button>
      </div>


      {successMessage && (
        <div className="sa-success" role="status">
          {successMessage}
        </div>
      )}


      <div className="sa-stats">
        <div className="sa-stat">
          <div className="sa-stat-label">Today</div>
          <div className="sa-stat-value">
            {list.status === "success" ? stats.today : list.status === "loading" ? "…" : NOT_PROVIDED}
          </div>
          <div className="sa-stat-note">
            {stats.todayConfirmed} confirmed · {stats.todayPending} pending
          </div>
        </div>
        <div className="sa-stat">
          <div className="sa-stat-label">Tomorrow</div>
          <div className="sa-stat-value" style={{ color: "var(--sa-lime)" }}>
            {list.status === "success" ? stats.tomorrow : list.status === "loading" ? "…" : NOT_PROVIDED}
          </div>
        </div>
        <div className="sa-stat">
          <div className="sa-stat-label">This Week</div>
          <div className="sa-stat-value">
            {list.status === "success" ? stats.thisWeek : list.status === "loading" ? "…" : NOT_PROVIDED}
          </div>
        </div>
        <div className="sa-stat">
          <div className="sa-stat-label">Pickup/Drop</div>
          <div className="sa-stat-value">
            {list.status === "success" ? stats.pickupDrop : list.status === "loading" ? "…" : NOT_PROVIDED}
          </div>
          <div className="sa-stat-note">requiring logistics</div>
        </div>
      </div>


      <div className="sa-date-tabs">
        <button
          type="button"
          className={`sa-tab ${selectedDay === ALL_DAYS ? "active" : ""}`}
          onClick={() => setSelectedDay(ALL_DAYS)}
        >
          ALL DAYS
        </button>
        {dayTabs.map((day) => (
          <button
            key={day}
            type="button"
            className={`sa-tab ${selectedDay === day ? "active" : ""}`}
            onClick={() => setSelectedDay(day)}
          >
            {new Date(`${day}T00:00:00`).toLocaleDateString("en-GB", {
              day: "2-digit",
              month: "short",
            }).toUpperCase()}
          </button>
        ))}
      </div>


      <div className="sa-wide-table">
        <table className="sa-table">
          <thead>
            <tr>
              <th>ID</th>
              <th>Date &amp; Time</th>
              <th>Customer</th>
              <th>Vehicle</th>
              <th>Service</th>
              <th>Type</th>
              <th>Bay</th>
              <th>Pickup</th>
              <th>Advisor</th>
              <th>Status</th>
              <th></th>
            </tr>
          </thead>
          <tbody>
            {list.status === "loading" ? (
              <tr>
                <td colSpan={11}>Loading appointments…</td>
              </tr>
            ) : list.status === "error" ? (
              <tr>
                <td colSpan={11}>
                  <div className="sa-list-error" role="alert">
                    <span>{list.error}</span>
                    <button type="button" className="sa-retry" onClick={handleRetry}>
                      Retry
                    </button>
                  </div>
                </td>
              </tr>
            ) : appointments.length === 0 ? (
              <tr>
                <td colSpan={11}>
                  {list.data.length === 0
                    ? "No appointments have been booked yet."
                    : "No appointments match the selected day."}
                </td>
              </tr>
            ) : (
              appointments.map((item) => (
                <tr key={item.id}>
                  <td className="sa-id">{displayId(item.id)}</td>
                  <td>
                    <strong>{formatDate(item.appointmentAt) || NOT_PROVIDED}</strong>
                    <br />
                    <small>{formatTime(item.appointmentAt) || NOT_PROVIDED}</small>
                  </td>
                  <td><strong>{item.customerName || NOT_PROVIDED}</strong></td>
                  <td>{item.vehicleInfo || NOT_PROVIDED}</td>
                  <td><strong>{item.serviceType || NOT_PROVIDED}</strong></td>
                  {/* Stored value only; a legacy appointment with no type renders as a dash. */}
                  <td>{appointmentTypeLabel(item.appointmentType)}</td>
                  <td>{item.baySlot || NOT_PROVIDED}</td>
                  <td
                    style={{
                      color: item.pickupDrop ? "var(--sa-lime)" : undefined,
                    }}
                  >
                    {item.pickupDrop ? "✓" : NOT_PROVIDED}
                  </td>
                  <td>{item.advisorName || NOT_PROVIDED}</td>
                  <td>
                    <span className={`sa-status ${statusClass(item.status)}`}>
                      {item.status ? displayLabel(item.status) : NOT_PROVIDED}
                    </span>
                  </td>
                  <td>
                    <button
                      type="button"
                      className="sa-details"
                      onClick={() => openEdit(item)}
                      disabled={isSavingEdit}
                    >
                      Edit
                    </button>
                  </td>
                </tr>
              ))
            )}
          </tbody>
        </table>
      </div>


      {/* ── NEW APPOINTMENT MODAL ── */}

      {showForm && (
        <div
          className="sa-modal-overlay sa-modal-overlay--strong"
          role="presentation"
          onClick={(e) => {
            /* Only a click on the overlay itself closes; clicks inside the
               dialog are ignored so the form is never dismissed by accident. */
            if (e.target === e.currentTarget) closeForm();
          }}
        >
          <div
            className="sa-modal sa-modal--appointment"
            role="dialog"
            aria-modal="true"
            aria-labelledby="sa-new-appointment-title"
          >
            <div className="sa-modal-header">
              <h2 className="sa-modal-title" id="sa-new-appointment-title">
                NEW APPOINTMENT
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
              onSubmit={handleCreateAppointment}
              noValidate
            >

              {(choices.customersStatus === "loading" ||
                vehiclesLoading ||
                choices.advisorsStatus === "loading") && (
                <p className="sa-modal-note">
                  Loading customers, vehicles and advisors…
                </p>
              )}

              {(choices.customersError ||
                choices.vehiclesError ||
                choices.advisorsError ||
                choices.baysError ||
                choices.typesError) && (
                <div className="sa-list-error" role="alert">
                  <span>
                    {[
                      choices.customersError && `Customers: ${choices.customersError}`,
                      choices.vehiclesError && `Vehicles: ${choices.vehiclesError}`,
                      choices.advisorsError && `Advisors: ${choices.advisorsError}`,
                      choices.baysError,
                      choices.typesError,
                    ]
                      .filter(Boolean)
                      .join(" ")}
                  </span>
                  <button
                    type="button"
                    className="sa-retry"
                    onClick={handleChoicesRetry}
                    disabled={isSaving}
                  >
                    Retry
                  </button>
                </div>
              )}

              <fieldset className="sa-modal-group">
                <legend className="sa-modal-legend">Appointment Type</legend>
                <div className="sa-choice-row">
                  {appointmentTypeOptions.map((type) => (
                    <label
                      key={type.value}
                      className={`sa-choice ${
                        formData.appointmentType === type.value ? "active" : ""
                      }`}
                    >
                      <input
                        type="radio"
                        name="appointmentType"
                        value={type.value}
                        checked={formData.appointmentType === type.value}
                        onChange={updateField("appointmentType")}
                        disabled={isSaving}
                      />
                      <span>{type.label}</span>
                    </label>
                  ))}
                </div>
              </fieldset>

              <label className="sa-modal-field">
                <span>Customer *</span>
                <select
                  className="sa-modal-input"
                  value={formData.customerId}
                  onChange={handleCustomerChange}
                  disabled={isSaving || choices.customersStatus !== "success"}
                >
                  <option value="">
                    {choices.customersStatus === "loading"
                      ? "Loading customers…"
                      : "Select customer"}
                  </option>
                  {choices.customers.map((customer) => (
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
                  /* Disabled only while the lookup is genuinely in flight, and until a
                     customer is picked. Once loading settles the control is
                     clickable even when the filtered list is empty. */
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
                  choices.vehiclesStatus === "success" &&
                  vehiclesForCustomer.length === 0 && (
                    <p className="sa-modal-note">
                      No vehicles registered for this customer.
                      {vehiclesUnassigned > 0
                        ? ` ${vehiclesUnassigned} loaded vehicle${
                            vehiclesUnassigned === 1 ? " is" : "s are"
                          } not linked to a customer and cannot be booked here.`
                        : ""}
                    </p>
                  )}
              </label>

              <label className="sa-modal-field">
                <span>Service Required *</span>
                <select
                  className="sa-modal-input"
                  value={formData.serviceType}
                  onChange={updateField("serviceType")}
                  disabled={isSaving}
                >
                  <option value="">Select service</option>
                  {SERVICE_TYPES.map((serviceType) => (
                    <option key={serviceType} value={serviceType}>
                      {serviceType}
                    </option>
                  ))}
                </select>
              </label>

              <div className="sa-modal-row">
                <label className="sa-modal-field">
                  <span>Date *</span>
                  <input
                    className="sa-modal-input"
                    type="date"
                    value={formData.appointmentDate}
                    onChange={updateField("appointmentDate")}
                    disabled={isSaving}
                  />
                </label>

                <label className="sa-modal-field">
                  <span>Time Slot *</span>
                  <input
                    className="sa-modal-input"
                    type="time"
                    value={formData.appointmentTime}
                    onChange={updateField("appointmentTime")}
                    disabled={isSaving}
                  />
                </label>
              </div>

              <div className="sa-modal-row">
                <label className="sa-modal-field">
                  <span>Bay / Slot</span>
                  <select
                    className="sa-modal-input"
                    value={formData.baySlot}
                    onChange={updateField("baySlot")}
                    disabled={isSaving}
                  >
                    <option value="">No bay assigned</option>
                    {choices.bays.map((bay) => (
                      <option key={bay} value={bay}>
                        {bay}
                      </option>
                    ))}
                  </select>
                </label>

                <label className="sa-modal-field">
                  <span>Assigned Advisor</span>
                  <select
                    className="sa-modal-input"
                    value={formData.advisorId}
                    onChange={updateField("advisorId")}
                    disabled={isSaving || choices.advisorsStatus !== "success"}
                  >
                    <option value="">
                      {choices.advisorsStatus === "loading"
                        ? "Loading advisors…"
                        : "Unassigned"}
                    </option>
                    {choices.advisors.map((advisor) => (
                      <option key={advisor.id} value={String(advisor.id)}>
                        {advisor.name || `Advisor ${advisor.id}`}
                      </option>
                    ))}
                  </select>
                </label>
              </div>

              <div className="sa-modal-field">
                <span>Pickup / Drop Service</span>
                <label className="sa-modal-check">
                  <input
                    type="checkbox"
                    checked={formData.pickupDrop}
                    onChange={(event) =>
                      setFormData((current) => ({
                        ...current,
                        pickupDrop: event.target.checked,
                      }))
                    }
                    disabled={isSaving}
                  />
                  <span>
                    {formData.pickupDrop
                      ? "Vehicle will be picked up from the customer and returned after service."
                      : "Customer brings the vehicle to the garage and collects it themselves."}
                  </span>
                </label>
              </div>

              <label className="sa-modal-field">
                <span>Special Notes</span>
                <textarea
                  className="sa-modal-input sa-modal-textarea"
                  value={formData.notes}
                  onChange={updateField("notes")}
                  disabled={isSaving}
                  maxLength={2000}
                  rows={3}
                  placeholder="Any remarks about this appointment…"
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
                  {isSaving ? "BOOKING…" : "BOOK APPOINTMENT"}
                </button>
              </div>

            </form>
          </div>
        </div>
      )}

      {/* ── EDIT APPOINTMENT MODAL ── */}

      {editTarget && (
        <div
          className="sa-modal-overlay sa-modal-overlay--strong"
          role="presentation"
          onClick={(event) => {
            /* Only a click on the overlay itself closes. */
            if (event.target === event.currentTarget) closeEdit();
          }}
        >
          <div
            className="sa-modal sa-modal--appointment"
            role="dialog"
            aria-modal="true"
            aria-labelledby="sa-edit-appointment-title"
          >
            <div className="sa-modal-header">
              <h2 className="sa-modal-title" id="sa-edit-appointment-title">
                EDIT APPOINTMENT
              </h2>
              <button
                type="button"
                className="sa-modal-close"
                onClick={closeEdit}
                disabled={isSavingEdit}
                aria-label="Close"
              >
                ×
              </button>
            </div>

            <form
              className="sa-modal-form"
              onSubmit={handleUpdateAppointment}
              noValidate
            >
              <fieldset className="sa-modal-group">
                <legend className="sa-modal-legend">Appointment Type</legend>
                <div className="sa-choice-row">
                  {appointmentTypeOptions.map((type) => (
                    <label
                      key={type.value}
                      className={`sa-choice ${
                        editForm.appointmentType === type.value ? "active" : ""
                      }`}
                    >
                      <input
                        type="radio"
                        name="editAppointmentType"
                        value={type.value}
                        checked={editForm.appointmentType === type.value}
                        onChange={updateEditField("appointmentType")}
                        disabled={isSavingEdit}
                      />
                      <span>{type.label}</span>
                    </label>
                  ))}
                </div>
              </fieldset>
              {(choices.customersStatus === "loading" ||
                editVehiclesLoading ||
                choices.advisorsStatus === "loading") && (
                <p className="sa-modal-note">
                  Loading customers, vehicles and advisors…
                </p>
              )}

              {(choices.customersError ||
                choices.vehiclesError ||
                choices.advisorsError ||
                choices.baysError ||
                choices.typesError) && (
                <div className="sa-list-error" role="alert">
                  <span>
                    {[
                      choices.customersError && `Customers: ${choices.customersError}`,
                      choices.vehiclesError && `Vehicles: ${choices.vehiclesError}`,
                      choices.advisorsError && `Advisors: ${choices.advisorsError}`,
                      choices.baysError,
                      choices.typesError,
                    ]
                      .filter(Boolean)
                      .join(" ")}
                  </span>
                  <button
                    type="button"
                    className="sa-retry"
                    onClick={handleChoicesRetry}
                    disabled={isSavingEdit}
                  >
                    Retry
                  </button>
                </div>
              )}

              <label className="sa-modal-field">
                <span>Customer *</span>
                <select
                  className="sa-modal-input"
                  value={editForm.customerId}
                  onChange={handleEditCustomerChange}
                  disabled={isSavingEdit || choices.customersStatus !== "success"}
                >
                  <option value="">
                    {choices.customersStatus === "loading"
                      ? "Loading customers…"
                      : "Select customer"}
                  </option>
                  {choices.customers.map((customer) => (
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
                  value={editForm.vehicleId}
                  onChange={updateEditField("vehicleId")}
                  disabled={isSavingEdit || !editForm.customerId || editVehiclesLoading}
                >
                  <option value="">{editVehiclePlaceholder}</option>
                  {editVehiclesForCustomer.map((vehicle) => (
                    <option key={vehicle.id} value={String(vehicle.id)}>
                      {[vehicle.make, vehicle.model, vehicle.registrationNo]
                        .filter(Boolean)
                        .join(" ") || `Vehicle ${vehicle.id}`}
                    </option>
                  ))}
                </select>
              </label>

              <label className="sa-modal-field">
                <span>Service Required *</span>
                <select
                  className="sa-modal-input"
                  value={editForm.serviceType}
                  onChange={updateEditField("serviceType")}
                  disabled={isSavingEdit}
                >
                  <option value="">Select service</option>
                  {SERVICE_TYPES.map((serviceType) => (
                    <option key={serviceType} value={serviceType}>
                      {serviceType}
                    </option>
                  ))}
                </select>
              </label>

              <div className="sa-modal-row">
                <label className="sa-modal-field">
                  <span>Date *</span>
                  <input
                    className="sa-modal-input"
                    type="date"
                    value={editForm.appointmentDate}
                    onChange={updateEditField("appointmentDate")}
                    disabled={isSavingEdit}
                  />
                </label>

                <label className="sa-modal-field">
                  <span>Time Slot *</span>
                  <input
                    className="sa-modal-input"
                    type="time"
                    value={editForm.appointmentTime}
                    onChange={updateEditField("appointmentTime")}
                    disabled={isSavingEdit}
                  />
                </label>
              </div>

              <div className="sa-modal-row">
                <label className="sa-modal-field">
                  <span>Status</span>
                  <select
                    className="sa-modal-input"
                    value={editForm.status}
                    onChange={updateEditField("status")}
                    disabled={isSavingEdit}
                  >
                    {STATUSES.map((status) => (
                      <option key={status} value={status}>
                        {displayLabel(status)}
                      </option>
                    ))}
                  </select>
                </label>

                <div className="sa-modal-field">
                  <span>Pickup / Drop Service</span>
                  <label className="sa-modal-check">
                    <input
                      type="checkbox"
                      checked={editForm.pickupDrop}
                      onChange={(event) => {
                        setEditError("");
                        setEditForm((current) => ({
                          ...current,
                          pickupDrop: event.target.checked,
                        }));
                      }}
                      disabled={isSavingEdit}
                    />
                    <span>
                      {editForm.pickupDrop
                        ? "Vehicle will be picked up from the customer and returned after service."
                        : "Customer brings the vehicle to the garage and collects it themselves."}
                    </span>
                  </label>
                </div>
              </div>

              <label className="sa-modal-field">
                <span>Special Notes</span>
                <textarea
                  className="sa-modal-input sa-modal-textarea"
                  value={editForm.notes}
                  onChange={updateEditField("notes")}
                  disabled={isSavingEdit}
                  maxLength={2000}
                  rows={3}
                  placeholder="Any remarks about this appointment…"
                />
              </label>

              {editError && (
                <p className="sa-modal-error" role="alert">
                  {editError}
                </p>
              )}

              <div className="sa-modal-row">
                <label className="sa-modal-field">
                  <span>Bay / Slot</span>
                  <select
                    className="sa-modal-input"
                    value={editForm.baySlot}
                    onChange={updateEditField("baySlot")}
                    disabled={isSavingEdit}
                  >
                    <option value="">No bay assigned</option>
                    {choices.bays.map((bay) => (
                      <option key={bay} value={bay}>
                        {bay}
                      </option>
                    ))}
                  </select>
                </label>

                <label className="sa-modal-field">
                  <span>Assigned Advisor</span>
                  <select
                    className="sa-modal-input"
                    value={editForm.advisorId}
                    onChange={updateEditField("advisorId")}
                    disabled={isSavingEdit || choices.advisorsStatus !== "success"}
                  >
                    <option value="">
                      {choices.advisorsStatus === "loading"
                        ? "Loading advisors…"
                        : "Unassigned"}
                    </option>
                    {choices.advisors.map((advisor) => (
                      <option key={advisor.id} value={String(advisor.id)}>
                        {advisor.name || `Advisor ${advisor.id}`}
                      </option>
                    ))}
                  </select>
                </label>
              </div>

              <div className="sa-modal-footer">
                <button
                  type="button"
                  className="sa-modal-cancel"
                  onClick={closeEdit}
                  disabled={isSavingEdit}
                >
                  Cancel
                </button>
                <button
                  type="submit"
                  className="sa-modal-submit"
                  disabled={isSavingEdit}
                >
                  {isSavingEdit ? "SAVING…" : "SAVE CHANGES"}
                </button>
              </div>

            </form>
          </div>
        </div>
      )}

    </div>
  );
}