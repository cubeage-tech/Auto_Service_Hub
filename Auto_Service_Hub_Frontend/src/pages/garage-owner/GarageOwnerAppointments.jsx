import React, { useCallback, useEffect, useMemo, useState } from "react";
import {
  createAppointment,
  getAppointmentAdvisors,
  getAppointmentBays,
  getAppointmentTypes,
  getAppointments,
  updateAppointment,
} from "../../services/appointmentService";
import { getCustomers, getVehicles } from "../../services/vehicleService";
import "./GarageOwnerAppointments.css";

const APPOINTMENT_TYPE_LABELS = {
  WALK_IN: "Walk-in",
  ONLINE: "Online",
  PICKUP_DROP: "Pickup/Drop",
};

const DEFAULT_APPOINTMENT_TYPES = [
  { value: "WALK_IN", label: "Walk-in" },
  { value: "ONLINE", label: "Online" },
  { value: "PICKUP_DROP", label: "Pickup/Drop" },
];

const DEFAULT_BAYS = ["Bay 1", "Bay 2", "Bay 3", "Bay 4"];

const DEFAULT_SERVICE_OPTIONS = [
  "Basic Service",
  "Full Service",
  "AC Service",
  "Battery Replacement",
  "Tyre Rotation",
  "Denting & Painting",
  "Suspension & Tyres",
];

const initialAppointmentForm = {
  appointmentType: "WALK_IN",
  customerId: "",
  vehicleId: "",
  serviceType: "Basic Service",
  date: "",
  time: "09:00",
  pickupDrop: false,
  baySlot: "",
  advisorId: "",
  status: "SCHEDULED",
  notes: "",
};

const getDateKey = (value) => {
  if (!value) return "";
  const parsedDate = new Date(value);
  return Number.isNaN(parsedDate.getTime())
    ? ""
    : `${parsedDate.getFullYear()}-${String(parsedDate.getMonth() + 1).padStart(2, "0")}-${String(parsedDate.getDate()).padStart(2, "0")}`;
};

const toTimeInputValue = (value) => {
  if (!value) return "09:00";
  const date = new Date(value);
  if (Number.isNaN(date.getTime())) return "09:00";
  return `${String(date.getHours()).padStart(2, "0")}:${String(date.getMinutes()).padStart(2, "0")}`;
};

const formatAppointmentDate = (value) => {
  if (!value) return "—";
  const date = new Date(value);
  return Number.isNaN(date.getTime())
    ? "—"
    : new Intl.DateTimeFormat("en-GB", {
        day: "2-digit",
        month: "short",
        year: "numeric",
      }).format(date);
};

const formatAppointmentTime = (value) => {
  if (!value) return "—";
  const date = new Date(value);
  return Number.isNaN(date.getTime())
    ? "—"
    : new Intl.DateTimeFormat("en-US", {
        hour: "2-digit",
        minute: "2-digit",
        hour12: true,
      }).format(date);
};

