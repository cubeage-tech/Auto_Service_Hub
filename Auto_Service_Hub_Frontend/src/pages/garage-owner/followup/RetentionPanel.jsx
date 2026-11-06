import React from "react";

import {
  formatCurrencyINR,
  formatDisplayDate,
  loyaltyTierClassName,
  retentionStatusClassName,
} from "./followUpUtils";

/* =========================================================
   RETENTION PANEL
   High-level loyalty and retention view: who keeps coming back,
   who has drifted away, and what has already been done about it.

   The frequency bars are plain CSS widths — no chart library is
   used, matching the rest of the dashboard.
   ========================================================= */

function RetentionPanel({ retention, activities, summary }) {
  const maxVisits = retention.reduce(
    (max, entry) => Math.max(max, Number(entry.visits || 0)),
    1
  );

  return (
    <section className="followup-section" aria-label="Retention overview">

      <div className="followup-section-head">
        <div>
          <h2>CUSTOMER RETENTION</h2>
          <p>
            Loyalty tier, visit history and the next service contact for the
            customers most likely to need attention.
          </p>
        </div>

        <div className="followup-section-metrics">
          <span>
            <strong>{summary.active}</strong>
            ACTIVE
          </span>
          <span>
            <strong>{summary.atRisk}</strong>
            AT RISK
          </span>
          <span>
            <strong>{summary.newCustomers}</strong>
            NEW
          </span>
        </div>
      </div>

      <div className="followup-retention-grid">

        <div className="followup-table-panel">

          <div className="followup-table-wrapper">

            <table className="followup-table followup-retention-table">

              <thead>
                <tr>
                  <th>CUSTOMER</th>
                  <th>VEHICLE / REGISTRATION</th>
                  <th>LOYALTY</th>
                  <th>VISITS</th>
                  <th>LAST VISIT</th>
                  <th>NEXT SERVICE DUE</th>
                  <th>LIFETIME VALUE</th>
                  <th>RETENTION</th>
                </tr>
              </thead>

              <tbody>
                {retention.map((entry) => (
                  <tr key={entry.id}>

                    <td>
                      <div className="followup-customer">
                        <strong>{entry.customer}</strong>
                        <span>{entry.note}</span>
                      </div>
                    </td>

                    <td>
                      <div className="followup-vehicle">
                        <strong>{entry.vehicle}</strong>
                        <span>{entry.registration}</span>
                      </div>
                    </td>

                    <td>
                      <span
                        className={`followup-loyalty ${loyaltyTierClassName(
                          entry.loyaltyTier
                        )}`}
                      >
                        {entry.loyaltyTier}
                      </span>
                    </td>

                    <td className="followup-count">{entry.visits}</td>

                    <td className="followup-muted">
                      {formatDisplayDate(entry.lastVisit)}
                    </td>

                    <td className="followup-muted">
                      {formatDisplayDate(entry.nextService)}
                    </td>

                    <td className="followup-value">
                      {formatCurrencyINR(entry.lifetimeValue)}
                    </td>

                    <td>
                      <span
                        className={`followup-retention-status ${retentionStatusClassName(
                          entry.status
                        )}`}
                      >
                        {entry.status}
                      </span>
                    </td>

                  </tr>
                ))}
              </tbody>

            </table>

          </div>

        </div>


        <div className="followup-retention-side">

          {/* =============================================
              VISIT FREQUENCY
          ============================================= */}

          <div className="followup-subpanel">
            <h3>VISITS BY CUSTOMER</h3>

            <div className="followup-bars">
              {retention.map((entry) => (
                <div className="followup-bar-row" key={entry.id}>
                  <div className="followup-bar-label">
                    <strong>{entry.customer}</strong>
                    <span>{entry.visits} visits</span>
                  </div>

                  <div className="followup-bar-track">
                    <div
                      className="followup-bar-fill"
                      style={{
                        width: `${Math.round(
                          (Number(entry.visits || 0) / maxVisits) * 100
                        )}%`,
                      }}
                    />
                  </div>
                </div>
              ))}
            </div>
          </div>


          {/* =============================================
              RETENTION ACTIVITY
          ============================================= */}

          <div className="followup-subpanel">
            <h3>RETENTION ACTIVITY</h3>

            {activities.length === 0 ? (
              <p className="followup-empty-note">
                No retention activity has been recorded yet.
              </p>
            ) : (
              <ul className="followup-activity-list">
                {activities.map((entry) => (
                  <li className="followup-activity-row" key={entry.id}>
                    <span className="followup-activity-kind">{entry.kind}</span>

                    <div className="followup-activity-body">
                      <strong>{entry.title}</strong>
                      <p>{entry.detail}</p>
                      <span>{formatDisplayDate(entry.date)}</span>
                    </div>
                  </li>
                ))}
              </ul>
            )}
          </div>

        </div>

      </div>

    </section>
  );
}

export default RetentionPanel;
