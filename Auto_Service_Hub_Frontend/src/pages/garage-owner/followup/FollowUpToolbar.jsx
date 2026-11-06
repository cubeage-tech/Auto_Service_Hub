import React from "react";

import { FOLLOW_UP_CHANNELS, FOLLOW_UP_STATUSES, FOLLOW_UP_TYPES } from "./followUpDemoData";
import { FOLLOW_UP_BUCKETS } from "./followUpUtils";

/* =========================================================
   FOLLOW-UP TOOLBAR
   View tabs plus the search and filter controls. Every control
   is wired to page state: none of them triggers a request.
   ========================================================= */

function FollowUpToolbar({
  activeTab,
  onTabChange,
  bucketCounts,
  search,
  onSearchChange,
  statusFilter,
  onStatusFilterChange,
  channelFilter,
  onChannelFilterChange,
  typeFilter,
  onTypeFilterChange,
  onResetFilters,
  filtersActive,
  resultCount,
  totalCount,
  activeTabLabel,
}) {
  return (
    <div className="followup-toolbar">

      {/* =====================================================
          VIEW TABS
      ===================================================== */}

      <div className="followup-tabs" role="tablist" aria-label="Follow-up views">
        {FOLLOW_UP_BUCKETS.map((bucket) => (
          <button
            key={bucket.key}
            type="button"
            role="tab"
            aria-selected={activeTab === bucket.key}
            className={
              activeTab === bucket.key
                ? "followup-tab active"
                : "followup-tab"
            }
            onClick={() => onTabChange(bucket.key)}
          >
            {bucket.label}

            <span className="followup-tab-count">
              {bucketCounts[bucket.key] ?? 0}
            </span>
          </button>
        ))}
      </div>


      {/* =====================================================
          SEARCH + FILTERS
      ===================================================== */}

      <div className="followup-filters">

        <div className="followup-search">
          <span className="followup-search-icon" aria-hidden="true">
            ⌕
          </span>

          <input
            type="text"
            placeholder="Search by customer, vehicle or registration..."
            value={search}
            onChange={(event) => onSearchChange(event.target.value)}
            aria-label="Search follow-ups"
          />
        </div>

        <select
          className="followup-select"
          value={statusFilter}
          onChange={(event) => onStatusFilterChange(event.target.value)}
          aria-label="Filter by status"
        >
          <option value="ALL">All Statuses</option>
          {FOLLOW_UP_STATUSES.map((status) => (
            <option value={status} key={status}>
              {status}
            </option>
          ))}
        </select>

        <select
          className="followup-select"
          value={channelFilter}
          onChange={(event) => onChannelFilterChange(event.target.value)}
          aria-label="Filter by channel"
        >
          <option value="ALL">All Channels</option>
          {FOLLOW_UP_CHANNELS.map((channel) => (
            <option value={channel} key={channel}>
              {channel}
            </option>
          ))}
        </select>

        <select
          className="followup-select"
          value={typeFilter}
          onChange={(event) => onTypeFilterChange(event.target.value)}
          aria-label="Filter by follow-up type"
        >
          <option value="ALL">All Types</option>
          {FOLLOW_UP_TYPES.map((type) => (
            <option value={type} key={type}>
              {type}
            </option>
          ))}
        </select>

        <button
          type="button"
          className="followup-reset"
          onClick={onResetFilters}
          disabled={!filtersActive}
        >
          RESET
        </button>

      </div>


      {/* =====================================================
          RESULT SUMMARY
      ===================================================== */}

      <p className="followup-result-meta">
        Showing <strong>{resultCount}</strong> of{" "}
        <strong>{totalCount}</strong> demonstration follow-ups
        <span className="followup-result-sep">·</span>
        {activeTabLabel}
      </p>

    </div>
  );
}

export default FollowUpToolbar;