const toAppointmentRow = (appointment) => {
  const status = String(appointment.status || "SCHEDULED")
    .toLowerCase()
    .replace(/_/g, " ")
    .replace(/\b\w/g, (letter) => letter.toUpperCase());
  const rawStatusClass = String(appointment.status || "scheduled")
    .toLowerCase()
    .replace(/_/g, "-");
  const statusClass = rawStatusClass === "in-progress"
    ? "progress"
    : rawStatusClass;

  const normalizedType = appointment.appointmentType
    ? String(appointment.appointmentType).trim().toUpperCase()
    : null;
  const displayType = normalizedType
    ? (APPOINTMENT_TYPE_LABELS[normalizedType] || appointment.appointmentType)
    : (appointment.pickupDrop ? "Pickup/Drop" : "—");

  return {
    id: appointment.id,
    displayId: `APT-${String(appointment.id).padStart(4, "0")}`,
    date: formatAppointmentDate(appointment.appointmentAt),
    time: formatAppointmentTime(appointment.appointmentAt),
    day: getDateKey(appointment.appointmentAt),
    customer: appointment.customerName || "—",
    vehicle: appointment.vehicleInfo || "—",
    service: appointment.serviceType || "—",
    serviceType: appointment.serviceType || "",
    type: displayType,
    appointmentType: normalizedType || "",
    bay: appointment.baySlot || "—",
    baySlot: appointment.baySlot || "",
    pickup: appointment.pickupDrop ? "✓" : "—",
    advisor: appointment.advisorName || "—",
    advisorId: appointment.advisorId != null ? String(appointment.advisorId) : "",
    advisorName: appointment.advisorName || "",
    status,
    statusClass,
    rawStatus: appointment.status || "SCHEDULED",
    customerId: appointment.customerId,
    vehicleId: appointment.vehicleId,
    appointmentAt: appointment.appointmentAt,
    pickupDrop: Boolean(appointment.pickupDrop),
    notes: appointment.notes || "",
  };
};

