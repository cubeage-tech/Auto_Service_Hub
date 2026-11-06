import React, { useMemo, useState } from "react";

import {
  COMMUNICATION_HISTORY_SEED,
  DEMO_NOTICE,
  FEEDBACK_SEED,
  FOLLOW_UP_SEED,
  RETENTION_ACTIVITIES_SEED,
  RETENTION_SEED,
  TODAY_KEY,
} from "./followup/followUpDemoData";
import {
  FOLLOW_UP_BUCKETS,
  averageRating,
  bucketFor,
  countByBucket,
  dayDifference,
  formatDisplayDate,
  isClosed,
  isCompleted,
  nextFollowUpId,
  sortFollowUps,
} from "./followup/followUpUtils";

import FollowUpSummaryCards from "./followup/FollowUpSummaryCards";
import FollowUpToolbar from "./followup/FollowUpToolbar";
import FollowUpTable from "./followup/FollowUpTable";
import ScheduleFollowUpModal from "./followup/ScheduleFollowUpModal";
import CommunicationModal from "./followup/CommunicationModal";
import FeedbackPanel from "./followup/FeedbackPanel";
import RetentionPanel from "./followup/RetentionPanel";

import "./GarageOwnerFollowup.css";

/* =========================================================
   GARAGE OWNER — FOLLOW-UP & RETENTION
   =========================================================
   FRONTEND DEMONSTRATION PAGE

   This screen is built entirely from the sample records in
   followup/followUpDemoData.jsx. It issues no HTTP request,
   sends no message and persists nothing:

     · Reminders, feedback and retention records are sample data.
     · Scheduling a follow-up, completing it, "sending" a message
       and replying to feedback only update local component state.
     · Reloading the page restores the original sample records.

   The existing design language of the module is reused
   throughout (page header, stat cards, tabs, table panel,
   status pills and modal shell), so nothing new is introduced
   visually.
   ========================================================= */

