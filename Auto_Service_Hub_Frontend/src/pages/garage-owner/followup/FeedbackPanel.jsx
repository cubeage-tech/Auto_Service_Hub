import React, { useState } from "react";

import { formatDisplayDate, toStars } from "./followUpUtils";

/* =========================================================
   FEEDBACK CARD
   One customer's rating, comment and response state. The reply
   box only edits demonstration state: no e-mail or message is
   sent when a reply is saved.
   ========================================================= */

function FeedbackCard({ entry, onRespond }) {
  const [replyOpen, setReplyOpen] = useState(false);
  const [reply, setReply] = useState("");
  const [replyError, setReplyError] = useState("");

  const handleSaveReply = () => {
    const trimmed = reply.trim();

    if (trimmed.length < 5) {
      setReplyError("Write at least 5 characters before saving the reply.");
      return;
    }

    onRespond(entry.id, trimmed);
    setReply("");
    setReplyError("");
    setReplyOpen(false);
  };

  return (
    <article className="followup-feedback-card">

      <header className="followup-feedback-head">
        <div className="followup-feedback-who">
          <strong>{entry.customer}</strong>
          <span>
            {entry.vehicle} — {entry.registration}
          </span>
        </div>

        <div className="followup-feedback-rating">
          <span
            className="followup-stars"
            aria-label={`${entry.rating} out of 5 stars`}
          >
            {toStars(entry.rating).map((filled, index) => (
              <i key={index} className={filled ? "filled" : ""}>
                {filled ? "★" : "☆"}
              </i>
            ))}
          </span>
          <strong>{entry.rating}.0</strong>
        </div>
      </header>

      <div className="followup-feedback-meta">
        <span>{entry.service}</span>
        <span>{formatDisplayDate(entry.date)}</span>
        <span
          className={
            entry.responded
              ? "followup-response-flag responded"
              : "followup-response-flag awaiting"
          }
        >
          {entry.responded ? "Responded" : "Awaiting response"}
        </span>
      </div>

      <p className="followup-feedback-comment">“{entry.comment}”</p>

      {entry.responded && entry.response && (
        <div className="followup-feedback-reply">
          <span>WORKSHOP REPLY</span>
          <p>{entry.response}</p>
        </div>
      )}

      {!entry.responded && replyOpen && (
        <div className="followup-feedback-reply-form">

          <textarea
            value={reply}
            rows="3"
            maxLength={400}
            placeholder="Reply to this customer..."
            onChange={(event) => {
              setReply(event.target.value);
              setReplyError("");
            }}
          />

          {replyError && (
            <span className="followup-field-error">{replyError}</span>
          )}

          <div className="followup-feedback-reply-actions">
            <button
              type="button"
              className="followup-action primary"
              onClick={handleSaveReply}
            >
              Save reply
            </button>

            <button
              type="button"
              className="followup-action"
              onClick={() => {
                setReplyOpen(false);
                setReply("");
                setReplyError("");
              }}
            >
              Cancel
            </button>
          </div>

        </div>
      )}

      {!entry.responded && !replyOpen && (
        <button
          type="button"
          className="followup-action primary followup-feedback-reply-open"
          onClick={() => setReplyOpen(true)}
        >
          Add reply
        </button>
      )}

    </article>
  );
}

/* =========================================================
   FEEDBACK PANEL
   ========================================================= */

function FeedbackPanel({ feedback, averageRatingValue, respondedCount, onRespond }) {
  return (
    <section className="followup-section" aria-label="Customer feedback">

      <div className="followup-section-head">
        <div>
          <h2>CUSTOMER FEEDBACK</h2>
          <p>
            Ratings and comments captured after recent services. Replies are
            demonstration-only and are not delivered.
          </p>
        </div>

        <div className="followup-section-metrics">
          <span>
            <strong>{feedback.length}</strong>
            RECEIVED
          </span>
          <span>
            <strong>{averageRatingValue.toFixed(1)}</strong>
            AVG RATING
          </span>
          <span>
            <strong>{respondedCount}</strong>
            REPLIED
          </span>
        </div>
      </div>

      {feedback.length === 0 ? (
        <p className="followup-empty-note">No feedback has been received yet.</p>
      ) : (
        <div className="followup-feedback-grid">
          {feedback.map((entry) => (
            <FeedbackCard entry={entry} onRespond={onRespond} key={entry.id} />
          ))}
        </div>
      )}

    </section>
  );
}

export default FeedbackPanel;