function GarageOwnerAppointments() {
  const [selectedDay, setSelectedDay] = useState("ALL DAYS");
  const [showNewAppointment, setShowNewAppointment] = useState(false);
  const [editingAppointmentId, setEditingAppointmentId] = useState(null);
  const [appointments, setAppointments] = useState([]);
  const [customers, setCustomers] = useState([]);
  const [vehicles, setVehicles] = useState([]);
  const [bays, setBays] = useState(DEFAULT_BAYS);
  const [advisors, setAdvisors] = useState([]);
  const [appointmentTypes, setAppointmentTypes] = useState(DEFAULT_APPOINTMENT_TYPES);
  const [appointmentForm, setAppointmentForm] = useState(initialAppointmentForm);
  const [loading, setLoading] = useState(true);
  const [loadingChoices, setLoadingChoices] = useState(false);
  const [saving, setSaving] = useState(false);
  const [loadError, setLoadError] = useState("");
  const [choiceError, setChoiceError] = useState("");
  const [formError, setFormError] = useState("");

  const loadAppointments = useCallback(async () => {
    setLoading(true);
    setLoadError("");
    try {
      setAppointments((await getAppointments()).map(toAppointmentRow));
    } catch (error) {
      setLoadError(error instanceof Error ? error.message : "Unable to load appointments.");
    } finally {
      setLoading(false);
    }
  }, []);

  useEffect(() => {
    void loadAppointments();
  }, [loadAppointments]);

  const availableDays = useMemo(() => {
    const days = [...new Set(appointments.map((appointment) => appointment.day).filter(Boolean))];
    return days.sort((first, second) => first.localeCompare(second)).slice(0, 3);
  }, [appointments]);

  const filteredAppointments = selectedDay === "ALL DAYS"
    ? appointments
    : appointments.filter((appointment) => appointment.day === selectedDay);

  const now = new Date();
  const todayKey = getDateKey(now);
  const tomorrow = new Date(now);
  tomorrow.setDate(tomorrow.getDate() + 1);
  const tomorrowKey = getDateKey(tomorrow);
  const weekStart = new Date(now);
  weekStart.setHours(0, 0, 0, 0);
  weekStart.setDate(weekStart.getDate() - ((weekStart.getDay() + 6) % 7));
  const weekEnd = new Date(weekStart);
  weekEnd.setDate(weekEnd.getDate() + 7);
  const todayAppointments = appointments.filter((appointment) => appointment.day === todayKey);
  const tomorrowCount = appointments.filter((appointment) => appointment.day === tomorrowKey).length;
  const weekCount = appointments.filter((appointment) => {
    const date = appointment.appointmentAt ? new Date(appointment.appointmentAt) : null;
    return date && date >= weekStart && date < weekEnd;
  }).length;
  const pickupDropCount = appointments.filter((appointment) => appointment.pickupDrop).length;
  const confirmedToday = todayAppointments.filter((appointment) =>
    appointment.status.toLowerCase() === "confirmed"
  ).length;
  const pendingToday = todayAppointments.filter((appointment) =>
    appointment.status.toLowerCase() === "pending"
  ).length;

  const handleInputChange = (e) => {
    const { name, value, type, checked } = e.target;

    setFormError("");
    setAppointmentForm((previous) => ({
      ...previous,
      [name]: type === "checkbox" ? checked : value,
    }));
  };

  const loadChoices = useCallback(async () => {
    setLoadingChoices(true);
    setChoiceError("");

    const [customerResult, vehicleResult, bayResult, advisorResult, typeResult] =
      await Promise.allSettled([
        getCustomers(),
        getVehicles(),
        getAppointmentBays(),
        getAppointmentAdvisors(),
        getAppointmentTypes(),
      ]);

    if (customerResult.status === "fulfilled" && Array.isArray(customerResult.value)) {
      setCustomers(customerResult.value);
    } else if (customerResult.status === "rejected") {
      setChoiceError(customerResult.reason?.message || "Unable to load customers.");
    }

    if (vehicleResult.status === "fulfilled" && Array.isArray(vehicleResult.value)) {
      setVehicles(vehicleResult.value);
    } else if (vehicleResult.status === "rejected") {
      setChoiceError((current) =>
        [current, vehicleResult.reason?.message || "Unable to load vehicles."]
          .filter(Boolean)
          .join(" ")
      );
    }

    if (bayResult.status === "fulfilled" && Array.isArray(bayResult.value) && bayResult.value.length > 0) {
      setBays(bayResult.value);
    }

    if (advisorResult.status === "fulfilled" && Array.isArray(advisorResult.value)) {
      setAdvisors(advisorResult.value);
    }

    if (typeResult.status === "fulfilled" && Array.isArray(typeResult.value) && typeResult.value.length > 0) {
      setAppointmentTypes(
        typeResult.value.map((type) => ({
          value: type,
          label: APPOINTMENT_TYPE_LABELS[type] || type,
        }))
      );
    }

    setLoadingChoices(false);
  }, []);

  const handleBookAppointment = async (e) => {
    e.preventDefault();

    if (
      !appointmentForm.customerId ||
      !appointmentForm.vehicleId ||
      !appointmentForm.date ||
      !appointmentForm.time ||
      !appointmentForm.serviceType
    ) {
      setFormError("Select a customer, vehicle, service, date, and time.");
      return;
    }

    setSaving(true);
    setFormError("");
    try {
      const payload = {
        customerId: Number(appointmentForm.customerId),
        vehicleId: Number(appointmentForm.vehicleId),
        serviceType: appointmentForm.serviceType,
        appointmentAt: `${appointmentForm.date}T${appointmentForm.time}:00`,
        pickupDrop: Boolean(appointmentForm.pickupDrop),
        notes: appointmentForm.notes.trim() || null,
        baySlot: appointmentForm.baySlot || null,
        advisorId: appointmentForm.advisorId ? Number(appointmentForm.advisorId) : null,
        appointmentType: appointmentForm.appointmentType || null,
        status: appointmentForm.status || "SCHEDULED",
      };

      if (editingAppointmentId) {
        await updateAppointment(editingAppointmentId, payload);
      } else {
        await createAppointment(payload);
      }

      setSelectedDay("ALL DAYS");
      setAppointmentForm(initialAppointmentForm);
      setEditingAppointmentId(null);
      setShowNewAppointment(false);
      await loadAppointments();
    } catch (error) {
      setFormError(
        error instanceof Error
          ? error.message
          : `Unable to ${editingAppointmentId ? "update" : "create"} appointment.`
      );
    } finally {
      setSaving(false);
    }
  };

  const handleEdit = async (appointment) => {
    setEditingAppointmentId(appointment.id);
    setChoiceError("");
    setFormError("");
    setShowNewAppointment(true);

    const initialDate = appointment.day || getDateKey(appointment.appointmentAt);
    const initialTime = toTimeInputValue(appointment.appointmentAt);

    setAppointmentForm({
      appointmentType: appointment.appointmentType || "WALK_IN",
      customerId: appointment.customerId ? String(appointment.customerId) : "",
      vehicleId: appointment.vehicleId ? String(appointment.vehicleId) : "",
      serviceType: appointment.serviceType || appointment.service || "Basic Service",
      date: initialDate,
      time: initialTime,
      pickupDrop: Boolean(appointment.pickupDrop),
      baySlot: appointment.baySlot || "",
      advisorId: appointment.advisorId ? String(appointment.advisorId) : "",
      status: appointment.rawStatus || "SCHEDULED",
      notes: appointment.notes || "",
    });

    await loadChoices();
  };

  const openNewAppointment = async () => {
    setEditingAppointmentId(null);
    setAppointmentForm(initialAppointmentForm);
    setShowNewAppointment(true);
    setChoiceError("");
    setFormError("");
    await loadChoices();
  };

  const vehiclesForSelectedCustomer = useMemo(
    () =>
      vehicles.filter(
        (vehicle) =>
          String(vehicle.customerId) === String(appointmentForm.customerId) ||
          (editingAppointmentId && String(vehicle.id) === String(appointmentForm.vehicleId))
      ),
    [vehicles, appointmentForm.customerId, editingAppointmentId, appointmentForm.vehicleId]
  );

  return (
    <div className="garage-appointments-content">

      {/* ================= HEADING ================= */}

      <div className="appointments-heading-row">

        <h1>APPOINTMENTS</h1>

        <button
          type="button"
          className="new-appointment-button"
          onClick={() => void openNewAppointment()}
        >
          + NEW APPOINTMENT
        </button>

      </div>


      {/* ================= STAT CARDS ================= */}

      <section className="appointment-stats">

        <div className="appointment-stat-card">

          <div className="appointment-stat-label">
            TODAY
          </div>

          <div className="appointment-stat-value">
            {todayAppointments.length}
          </div>

          <div className="appointment-stat-description">
            {confirmedToday} confirmed · {pendingToday} pending
          </div>

        </div>


        <div className="appointment-stat-card">

          <div className="appointment-stat-label">
            TOMORROW
          </div>

          <div className="appointment-stat-value lime">
            {tomorrowCount}
          </div>

        </div>


        <div className="appointment-stat-card">

          <div className="appointment-stat-label">
            THIS WEEK
          </div>

          <div className="appointment-stat-value">
            {weekCount}
          </div>

        </div>


        <div className="appointment-stat-card">

          <div className="appointment-stat-label">
            PICKUP/DROP
          </div>

          <div className="appointment-stat-value">
            {pickupDropCount}
          </div>

          <div className="appointment-stat-description">
            requiring logistics
          </div>

        </div>

      </section>

      {loadError && (
        <div className="appointment-feedback appointment-feedback-error" role="alert">
          <span>{loadError}</span>
          <button type="button" onClick={() => void loadAppointments()}>Retry</button>
        </div>
      )}

      {/* ================= DAY FILTER ================= */}

      <div className="appointment-day-filters">

        <button
          type="button"
          className={
            selectedDay === "ALL DAYS"
              ? "day-filter active"
              : "day-filter"
          }
          onClick={() => setSelectedDay("ALL DAYS")}
        >
          ALL DAYS
        </button>


        {availableDays.map((day) => (
          <button
            key={day}
            type="button"
            className={selectedDay === day ? "day-filter active" : "day-filter"}
            onClick={() => setSelectedDay(day)}
          >
            {new Intl.DateTimeFormat("en-GB", {
              day: "2-digit",
              month: "short",
            }).format(new Date(`${day}T00:00:00`)).toUpperCase()}
          </button>
        ))}

      </div>


      {/* ================= APPOINTMENT TABLE ================= */}

      <div className="appointments-table-panel">

        <div className="appointments-table-wrapper">

          <table className="appointments-table">

            <thead>

              <tr>
                <th>ID</th>
                <th>DATE &amp; TIME</th>
                <th>CUSTOMER</th>
                <th>VEHICLE</th>
                <th>SERVICE</th>
                <th>TYPE</th>
                <th>BAY</th>
                <th>PICKUP</th>
                <th>ADVISOR</th>
                <th>STATUS</th>
                <th></th>
              </tr>

            </thead>


            <tbody>

              {loading ? (
                <tr>
                  <td className="appointment-table-state" colSpan={11}>Loading appointments…</td>
                </tr>
              ) : filteredAppointments.length === 0 ? (
                <tr>
                  <td className="appointment-table-state" colSpan={11}>
                    No appointments found.
                  </td>
                </tr>
              ) : (
              filteredAppointments.map((appointment) => (

                <tr key={appointment.id}>

                  <td className="appointment-id">
                    {appointment.displayId}
                  </td>


                  <td>
                    <div className="appointment-date">
                      <strong>{appointment.date}</strong>
                      <span>{appointment.time}</span>
                    </div>
                  </td>


                  <td className="appointment-customer">
                    {appointment.customer}
                  </td>


                  <td className="appointment-vehicle">
                    {appointment.vehicle}
                  </td>


                  <td className="appointment-service">
                    {appointment.service}
                  </td>


                  <td className="appointment-type">
                    {appointment.type}
                  </td>


                  <td className="appointment-bay">
                    {appointment.bay}
                  </td>


                  <td
                    className={
                      appointment.pickup === "✓"
                        ? "appointment-pickup yes"
                        : "appointment-pickup"
                    }
                  >
                    {appointment.pickup}
                  </td>


                  <td className="appointment-advisor">
                    {appointment.advisor}
                  </td>


                  <td>

                    <span
                      className={`appointment-status ${appointment.statusClass}`}
                    >
                      {appointment.status}
                    </span>

                  </td>


                  <td>

                    <button
                      type="button"
                      className="appointment-edit-button"
                      onClick={() =>
                        handleEdit(appointment)
                      }
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

      </div>


      {/* =====================================================
          NEW APPOINTMENT MODAL
      ===================================================== */}

      {showNewAppointment && (

        <div
          className="new-appointment-overlay"
          onMouseDown={(e) => {
            if (e.target === e.currentTarget && !saving) {
              setShowNewAppointment(false);
              setEditingAppointmentId(null);
            }
          }}
        >

          <div className="new-appointment-modal">

            {/* ================= MODAL HEADER ================= */}

            <div className="new-appointment-modal-header">
              <h2>{editingAppointmentId ? "EDIT APPOINTMENT" : "NEW APPOINTMENT"}</h2>

              <button
                type="button"
                className="new-appointment-close"
                onClick={() => {
                  if (!saving) {
                    setShowNewAppointment(false);
                    setEditingAppointmentId(null);
                  }
                }}
                disabled={saving}
              >
                ×
              </button>

            </div>


            {/* ================= FORM ================= */}

            <form
              className="new-appointment-form"
              onSubmit={handleBookAppointment}
            >

              <div className="new-appointment-form-scroll">

                {choiceError && (
                  <div className="appointment-feedback appointment-feedback-error" role="alert">
                    {choiceError}
                  </div>
                )}
                {!loadingChoices && !choiceError && customers.length === 0 && (
                  <div className="appointment-feedback appointment-feedback-error" role="alert">
                    No customers are available. Create a customer before booking an appointment.
                  </div>
                )}

                <div className="appointment-form-group">
                  <label>APPOINTMENT TYPE</label>
                  <div className="appointment-type-options" role="radiogroup" aria-label="Appointment type">
                    {appointmentTypes.map((type) => (
                      <label className="appointment-type-option" key={type.value}>
                        <input
                          type="radio"
                          name="appointmentType"
                          value={type.value}
                          checked={appointmentForm.appointmentType === type.value}
                          onChange={handleInputChange}
                          disabled={saving}
                        />
                        <span>{type.label}</span>
                      </label>
                    ))}
                  </div>
                </div>

                {/* CUSTOMER */}

                <div className="appointment-form-group">

                  <label>
                    CUSTOMER
                  </label>

                  <select
                    name="customerId"
                    value={appointmentForm.customerId}
                    onChange={(event) => {
                      setFormError("");
                      setAppointmentForm((previous) => ({
                        ...previous,
                        customerId: event.target.value,
                        vehicleId: "",
                      }));
                    }}
                    required
                    disabled={saving || loadingChoices || customers.length === 0}
                  >

                    <option value="">
                      {loadingChoices
                        ? "Loading customers..."
                        : customers.length
                          ? "Select customer..."
                          : "No customers available"}
                    </option>
                    {customers.map((customer) => (
                      <option key={customer.id} value={customer.id}>
                        {customer.name || customer.phone || `Customer ${customer.id}`}
                      </option>
                    ))}
                  </select>

                </div>


                {/* VEHICLE */}

                <div className="appointment-form-group">

                  <label>
                    VEHICLE
                  </label>

                  <select
                    name="vehicleId"
                    value={appointmentForm.vehicleId}
                    onChange={handleInputChange}
                    required
                    disabled={
                      saving ||
                      loadingChoices ||
                      !appointmentForm.customerId ||
                      vehiclesForSelectedCustomer.length === 0
                    }
                  >

                    <option value="">
                      {!appointmentForm.customerId
                        ? "Select a customer first..."
                        : loadingChoices
                          ? "Loading vehicles..."
                          : vehiclesForSelectedCustomer.length
                            ? "Select vehicle..."
                            : "No vehicles for this customer"}
                    </option>
                    {vehiclesForSelectedCustomer.map((vehicle) => (
                      <option key={vehicle.id} value={vehicle.id}>
                        {[vehicle.make, vehicle.model, vehicle.registrationNo]
                          .filter(Boolean)
                          .join(" ") || `Vehicle ${vehicle.id}`}
                      </option>
                    ))}
                  </select>

                </div>


                {/* SERVICE */}

                <div className="appointment-form-group">

                  <label>
                    SERVICE REQUIRED
                  </label>

                  <select
                    name="serviceType"
                    value={appointmentForm.serviceType}
                    onChange={handleInputChange}
                    required
                    disabled={saving}
                  >
                    {appointmentForm.serviceType &&
                      !DEFAULT_SERVICE_OPTIONS.includes(appointmentForm.serviceType) && (
                        <option value={appointmentForm.serviceType}>
                          {appointmentForm.serviceType}
                        </option>
                      )}
                    {DEFAULT_SERVICE_OPTIONS.map((serviceOption) => (
                      <option key={serviceOption} value={serviceOption}>
                        {serviceOption}
                      </option>
                    ))}
                  </select>

                </div>

                {/* STATUS (EDIT MODE ONLY) */}
                {editingAppointmentId && (
                  <div className="appointment-form-group">
                    <label htmlFor="appointment-status">STATUS</label>
                    <select
                      id="appointment-status"
                      name="status"
                      value={appointmentForm.status}
                      onChange={handleInputChange}
                      disabled={saving}
                    >
                      <option value="SCHEDULED">Scheduled</option>
                      <option value="CONFIRMED">Confirmed</option>
                      <option value="IN_PROGRESS">In Progress</option>
                      <option value="COMPLETED">Completed</option>
                      <option value="CANCELLED">Cancelled</option>
                    </select>
                  </div>
                )}

                {/* DATE + TIME */}

                <div className="appointment-form-row">

                  <div className="appointment-form-group">

                    <label>
                      DATE
                    </label>

                    <input
                      type="date"
                      name="date"
                      value={appointmentForm.date}
                      onChange={handleInputChange}
                      required
                      disabled={saving}
                    />

                  </div>


                  <div className="appointment-form-group">

                    <label>
                      TIME SLOT
                    </label>

                    <input
                      type="time"
                      name="time"
                      value={appointmentForm.time}
                      onChange={handleInputChange}
                      required
                      disabled={saving}
                    />

                  </div>

                </div>


                <div className="appointment-form-row">
                  <div className="appointment-form-group">
                    <label htmlFor="appointment-bay">BAY / SLOT</label>
                    <select
                      id="appointment-bay"
                      name="baySlot"
                      value={appointmentForm.baySlot}
                      onChange={handleInputChange}
                      disabled={saving}
                    >
                      <option value="">No bay assigned</option>
                      {bays.map((bay) => (
                        <option key={bay} value={bay}>
                          {bay}
                        </option>
                      ))}
                    </select>
                  </div>
                  <div className="appointment-form-group">
                    <label htmlFor="appointment-advisor">ASSIGNED ADVISOR</label>
                    <select
                      id="appointment-advisor"
                      name="advisorId"
                      value={appointmentForm.advisorId}
                      onChange={handleInputChange}
                      disabled={saving}
                    >
                      <option value="">
                        {loadingChoices ? "Loading advisors..." : "Unassigned"}
                      </option>
                      {advisors.map((advisor) => (
                        <option key={advisor.id} value={String(advisor.id)}>
                          {advisor.name || `Advisor ${advisor.id}`}
                        </option>
                      ))}
                    </select>
                  </div>
                </div>

                {/* PICKUP / DROP */}

                <div className="appointment-form-group">

                  <label>
                    PICKUP / DROP SERVICE
                  </label>

                  <label className="pickup-checkbox">

                    <input
                      type="checkbox"
                      name="pickupDrop"
                      checked={
                        appointmentForm.pickupDrop
                      }
                      onChange={handleInputChange}
                      disabled={saving}
                    />

                    <span>
                      Customer requires pickup/drop
                      service
                    </span>

                  </label>

                </div>

                {formError && (
                  <div className="appointment-feedback appointment-feedback-error" role="alert">
                    {formError}
                  </div>
                )}

                {/* SPECIAL NOTES */}

                <div className="appointment-form-group">

                  <label>
                    SPECIAL NOTES
                  </label>

                  <textarea
                    name="notes"
                    placeholder="Any specific instructions or complaints..."
                    value={appointmentForm.notes}
                    onChange={handleInputChange}
                    maxLength={255}
                    rows="2"
                    disabled={saving}
                  ></textarea>

                </div>

              </div>


              {/* ================= MODAL FOOTER ================= */}

              <div className="new-appointment-modal-footer">

                <button
                  type="submit"
                  className="book-appointment-button"
                  disabled={
                    saving ||
                    loadingChoices ||
                    Boolean(choiceError) ||
                    customers.length === 0 ||
                    Boolean(appointmentForm.customerId && vehiclesForSelectedCustomer.length === 0)
                  }
                >
                  {saving
                    ? (editingAppointmentId ? "UPDATING..." : "BOOKING...")
                    : (editingAppointmentId ? "UPDATE APPOINTMENT" : "BOOK APPOINTMENT")}
                </button>

                <button
                  type="button"
                  className="cancel-appointment-button"
                  onClick={() => {
                    if (!saving) {
                      setShowNewAppointment(false);
                      setEditingAppointmentId(null);
                    }
                  }}
                  disabled={saving}
                >
                  Cancel
                </button>

              </div>

            </form>

          </div>

        </div>

      )}

    </div>
  );
}

export default GarageOwnerAppointments;