import React, { useEffect, useState } from "react";
import { Link } from "react-router-dom";
import { appointmentsApi, customersApi, jobCardsApi, mechanicsApi, vehiclesApi } from "../../services/resources";
import { getErrorMessage } from "../../services/api";
import "./GarageOwnerJobCards.css";

function GarageOwnerJobCards({
  allowCreate = true,
  showAppointment = false,
  openCreateOnMount = false,
}) {
  const [activeFilter, setActiveFilter] = useState("ALL");
  const [showModal, setShowModal] = useState(openCreateOnMount);
  const [jobCards, setJobCards] = useState([]);
  const [customers, setCustomers] = useState([]);
  const [vehicles, setVehicles] = useState([]);
  const [mechanics, setMechanics] = useState([]);
  const [appointments, setAppointments] = useState([]);
  const [loading, setLoading] = useState(true);
  const [saving, setSaving] = useState(false);
  const [error, setError] = useState("");

  const [formData, setFormData] = useState({
    appointment: "",
    customer: "",
    vehicle: "",
    odometer: "",
    serviceType: "Basic Service",
    mechanic: "",
    complaint: "",
    delivery: "",
    cost: "",
    notes: "",
  });

  useEffect(() => {
    Promise.all([
      jobCardsApi.list(),
      customersApi.list(),
      vehiclesApi.list(),
      mechanicsApi.list(),
      showAppointment ? appointmentsApi.list() : Promise.resolve([]),
    ])
      .then(([jobCardRecords, customerRecords, vehicleRecords, mechanicRecords, appointmentRecords]) => {
        setJobCards(jobCardRecords);
        setCustomers(customerRecords);
        setVehicles(vehicleRecords);
        setMechanics(mechanicRecords);
        setAppointments(appointmentRecords);
      })
      .catch((requestError) => setError(getErrorMessage(requestError)))
      .finally(() => setLoading(false));
  }, [showAppointment]);

  const filterMap = {
    RECEIVED: "RECEIVED",
    INSPECTION: "INSPECTION",
    REPAIR: "IN_REPAIR",
    QC: "QUALITY_CHECK",
    DELIVERED: "DELIVERED",
  };

  const filteredJobs =
    activeFilter === "ALL"
      ? jobCards
      : jobCards.filter(
          (job) => job.status === filterMap[activeFilter]
        );
  const statusLabels = {
    RECEIVED: "Received",
    INSPECTION: "Inspection",
    IN_REPAIR: "In Repair",
    QUALITY_CHECK: "Quality Check",
    DELIVERED: "Delivered",
  };
  const statusClasses = {
    RECEIVED: "received",
    INSPECTION: "inspection",
    IN_REPAIR: "repair",
    QUALITY_CHECK: "quality",
    DELIVERED: "delivered",
  };
  const todayKey = new Date().toLocaleDateString("en-CA");
  const overdueCount = jobCards.filter((job) =>
    job.status !== "DELIVERED" &&
    job.estimatedDelivery &&
    new Date(job.estimatedDelivery) < new Date()
  ).length;

  const handleChange = (e) => {
    const { name, value } = e.target;

    if (name === "appointment") {
      const appointment = appointments.find((item) => String(item.id) === value);
      setFormData((previous) => ({
        ...previous,
        appointment: value,
        customer: appointment ? String(appointment.customerId) : "",
        vehicle: appointment ? String(appointment.vehicleId) : "",
      }));
      return;
    }

    setFormData((previous) => ({
      ...previous,
      [name]: value,
      ...(["customer", "vehicle"].includes(name) ? { appointment: "" } : {}),
      ...(name === "customer" ? { vehicle: "" } : {}),
    }));
  };

  const handleCreateJobCard = async (e) => {
    e.preventDefault();

    if (!formData.customer || !formData.vehicle) {
      alert("Please select a customer and vehicle.");
      return;
    }

    setError("");
    setSaving(true);
    try {
      const created = await jobCardsApi.create({
        customerId: Number(formData.customer),
        vehicleId: Number(formData.vehicle),
        appointmentId: formData.appointment ? Number(formData.appointment) : null,
        mechanicId: formData.mechanic ? Number(formData.mechanic) : null,
        serviceType: formData.serviceType,
        complaint: formData.complaint,
        technicianNotes: formData.notes,
        odometerReading: formData.odometer ? Number(formData.odometer) : null,
        estimatedDelivery: formData.delivery || null,
        estimatedCost: formData.cost ? Number(formData.cost) : null,
        status: "RECEIVED",
      });
      setJobCards((existing) => [created, ...existing]);
      setShowModal(false);
      setFormData({
        appointment: "",
        customer: "",
        vehicle: "",
        odometer: "",
        serviceType: "Basic Service",
        mechanic: "",
        complaint: "",
        delivery: "",
        cost: "",
        notes: "",
      });
    } catch (requestError) {
      setError(getErrorMessage(requestError, "Could not create job card."));
    } finally {
      setSaving(false);
    }
  };

  return (
    <div className="garage-jobcards-content">

      {/* =====================================================
          PAGE HEADING
      ===================================================== */}

      <div className="jobcards-heading-row">

        <h1>JOB CARDS</h1>

        {allowCreate && <button
          className="new-jobcard-button"
          onClick={() => setShowModal(true)}
        >
          + NEW JOB CARD
        </button>}

      </div>


      {/* =====================================================
          STAT CARDS
      ===================================================== */}

      <section className="jobcard-stats">

        <div className="jobcard-stat-card">
          <div className="jobcard-stat-label">
            TOTAL TODAY
          </div>

          <div className="jobcard-stat-value">
            {jobCards.filter((job) => job.assignedDate?.slice(0, 10) === todayKey).length}
          </div>
        </div>


        <div className="jobcard-stat-card">
          <div className="jobcard-stat-label">
            IN REPAIR
          </div>

          <div className="jobcard-stat-value lime">
            {jobCards.filter((job) => job.status === "IN_REPAIR").length}
          </div>
        </div>


        <div className="jobcard-stat-card">
          <div className="jobcard-stat-label">
            QC
          </div>

          <div className="jobcard-stat-value">
            {jobCards.filter((job) => job.status === "QUALITY_CHECK").length}
          </div>
        </div>


        <div className="jobcard-stat-card">
          <div className="jobcard-stat-label">
            DELIVERED
          </div>

          <div className="jobcard-stat-value">
            {jobCards.filter((job) => job.status === "DELIVERED").length}
          </div>
        </div>


        <div className="jobcard-stat-card">
          <div className="jobcard-stat-label">
            OVERDUE
          </div>

          <div className="jobcard-stat-value">
            {overdueCount}
          </div>
        </div>

      </section>


      {/* =====================================================
          FILTERS
      ===================================================== */}

      <div className="jobcard-filters">

        {[
          "ALL",
          "RECEIVED",
          "INSPECTION",
          "REPAIR",
          "QC",
          "DELIVERED",
        ].map((filter) => (

          <button
            key={filter}
            className={
              activeFilter === filter
                ? "jobcard-filter active"
                : "jobcard-filter"
            }
            onClick={() => setActiveFilter(filter)}
          >
            {filter}
          </button>

        ))}

      </div>


      {/* =====================================================
          JOB CARDS LIST
      ===================================================== */}

      <div className="jobcards-list">

        {loading && <div className="no-jobcards">Loading job cards…</div>}
        {!loading && error && <div className="no-jobcards" role="alert">{error}</div>}
        {filteredJobs.map((job) => (

          <div
            className="jobcard-item"
            key={job.id}
          >

            {/* TOP SECTION */}

            <div className="jobcard-top">

              {/* LEFT */}

              <div className="jobcard-left">

                <div className="jobcard-id">
                  {job.jobCardNumber}
                </div>

                <div className="jobcard-customer">
                  {job.customerName}
                </div>

                <div className="jobcard-vehicle">
                  {job.vehicleInfo}
                </div>

              </div>


              {/* SERVICE */}

              <div className="jobcard-service">

                <span>
                  Service
                </span>

                <strong>
                  {job.serviceType}
                </strong>

                <small>
                  Mechanic: {job.mechanicName || "Unassigned"}
                </small>

              </div>


              {/* RIGHT */}

              <div className="jobcard-right">

                <span
                  className={`jobcard-status ${statusClasses[job.status] || ""}`}
                >
                  {statusLabels[job.status] || job.status}
                </span>

                <div className="jobcard-eta">
                  {job.estimatedDelivery
                    ? new Date(job.estimatedDelivery).toLocaleString("en-IN", { dateStyle: "medium", timeStyle: "short" })
                    : "No delivery estimate"}
                </div>

                <div className="jobcard-amount">
                  {job.estimatedCost == null ? "—" : `₹${Number(job.estimatedCost).toLocaleString("en-IN")}`}
                </div>

                <Link
                  to={`/job-cards/${job.id}`}
                  className="jobcard-details"
                >
                  Details
                </Link>

              </div>

            </div>


            {/* PROGRESS */}

            <div className="jobcard-progress">

              <div className="progress-track">

                <div
                  className="progress-fill"
                  style={{
                    width: `${Math.min(100, Math.max(0, (Number(job.progress) || 0) * 20))}%`,
                  }}
                ></div>

              </div>


              <div className="progress-labels">

                <span>
                  Received
                </span>

                <span>
                  Inspection
                </span>

                <span>
                  In Repair
                </span>

                <span>
                  QC
                </span>

                <span>
                  Delivered
                </span>

              </div>

            </div>

          </div>

        ))}


        {!loading && !error && filteredJobs.length === 0 && (
          <div className="no-jobcards">
            No job cards found.
          </div>
        )}

      </div>


      {/* =====================================================
          NEW JOB CARD MODAL
      ===================================================== */}

      {allowCreate && showModal && (

        <div
          className="jobcard-modal-overlay"
          onMouseDown={(e) => {
            if (e.target === e.currentTarget) {
              setShowModal(false);
            }
          }}
        >

          <div className="jobcard-modal">

            {/* MODAL HEADER */}

            <div className="jobcard-modal-header">

              <h2>
                NEW JOB CARD
              </h2>

              <button
                type="button"
                className="jobcard-modal-close"
                onClick={() => setShowModal(false)}
              >
                ×
              </button>

            </div>


            {/* FORM */}

            <form
              className="jobcard-form"
              onSubmit={handleCreateJobCard}
            >
              {error && <p role="alert" className="login-error">{error}</p>}

              {showAppointment && (
                <div className="jobcard-form-group">
                  <label htmlFor="jobcard-appointment">FROM APPOINTMENT</label>
                  <select
                    id="jobcard-appointment"
                    name="appointment"
                    value={formData.appointment}
                    onChange={handleChange}
                  >
                    <option value="">Create without appointment</option>
                    {appointments.map((appointment) => (
                      <option key={appointment.id} value={appointment.id}>
                        Appointment #{appointment.id} — {appointment.customerName}
                        {appointment.appointmentAt
                          ? ` (${new Date(appointment.appointmentAt).toLocaleString()})`
                          : ""}
                      </option>
                    ))}
                  </select>
                </div>
              )}

              {/* CUSTOMER */}

              <div className="jobcard-form-group">

                <label>
                  CUSTOMER
                </label>

                <select
                  name="customer"
                  value={formData.customer}
                  onChange={handleChange}
                  required
                >

                  <option value="">
                    Select customer...
                  </option>

                  {customers.map((customer) => (
                    <option key={customer.id} value={customer.id}>{customer.name} — {customer.phone}</option>
                  ))}

                </select>

              </div>


              {/* VEHICLE */}

              <div className="jobcard-form-group">

                <label>
                  VEHICLE
                </label>

                <select
                  name="vehicle"
                  value={formData.vehicle}
                  onChange={handleChange}
                  required
                >

                  <option value="">
                    Select vehicle...
                  </option>

                  {vehicles
                    .filter((vehicle) => !formData.customer || vehicle.customerId === Number(formData.customer))
                    .map((vehicle) => (
                      <option key={vehicle.id} value={vehicle.id}>
                        {vehicle.registrationNo} — {vehicle.make} {vehicle.model}
                      </option>
                    ))}

                </select>

              </div>


              {/* ODOMETER */}

              <div className="jobcard-form-group">

                <label>
                  ODOMETER READING (KM)
                </label>

                <input
                  type="number"
                  name="odometer"
                  value={formData.odometer}
                  onChange={handleChange}
                />

              </div>


              {/* SERVICE */}

              <div className="jobcard-form-group">

                <label>
                  SERVICE TYPE
                </label>

                <select
                  name="serviceType"
                  value={formData.serviceType}
                  onChange={handleChange}
                >

                  <option>
                    Basic Service
                  </option>

                  <option>
                    Full Service
                  </option>

                  <option>
                    Engine Overhaul
                  </option>

                  <option>
                    AC Service
                  </option>

                  <option>
                    Brake Replacement
                  </option>

                  <option>
                    Battery Replacement
                  </option>

                  <option>
                    Suspension & Tyres
                  </option>

                </select>

              </div>


              {/* MECHANIC */}

              <div className="jobcard-form-group">

                <label>
                  ASSIGN MECHANIC
                </label>

                <select
                  name="mechanic"
                  value={formData.mechanic}
                  onChange={handleChange}
                >

                  <option value="">Unassigned</option>
                  {mechanics.map((mechanic) => (
                    <option key={mechanic.id} value={mechanic.id}>{mechanic.name}</option>
                  ))}

                </select>

              </div>


              {/* CUSTOMER COMPLAINT */}

              <div className="jobcard-form-group">

                <label>
                  CUSTOMER COMPLAINT
                </label>

                <textarea
                  name="complaint"
                  value={formData.complaint}
                  onChange={handleChange}
                  placeholder="Describe the customer's complaint in detail..."
                  rows="3"
                ></textarea>

              </div>


              {/* DELIVERY */}

              <div className="jobcard-form-group">

                <label>
                  ESTIMATED DELIVERY
                </label>

                <input
                  type="datetime-local"
                  name="delivery"
                  value={formData.delivery}
                  onChange={handleChange}
                />

              </div>


              {/* COST */}

              <div className="jobcard-form-group">

                <label>
                  ESTIMATED COST (₹)
                </label>

                <input
                  type="number"
                  name="cost"
                  value={formData.cost}
                  onChange={handleChange}
                />

              </div>


              {/* NOTES */}

              <div className="jobcard-form-group">

                <label>
                  TECHNICIAN NOTES
                </label>

                <textarea
                  name="notes"
                  value={formData.notes}
                  onChange={handleChange}
                  placeholder="Initial technician observations..."
                  rows="3"
                ></textarea>

              </div>


              {/* FOOTER */}

              <div className="jobcard-modal-footer">

                <button
                  type="submit"
                  className="create-jobcard-button"
                  disabled={saving}
                >
                  {saving ? "SAVING..." : "CREATE JOB CARD"}
                </button>

                <button
                  type="button"
                  className="cancel-jobcard-button"
                  onClick={() => setShowModal(false)}
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

export default GarageOwnerJobCards;
