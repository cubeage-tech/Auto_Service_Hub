import React, { useEffect, useMemo, useState } from "react";

import {
  ASSIGNED_STAFF,
  FOLLOW_UP_CHANNELS,
  FOLLOW_UP_STATUSES,
  FOLLOW_UP_TYPES,
  TODAY_KEY,
} from "./followUpDemoData";
import { describeDueDate } from "./followUpUtils";

/* =========================================================
   EMPTY FORM
   ========================================================= */

const emptyForm = () => ({
  customer: "",
  vehicle: "",
  type: FOLLOW_UP_TYPES[0],
  dueDate: TODAY_KEY,
  channel: FOLLOW_UP_CHANNELS[0],
  assignedTo: "Unassigned",
  status: "Pending",
  notes: "",
});

/* =========================================================
   SCHEDULE FOLLOW-UP MODAL
   Validation runs here so an invalid entry never reaches page
   state. onSave receives the finished values and returns ""
   when the record was stored, or a message to display when it
   was not.

   Nothing is transmitted: the record is added to the
   demonstration list held in component state only.
   ========================================================= */

function ScheduleFollowUpModal({
  open,
  customers,
  onClose,
  onSave,
  suggestedId,
}) {
  const [form, setForm] = useState(emptyForm);
  const [errors, setErrors] = useState({});
  const [saveError, setSaveError] = useState("");

  /* Reopening the modal always starts from a clean form. */
  useEffect(() => {
    if (open) {
      setForm(emptyForm());
      setErrors({});
      setSaveError("");
    }
  }, [open]);

  const vehiclesForCustomer = useMemo(() => {
    const match = customers.find((entry) => entry.customer === form.customer);
    return match ? match.vehicles : [];
  }, [customers, form.customer]);

  const dueNote = useMemo(
    () => describeDueDate(form.dueDate, TODAY_KEY),
    [form.dueDate]
  );

  if (!open) {
    return null;
  }

  const handleChange = (event) => {
    const { name, value } = event.target;

    setSaveError("");
    setErrors((previous) => {
      if (!previous[name]) {
        return previous;
      }
      const next = { ...previous };
      delete next[name];
      return next;
    });

    setForm((previous) => {
      if (name === "customer") {
        /* The vehicle list belongs to the customer, so a new
           customer always clears the previous selection. */
        return { ...previous, customer: value, vehicle: "" };
      }
      return { ...previous, [name]: value };
    });
  };

  const validate = () => {
    const nextErrors = {};

    if (!form.customer) {
      nextErrors.customer = "Select the customer this follow-up belongs to.";
    }

    if (!form.vehicle) {
      nextErrors.vehicle = "Select the customer's vehicle.";
    }

    if (!form.dueDate) {
      nextErrors.dueDate = "Choose the date the follow-up is due.";
    } else if (form.dueDate < TODAY_KEY) {
      nextErrors.dueDate = "The due date cannot be earlier than today.";
    }

    if (!form.type) {
      nextErrors.type = "Choose the follow-up type.";
    }

    if (!form.channel) {
      nextErrors.channel = "Choose the communication channel.";
    }

    return nextErrors;
  };

  const handleSubmit = (event) => {
    event.preventDefault();

    const nextErrors = validate();

    if (Object.keys(nextErrors).length > 0) {
      setErrors(nextErrors);
      setSaveError("Complete the highlighted fields before saving.");
      return;
    }

    const message = onSave(form);

    if (message) {
      setSaveError(message);
    }
  };

  return (
    <div
      className="followup-modal-overlay"
      onMouseDown={(event) => {
        if (event.target === event.currentTarget) {
          onClose();
        }
      }}
    >
      <div className="followup-modal">

        <div className="followup-modal-header">
          <h2>SCHEDULE FOLLOW-UP</h2>

          <button
            type="button"
            className="followup-modal-close"
            onClick={onClose}
            aria-label="Close"
          >
            ×
          </button>
        </div>

        <form className="followup-modal-form" onSubmit={handleSubmit}>

          <div className="followup-form-scroll">

            <p className="followup-form-note">
              Demonstration form — the new record is added to this screen only
              and is never saved to the backend.
              {suggestedId ? ` It will be listed as ${suggestedId}.` : ""}
            </p>

            {saveError && (
              <div
                className="followup-feedback followup-feedback-error"
                role="alert"
              >
                {saveError}
              </div>
            )}

            <div className="followup-form-row">

              <div className="followup-form-group">
                <label htmlFor="followup-customer">CUSTOMER</label>

                <select
                  id="followup-customer"
                  name="customer"
                  value={form.customer}
                  onChange={handleChange}
                >
                  <option value="">Select customer</option>

                  {customers.map((entry) => (
                    <option value={entry.customer} key={entry.customer}>
                      {entry.customer}
                    </option>
                  ))}
                </select>

                {errors.customer && (
                  <span className="followup-field-error">
                    {errors.customer}
                  </span>
                )}
              </div>

              <div className="followup-form-group">
                <label htmlFor="followup-vehicle">
                  VEHICLE / REGISTRATION
                </label>

                <select
                  id="followup-vehicle"
                  name="vehicle"
                  value={form.vehicle}
                  onChange={handleChange}
                  disabled={!form.customer || vehiclesForCustomer.length === 0}
                >
                  <option value="">
                    {form.customer ? "Select vehicle" : "Select customer first"}
                  </option>

                  {vehiclesForCustomer.map((entry) => (
                    <option value={entry.registration} key={entry.registration}>
                      {entry.vehicle} — {entry.registration}
                    </option>
                  ))}
                </select>

                {errors.vehicle && (
                  <span className="followup-field-error">{errors.vehicle}</span>
                )}
              </div>

            </div>


            <div className="followup-form-row">

              <div className="followup-form-group">
                <label htmlFor="followup-type">FOLLOW-UP TYPE</label>

                <select
                  id="followup-type"
                  name="type"
                  value={form.type}
                  onChange={handleChange}
                >
                  {FOLLOW_UP_TYPES.map((type) => (
                    <option value={type} key={type}>
                      {type}
                    </option>
                  ))}
                </select>

                {errors.type && (
                  <span className="followup-field-error">{errors.type}</span>
                )}
              </div>

              <div className="followup-form-group">
                <label htmlFor="followup-due-date">FOLLOW-UP DATE</label>

                <input
                  id="followup-due-date"
                  type="date"
                  name="dueDate"
                  value={form.dueDate}
                  min={TODAY_KEY}
                  onChange={handleChange}
                />

                {errors.dueDate ? (
                  <span className="followup-field-error">{errors.dueDate}</span>
                ) : (
                  <span className={`followup-field-hint ${dueNote.tone}`}>
                    {dueNote.label}
                  </span>
                )}
              </div>

            </div>


            <div className="followup-form-row">

              <div className="followup-form-group">
                <label htmlFor="followup-channel">CHANNEL</label>

                <select
                  id="followup-channel"
                  name="channel"
                  value={form.channel}
                  onChange={handleChange}
                >
                  {FOLLOW_UP_CHANNELS.map((channel) => (
                    <option value={channel} key={channel}>
                      {channel}
                    </option>
                  ))}
                </select>

                {errors.channel && (
                  <span className="followup-field-error">{errors.channel}</span>
                )}
              </div>

              <div className="followup-form-group">
                <label htmlFor="followup-assigned">ASSIGNED TO</label>

                <select
                  id="followup-assigned"
                  name="assignedTo"
                  value={form.assignedTo}
                  onChange={handleChange}
                >
                  {ASSIGNED_STAFF.map((staff) => (
                    <option value={staff} key={staff}>
                      {staff}
                    </option>
                  ))}
                </select>
              </div>

            </div>


            <div className="followup-form-group">
              <label htmlFor="followup-status">STATUS</label>

              <select
                id="followup-status"
                name="status"
                value={form.status}
                onChange={handleChange}
              >
                {FOLLOW_UP_STATUSES.map((status) => (
                  <option value={status} key={status}>
                    {status}
                  </option>
                ))}
              </select>

              <span className="followup-field-hint">
                &quot;Overdue&quot; is worked out from the follow-up date, so it
                is not stored on the record.
              </span>
            </div>


            <div className="followup-form-group">
              <label htmlFor="followup-notes">MESSAGE / INTERNAL NOTES</label>

              <textarea
                id="followup-notes"
                name="notes"
                value={form.notes}
                onChange={handleChange}
                maxLength={500}
                rows="3"
                placeholder="What should the reminder cover?"
              />

              <span className="followup-field-hint">
                {form.notes.length} / 500 characters
              </span>
            </div>

          </div>

          <div className="followup-modal-divider"></div>

          <div className="followup-modal-actions">

            <button type="submit" className="followup-save-button">
              SAVE FOLLOW-UP
            </button>

            <button
              type="button"
              className="followup-cancel-button"
              onClick={onClose}
            >
              Cancel
            </button>

          </div>

        </form>

      </div>
    </div>
  );
}

export default ScheduleFollowUpModal;

