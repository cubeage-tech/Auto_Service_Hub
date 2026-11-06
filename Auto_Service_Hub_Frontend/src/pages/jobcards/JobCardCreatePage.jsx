import React, { useState } from "react";
import { useNavigate } from "react-router-dom";

const JOB_CARDS_STORAGE_KEY = "auto_service_hub_jobcards";
const LEGACY_JOB_CARDS_STORAGE_KEY = "smartgarage_jobcards";

function JobCardCreatePage() {
  const navigate = useNavigate();

  const [formData, setFormData] = useState({
    appointment: "Create without appointment",
    customer: "",
    vehicle: "",
    odometer: "43200",
    serviceType: "Basic Service",
    mechanic: "Auto-assign via AI",
    complaint: "",
    estimatedDelivery: "",
    estimatedCost: "0",
    technicianNotes: "",
  });

  const handleChange = (e) => {
    const { name, value } = e.target;

    setFormData((prev) => ({
      ...prev,
      [name]: value,
    }));
  };

  const closeModal = () => {
    navigate("/job-cards");
  };

  const handleSubmit = (e) => {
    e.preventDefault();

    const storedJobs =
      localStorage.getItem(JOB_CARDS_STORAGE_KEY) ??
      localStorage.getItem(LEGACY_JOB_CARDS_STORAGE_KEY);
    const existingJobs = storedJobs ? JSON.parse(storedJobs) : [];

    const newJob = {
      id: `JC-${2409 + existingJobs.length}`,

      customer: formData.customer || "New Customer",

      phone: "",

      vehicle: formData.vehicle || "Vehicle",

      service: formData.serviceType,

      mechanic:
        formData.mechanic === "Auto-assign via AI"
          ? "Ravi Kumar"
          : formData.mechanic,

      status: "Received",

      eta: formData.estimatedDelivery
        ? new Date(formData.estimatedDelivery).toLocaleString()
        : "Not specified",

      amount: formData.estimatedCost
        ? `₹${Number(formData.estimatedCost).toLocaleString("en-IN")}`
        : "₹0",

      progress: 1,

      description: formData.complaint,

      odometer: formData.odometer,

      technicianNotes: formData.technicianNotes,

      createdAt: new Date().toISOString(),
    };

    localStorage.setItem(
      JOB_CARDS_STORAGE_KEY,
      JSON.stringify([...existingJobs, newJob])
    );
    localStorage.removeItem(LEGACY_JOB_CARDS_STORAGE_KEY);

    alert("Job Card created successfully!");

    navigate("/job-cards");
  };

  return (
    <div className="job-create-overlay">

      {/* DARK BACKGROUND */}
      <div
        className="job-create-backdrop"
        onClick={closeModal}
      ></div>


      {/* MODAL */}
      <div className="job-create-modal">

        {/* HEADER */}
        <div className="job-create-modal-header">

          <h2>
            NEW JOB CARD
          </h2>

          <button
            type="button"
            className="job-create-close"
            onClick={closeModal}
          >
            ×
          </button>

        </div>


        {/* FORM */}
        <form
          className="job-create-modal-form"
          onSubmit={handleSubmit}
        >

          {/* FROM APPOINTMENT */}
          <div className="job-create-field">

            <label>
              FROM APPOINTMENT
            </label>

            <select
              name="appointment"
              value={formData.appointment}
              onChange={handleChange}
            >
              <option value="Create without appointment">
                Create without appointment
              </option>

              <option value="Appointment #1001">
                Appointment #1001
              </option>

              <option value="Appointment #1002">
                Appointment #1002
              </option>
            </select>

          </div>


          {/* CUSTOMER */}
          <div className="job-create-field">

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

              <option value="Arjun Mehta">
                Arjun Mehta
              </option>

              <option value="Priya Sharma">
                Priya Sharma
              </option>

              <option value="Rohit Desai">
                Rohit Desai
              </option>

              <option value="Neha Joshi">
                Neha Joshi
              </option>

              <option value="Vikram Singh">
                Vikram Singh
              </option>

              <option value="Kavita Rao">
                Kavita Rao
              </option>
            </select>

          </div>


          {/* VEHICLE */}
          <div className="job-create-field">

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

              <option value="MH-12-AB-4521 | Swift">
                MH-12-AB-4521 | Swift
              </option>

              <option value="DL-01-CZ-9834 | Creta">
                DL-01-CZ-9834 | Creta
              </option>

              <option value="GJ-05-XY-7712 | Innova">
                GJ-05-XY-7712 | Innova
              </option>

              <option value="MH-14-PQ-3356 | City">
                MH-14-PQ-3356 | City
              </option>
            </select>

          </div>


          {/* ODOMETER */}
          <div className="job-create-field">

            <label>
              ODOMETER READING (KM)
            </label>

            <input
              type="number"
              name="odometer"
              value={formData.odometer}
              onChange={handleChange}
              placeholder="43200"
            />

          </div>


          {/* SERVICE TYPE */}
          <div className="job-create-field">

            <label>
              SERVICE TYPE
            </label>

            <select
              name="serviceType"
              value={formData.serviceType}
              onChange={handleChange}
            >
              <option value="Basic Service">
                Basic Service
              </option>

              <option value="Full Service">
                Full Service
              </option>

              <option value="Engine Overhaul">
                Engine Overhaul
              </option>

              <option value="AC Repair + Service">
                AC Repair + Service
              </option>

              <option value="Brake Replacement">
                Brake Replacement
              </option>

              <option value="Suspension + Tyres">
                Suspension + Tyres
              </option>

              <option value="Electrical">
                Electrical
              </option>

              <option value="Tyre Replacement">
                Tyre Replacement
              </option>
            </select>

          </div>


          {/* ASSIGN MECHANIC */}
          <div className="job-create-field">

            <label>
              ASSIGN MECHANIC
            </label>

            <select
              name="mechanic"
              value={formData.mechanic}
              onChange={handleChange}
            >
              <option value="Auto-assign via AI">
                Auto-assign via AI
              </option>

              <option value="Ravi Kumar">
                Ravi Kumar
              </option>

              <option value="Amit Patel">
                Amit Patel
              </option>

              <option value="Suresh Nair">
                Suresh Nair
              </option>

              <option value="Deepak Verma">
                Deepak Verma
              </option>

              <option value="Kiran Joshi">
                Kiran Joshi
              </option>
            </select>

          </div>


          {/* CUSTOMER COMPLAINT */}
          <div className="job-create-field">

            <label>
              CUSTOMER COMPLAINT
            </label>

            <textarea
              name="complaint"
              value={formData.complaint}
              onChange={handleChange}
              placeholder="Describe the customer's complaint in detail..."
              rows="3"
            />

          </div>


          {/* ESTIMATED DELIVERY */}
          <div className="job-create-field">

            <label>
              ESTIMATED DELIVERY
            </label>

            <input
              type="datetime-local"
              name="estimatedDelivery"
              value={formData.estimatedDelivery}
              onChange={handleChange}
            />

          </div>


          {/* ESTIMATED COST */}
          <div className="job-create-field">

            <label>
              ESTIMATED COST (₹)
            </label>

            <input
              type="number"
              name="estimatedCost"
              value={formData.estimatedCost}
              onChange={handleChange}
              placeholder="0"
              min="0"
            />

          </div>


          {/* TECHNICIAN NOTES */}
          <div className="job-create-field">

            <label>
              TECHNICIAN NOTES
            </label>

            <textarea
              name="technicianNotes"
              value={formData.technicianNotes}
              onChange={handleChange}
              placeholder="Initial technician observations..."
              rows="3"
            />

          </div>


          {/* BUTTONS */}
          <div className="job-create-actions">

            <button
              type="submit"
              className="job-create-submit"
            >
              CREATE JOB CARD
            </button>

            <button
              type="button"
              className="job-create-cancel"
              onClick={closeModal}
            >
              Cancel
            </button>

          </div>

        </form>

      </div>

    </div>
  );
}

export default JobCardCreatePage;