function GarageOwnerFollowup() {
  /* =====================================================
     DEMONSTRATION DATA
  ===================================================== */

  const [followUps, setFollowUps] = useState(FOLLOW_UP_SEED);
  const [history, setHistory] = useState(COMMUNICATION_HISTORY_SEED);
  const [feedback, setFeedback] = useState(FEEDBACK_SEED);

  /* =====================================================
     VIEW STATE
  ===================================================== */

  const [activeTab, setActiveTab] = useState("all");
  const [search, setSearch] = useState("");
  const [statusFilter, setStatusFilter] = useState("ALL");
  const [channelFilter, setChannelFilter] = useState("ALL");
  const [typeFilter, setTypeFilter] = useState("ALL");

  const [showSchedule, setShowSchedule] = useState(false);
  const [showCommunication, setShowCommunication] = useState(false);
  const [selectedRowId, setSelectedRowId] = useState("");
  const [successMessage, setSuccessMessage] = useState("");

  /* =====================================================
     CUSTOMER / VEHICLE OPTIONS FOR THE SCHEDULE MODAL
     Built from the demonstration records so the dropdowns stay
     in step with whatever is currently on screen.
  ===================================================== */

  const customerOptions = useMemo(() => {
    const map = new Map();

    followUps.forEach((entry) => {
      const vehicle = {
        vehicle: entry.vehicle,
        registration: entry.registration,
      };
      const existing = map.get(entry.customer);

      if (existing) {
        const alreadyListed = existing.vehicles.some(
          (item) => item.registration === vehicle.registration
        );

        if (!alreadyListed) {
          existing.vehicles.push(vehicle);
        }
        return;
      }

      map.set(entry.customer, {
        customer: entry.customer,
        phone: entry.phone,
        vehicles: [vehicle],
      });
    });

    return [...map.values()].sort((first, second) =>
      first.customer.localeCompare(second.customer)
    );
  }, [followUps]);

  /* =====================================================
     FILTERING
     The tab, the search box and the three dropdowns all narrow
     the same in-memory list.
  ===================================================== */

  const filtersActive =
    search.trim() !== "" ||
    statusFilter !== "ALL" ||
    channelFilter !== "ALL" ||
    typeFilter !== "ALL";

  const visibleFollowUps = useMemo(() => {
    const term = search.trim().toLowerCase();

    const matched = followUps.filter((entry) => {
      if (activeTab !== "all" && bucketFor(entry) !== activeTab) {
        return false;
      }

      if (statusFilter !== "ALL" && entry.status !== statusFilter) {
        return false;
      }

      if (channelFilter !== "ALL" && entry.channel !== channelFilter) {
        return false;
      }

      if (typeFilter !== "ALL" && entry.type !== typeFilter) {
        return false;
      }

      if (!term) {
        return true;
      }

      return [
        entry.id,
        entry.customer,
        entry.phone,
        entry.vehicle,
        entry.registration,
        entry.assignedTo,
        entry.type,
      ].some((value) => String(value || "").toLowerCase().includes(term));
    });

    return sortFollowUps(matched);
  }, [followUps, activeTab, search, statusFilter, channelFilter, typeFilter]);

  const bucketCounts = useMemo(
    () =>
      FOLLOW_UP_BUCKETS.reduce((counts, bucket) => {
        counts[bucket.key] = countByBucket(followUps, bucket.key);
        return counts;
      }, {}),
    [followUps]
  );

  const activeTabLabel =
    FOLLOW_UP_BUCKETS.find((bucket) => bucket.key === activeTab)?.label ||
    "All Follow-ups";

  const emptyMessage = useMemo(() => {
    if (activeTab === "overdue") {
      return "Nothing is overdue — every scheduled reminder is on time.";
    }

    if (activeTab === "due-today") {
      return "No follow-ups are due today.";
    }

    if (activeTab === "upcoming") {
      return "No upcoming follow-ups are scheduled.";
    }

    if (activeTab === "completed") {
      return "No follow-ups have been completed yet.";
    }

    if (filtersActive) {
      return "No follow-ups match the current search or filters.";
    }

    return "No follow-ups found.";
  }, [activeTab, filtersActive]);

  /* =====================================================
     SUMMARY FIGURES
     Every number is counted from the records held in state, so
     the cards always agree with the table below them.
  ===================================================== */

  const summary = useMemo(() => {
    const dueFollowUps = followUps.filter(
      (entry) =>
        !isClosed(entry) && dayDifference(TODAY_KEY, entry.dueDate) <= 0
    ).length;

    const pendingReminders = followUps.filter(
      (entry) => entry.status === "Pending"
    ).length;

    const messagesSent = Object.values(history)
      .flat()
      .filter((entry) => entry.status === "Sent" || entry.status === "Delivered")
      .length;

    const rating = averageRating(feedback);

    return {
      dueFollowUps,
      pendingReminders,
      feedbackReceived: feedback.length,
      messagesSent,
      averageRatingLabel: rating
        ? `Average rating ${rating.toFixed(1)} of 5`
        : "No ratings recorded yet",
    };
  }, [followUps, history, feedback]);

  const retentionSummary = useMemo(
    () =>
      RETENTION_SEED.reduce(
        (counts, entry) => {
          if (entry.status === "Active") {
            counts.active += 1;
          } else if (entry.status === "At Risk") {
            counts.atRisk += 1;
          } else {
            counts.newCustomers += 1;
          }
          return counts;
        },
        { active: 0, atRisk: 0, newCustomers: 0 }
      ),
    []
  );

  const selectedFollowUp =
    followUps.find((entry) => entry.rowId === selectedRowId) || null;

  const selectedHistory = selectedFollowUp
    ? history[selectedFollowUp.id] || []
    : [];

  const feedbackRespondedCount = feedback.filter(
    (entry) => entry.responded
  ).length;

  const averageRatingValue = averageRating(feedback);

  /* =====================================================
     HANDLERS
  ===================================================== */

  const resetFilters = () => {
    setSearch("");
    setStatusFilter("ALL");
    setChannelFilter("ALL");
    setTypeFilter("ALL");
  };

  const openScheduleModal = () => {
    setSuccessMessage("");
    setShowSchedule(true);
  };

  /* Adds the schedule form's values to the demonstration list.
     Returns "" on success, or a message for the modal to show. */
  const handleSaveFollowUp = (values) => {
    const customer = customerOptions.find(
      (entry) => entry.customer === values.customer
    );

    if (!customer) {
      return "That customer is no longer available in the demonstration data.";
    }

    const vehicle = customer.vehicles.find(
      (entry) => entry.registration === values.vehicle
    );

    if (!vehicle) {
      return "That vehicle is no longer available for the selected customer.";
    }

    const id = nextFollowUpId(followUps);

    const record = {
      id,
      rowId: `followup-${id.toLowerCase()}-local-${followUps.length + 1}`,
      customer: customer.customer,
      phone: customer.phone,
      vehicle: vehicle.vehicle,
      registration: vehicle.registration,
      type: values.type,
      channel: values.channel,
      assignedTo: values.assignedTo,
      status: values.status,
      priority: "Normal",
      dueDate: values.dueDate,
      notes: values.notes.trim(),
    };

    setFollowUps((current) => [record, ...current]);
    setShowSchedule(false);
    setActiveTab("all");
    setSuccessMessage(
      `Follow-up ${id} scheduled for ${values.customer} on ` +
        `${formatDisplayDate(values.dueDate)}. Demonstration record only — ` +
        "nothing was saved to the backend."
    );

    return "";
  };

  /* Completing a reminder is a local state change: the row moves
     to the Completed tab and stops counting as overdue. */
  const handleToggleComplete = (entry) => {
    const reopening = isCompleted(entry);

    setFollowUps((current) =>
      current.map((item) =>
        item.rowId === entry.rowId
          ? { ...item, status: reopening ? "Pending" : "Completed" }
          : item
      )
    );

    setSuccessMessage(
      reopening
        ? `${entry.id} reopened for ${entry.customer} and returned to Pending.`
        : `${entry.id} for ${entry.customer} marked as completed.`
    );
  };

  const openCommunication = (entry) => {
    setSelectedRowId(entry.rowId);
    setShowCommunication(true);
  };

  const closeCommunication = () => {
    setShowCommunication(false);
  };

  /* Records a simulated message. The history entry lives in page
     state only: no SMS, WhatsApp message, e-mail or call is
     actually placed. */
  const handleSendCommunication = (entry, payload) => {
    setHistory((current) => {
      const existing = current[entry.id] || [];
      const preview =
        payload.message.length > 140
          ? `${payload.message.slice(0, 140)}…`
          : payload.message;

      const record = {
        id: `${entry.id}-comm-local-${existing.length + 1}`,
        channel: payload.channel,
        status: "Sent",
        at: TODAY_KEY,
        note: `Simulated send from the Follow-up workspace. Message: ${preview}`,
      };

      return { ...current, [entry.id]: [record, ...existing] };
    });

    if (!isClosed(entry)) {
      setFollowUps((current) =>
        current.map((item) =>
          item.rowId === entry.rowId
            ? { ...item, status: "Reminder Sent" }
            : item
        )
      );
    }

    setSuccessMessage(
      `Communication recorded for ${entry.customer}. This was simulated — ` +
        "no message left the browser."
    );
  };

  const handleRespondFeedback = (feedbackId, reply) => {
    setFeedback((current) =>
      current.map((entry) =>
        entry.id === feedbackId
          ? { ...entry, responded: true, response: reply }
          : entry
      )
    );

    setSuccessMessage(
      "Reply saved against the demonstration feedback record. " +
        "No e-mail or message was sent to the customer."
    );
  };


  return (
    <>
      <div className="followup-page">

        {/* =====================================================
            PAGE HEADER
        ===================================================== */}

        <div className="followup-page-top">

          <div className="followup-page-title">

            <div className="followup-eyebrow">
              — FOLLOW-UP &amp; RETENTION
            </div>

            <h1>
              FOLLOW-UP &amp; RETENTION
            </h1>

            <p>
              Manage service reminders, customer communication, feedback, and
              retention.
            </p>

          </div>


          <div className="followup-page-actions">

            <span className="followup-demo-flag">
              DEMO DATA
            </span>

            <button
              type="button"
              className="followup-new-button"
              onClick={openScheduleModal}
            >
              + SCHEDULE FOLLOW-UP
            </button>

          </div>

        </div>


        {/* =====================================================
            DEMONSTRATION NOTICE
        ===================================================== */}

        <div className="followup-demo-notice" role="note">
          {DEMO_NOTICE}
        </div>


        {/* =====================================================
            FEEDBACK
        ===================================================== */}

        {successMessage && (
          <div
            className="followup-feedback followup-feedback-success"
            role="status"
          >
            <span>{successMessage}</span>

            <button
              type="button"
              onClick={() => setSuccessMessage("")}
            >
              Dismiss
            </button>
          </div>
        )}


        {/* =====================================================
            SUMMARY CARDS
        ===================================================== */}

        <FollowUpSummaryCards summary={summary} />


        {/* =====================================================
            TABS + SEARCH + FILTERS
        ===================================================== */}

        <FollowUpToolbar
          activeTab={activeTab}
          onTabChange={setActiveTab}
          bucketCounts={bucketCounts}
          search={search}
          onSearchChange={setSearch}
          statusFilter={statusFilter}
          onStatusFilterChange={setStatusFilter}
          channelFilter={channelFilter}
          onChannelFilterChange={setChannelFilter}
          typeFilter={typeFilter}
          onTypeFilterChange={setTypeFilter}
          onResetFilters={resetFilters}
          filtersActive={filtersActive}
          resultCount={visibleFollowUps.length}
          totalCount={followUps.length}
          activeTabLabel={activeTabLabel}
        />


        {/* =====================================================
            FOLLOW-UP TABLE
        ===================================================== */}

        <FollowUpTable
          followUps={visibleFollowUps}
          todayKey={TODAY_KEY}
          emptyMessage={emptyMessage}
          onOpenCommunication={openCommunication}
          onToggleComplete={handleToggleComplete}
        />


        {/* =====================================================
            FEEDBACK SECTION
        ===================================================== */}

        <FeedbackPanel
          feedback={feedback}
          averageRatingValue={averageRatingValue}
          respondedCount={feedbackRespondedCount}
          onRespond={handleRespondFeedback}
        />


        {/* =====================================================
            RETENTION SECTION
        ===================================================== */}

        <RetentionPanel
          retention={RETENTION_SEED}
          activities={RETENTION_ACTIVITIES_SEED}
          summary={retentionSummary}
        />

      </div>


      {/* =====================================================
          SCHEDULE FOLLOW-UP MODAL
      ===================================================== */}

      <ScheduleFollowUpModal
        open={showSchedule}
        customers={customerOptions}
        suggestedId={nextFollowUpId(followUps)}
        onClose={() => setShowSchedule(false)}
        onSave={handleSaveFollowUp}
      />


      {/* =====================================================
          COMMUNICATION MODAL
      ===================================================== */}

      <CommunicationModal
        open={showCommunication}
        followUp={selectedFollowUp}
        history={selectedHistory}
        onClose={closeCommunication}
        onSend={handleSendCommunication}
      />

    </>
  );
}

export default GarageOwnerFollowup;

