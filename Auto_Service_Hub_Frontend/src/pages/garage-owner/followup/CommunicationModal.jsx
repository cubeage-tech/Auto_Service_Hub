import React, { useEffect, useMemo, useState } from "react";

import {
  DEMO_SEND_NOTICE,
  FOLLOW_UP_CHANNELS,
  MESSAGE_TEMPLATES,
} from "./followUpDemoData";
import {
  communicationStatusClassName,
  describeDueDate,
  formatDisplayDate,
  statusClassName,
} from "./followUpUtils";

/* =========================================================
   MESSAGE TEMPLATE PICKER
   ========================================================= */

const templateForType = (type) => {
  switch (type) {
    case "Feedback Request":
      return MESSAGE_TEMPLATES.feedback;
    case "Payment Reminder":
      return MESSAGE_TEMPLATES.payment;
    case "Insurance Renewal":
      return MESSAGE_TEMPLATES.renewal;
    default:
      return MESSAGE_TEMPLATES.reminder;
  }
};

const fillTemplate = (template, followUp) =>
  template
    .replaceAll("{customer}", followUp?.customer || "customer")
    .replaceAll("{vehicle}", followUp?.vehicle || "vehicle")
    .replaceAll("{registration}", followUp?.registration || "registration");

/* =========================================================
   COMMUNICATION PANEL
   Shows the conversation planner and the recorded contact
   history for one follow-up.

   IMPORTANT: "Send" is a simulation. The entry it creates is
   stored in page state only — no SMS, WhatsApp, e-mail or call
   is placed and nothing is written to the backend.
   ========================================================= */

function CommunicationModal({ open, followUp, history, onClose, onSend }) {
  const [channel, setChannel] = useState("");
  const [message, setMessage] = useState("");
  const [notice, setNotice] = useState("");

  /* A different follow-up always starts a fresh draft. */
  useEffect(() => {
    if (open && followUp) {
      setChannel(followUp.channel);
      setMessage(fillTemplate(templateForType(followUp.type), followUp));
      setNotice("");
    }
  }, [open, followUp]);

  const due = useMemo(
    () => (followUp ? describeDueDate(followUp.dueDate) : null),
    [followUp]
  );

  if (!open || !followUp) {
    return null;
  }

  const handleSend = () => {
    if (!message.trim()) {
      setNotice("Write a message before recording it as sent.");
      return;
    }

    onSend(followUp, { channel, message: message.trim() });
    setNotice(
      `Recorded as sent to ${followUp.customer} over ${channel} — simulated only.`
    );
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
      <div className="followup-modal followup-modal-wide">

        <div className="followup-modal-header">
          <h2>CUSTOMER COMMUNICATION</h2>

          <button
            type="button"
            className="followup-modal-close"
            onClick={onClose}
            aria-label="Close"
          >
            ×
          </button>
        </div>

        <div className="followup-modal-body">

          {/* =============================================
              CUSTOMER SUMMARY
          ============================================= */}

          <div className="followup-comm-summary">

            <div className="followup-comm-identity">
              <strong>{followUp.customer}</strong>
              <span>{followUp.phone}</span>
            </div>

            <div className="followup-comm-detail">
              <span>VEHICLE</span>
              <strong>
                {followUp.vehicle} — {followUp.registration}
              </strong>
            </div>

            <div className="followup-comm-detail">
              <span>FOLLOW-UP</span>
              <strong>{followUp.type}</strong>
            </div>

            <div className="followup-comm-detail">
              <span>DUE</span>
              <strong>
                {formatDisplayDate(followUp.dueDate)}
                {due ? ` · ${due.label}` : ""}
              </strong>
            </div>

            <div className="followup-comm-detail">
              <span>ASSIGNED TO</span>
              <strong>{followUp.assignedTo}</strong>
            </div>

            <div className="followup-comm-detail">
              <span>STATUS</span>
              <strong>
                <span
                  className={`followup-status ${statusClassName(
                    followUp.status
                  )}`}
                >
                  {followUp.status}
                </span>
              </strong>
            </div>

          </div>


          <p className="followup-form-note">{DEMO_SEND_NOTICE}</p>

          {notice && (
            <div
              className="followup-feedback followup-feedback-success"
              role="status"
            >
              {notice}
            </div>
          )}


          {/* =============================================
              DRAFT MESSAGE
          ============================================= */}

          <div className="followup-form-group">
            <label htmlFor="followup-comm-channel">CHANNEL</label>

            <select
              id="followup-comm-channel"
              value={channel}
              onChange={(event) => setChannel(event.target.value)}
            >
              {FOLLOW_UP_CHANNELS.map((entry) => (
                <option value={entry} key={entry}>
                  {entry}
                </option>
              ))}
            </select>
          </div>

          <div className="followup-form-group">
            <label htmlFor="followup-comm-message">MESSAGE</label>

            <textarea
              id="followup-comm-message"
              value={message}
              rows="5"
              maxLength={1000}
              onChange={(event) => setMessage(event.target.value)}
            />

            <span className="followup-field-hint">
              {message.length} / 1000 characters
            </span>
          </div>

          <div className="followup-comm-actions">

            <button
              type="button"
              className="followup-save-button"
              onClick={handleSend}
            >
              RECORD AS SENT (SIMULATED)
            </button>

            <button
              type="button"
              className="followup-cancel-button"
              onClick={() =>
                setMessage(
                  fillTemplate(templateForType(followUp.type), followUp)
                )
              }
            >
              Reset message
            </button>

          </div>


          {/* =============================================
              COMMUNICATION HISTORY
          ============================================= */}

          <div className="followup-comm-history">

            <h3>
              COMMUNICATION HISTORY
              <span>{history.length} RECORDS</span>
            </h3>

            {history.length === 0 ? (
              <p className="followup-comm-empty">
                No communication has been recorded for this follow-up yet.
              </p>
            ) : (
              <ul className="followup-history-list">
                {history.map((entry) => (
                  <li className="followup-history-row" key={entry.id}>

                    <div className="followup-history-main">
                      <strong>{entry.channel}</strong>
                      <span>{formatDisplayDate(entry.at)}</span>
                    </div>

                    <p className="followup-history-note">{entry.note}</p>

                    <span
                      className={`followup-comm-status ${communicationStatusClassName(
                        entry.status
                      )}`}
                    >
                      {entry.status}
                    </span>

                  </li>
                ))}
              </ul>
            )}

          </div>

        </div>

      </div>
    </div>
  );
}

export default CommunicationModal;
