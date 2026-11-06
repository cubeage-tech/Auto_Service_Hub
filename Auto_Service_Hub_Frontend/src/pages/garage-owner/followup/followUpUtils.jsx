/* =========================================================
   FOLLOW-UP & RETENTION — SHARED HELPERS
   Pure functions only: no components and no state live here,
   so every rule below is easy to reason about in isolation.
   ========================================================= */

import { TODAY_KEY } from "./followUpDemoData";

/* =========================================================
   DATE HELPERS
   Dates are handled as plain YYYY-MM-DD keys so comparisons
   never drift because of time-of-day or time-zone offsets.
   ========================================================= */

export const dateKey = (value) => {
  if (!value) {
    return "";
  }

  if (typeof value === "string" && /^\d{4}-\d{2}-\d{2}$/.test(value)) {
    return value;
  }

  const parsed = new Date(value);

  if (Number.isNaN(parsed.getTime())) {
    return "";
  }

  const year = parsed.getFullYear();
  const month = String(parsed.getMonth() + 1).padStart(2, "0");
  const day = String(parsed.getDate()).padStart(2, "0");
  return `${year}-${month}-${day}`;
};

export const formatDisplayDate = (value) => {
  const key = dateKey(value);

  if (!key) {
    return "—";
  }

  return new Intl.DateTimeFormat("en-GB", {
    day: "2-digit",
    month: "short",
    year: "numeric",
  }).format(new Date(`${key}T00:00:00`));
};

/* Whole days from the first key to the second. Positive means
   the second date is in the future. */
export const dayDifference = (fromKey, toKey) => {
  const from = dateKey(fromKey);
  const to = dateKey(toKey);

  if (!from || !to) {
    return 0;
  }

  const fromTime = new Date(`${from}T00:00:00`).getTime();
  const toTime = new Date(`${to}T00:00:00`).getTime();
  return Math.round((toTime - fromTime) / 86400000);
};

/* Human wording for the due date, plus the tone used to colour
   the cell. Overdue wording is deliberately explicit because
   the reminder needs immediate attention. */
export const describeDueDate = (dueDate, todayKey = TODAY_KEY) => {
  const days = dayDifference(todayKey, dueDate);

  if (days === 0) {
    return { label: "Due today", tone: "today" };
  }

  if (days === 1) {
    return { label: "Due tomorrow", tone: "upcoming" };
  }

  if (days > 1) {
    return { label: `Due in ${days} days`, tone: "upcoming" };
  }

  if (days === -1) {
    return { label: "1 day overdue", tone: "overdue" };
  }

  return { label: `${Math.abs(days)} days overdue`, tone: "overdue" };
};

/* =========================================================
   STATUS HELPERS
   ========================================================= */

export const isCompleted = (followUp) => followUp.status === "Completed";

export const isCancelled = (followUp) => followUp.status === "Cancelled";

/* A follow-up stops being actionable once it is completed or
   cancelled: neither can be overdue. */
export const isClosed = (followUp) =>
  isCompleted(followUp) || isCancelled(followUp);

export const isOverdue = (followUp, todayKey = TODAY_KEY) =>
  !isClosed(followUp) && dayDifference(todayKey, followUp.dueDate) < 0;

/* =========================================================
   VIEW BUCKETS
   "Overdue" is derived from the due date rather than stored as
   a status, so a reminder can be Pending and overdue at the
   same time. Cancelled rows belong to no bucket: they stay
   visible only under "All" so the working views stay clean.
   ========================================================= */

export const FOLLOW_UP_BUCKETS = [
  { key: "all", label: "All Follow-ups" },
  { key: "due-today", label: "Due Today" },
  { key: "upcoming", label: "Upcoming" },
  { key: "overdue", label: "Overdue" },
  { key: "completed", label: "Completed" },
];

export const bucketFor = (followUp, todayKey = TODAY_KEY) => {
  if (isCancelled(followUp)) {
    return "cancelled";
  }

  if (isCompleted(followUp)) {
    return "completed";
  }

  const days = dayDifference(todayKey, followUp.dueDate);

  if (days < 0) {
    return "overdue";
  }

  if (days === 0) {
    return "due-today";
  }

  return "upcoming";
};

export const countByBucket = (followUps, bucketKey, todayKey = TODAY_KEY) => {
  if (bucketKey === "all") {
    return followUps.length;
  }

  return followUps.filter((entry) => bucketFor(entry, todayKey) === bucketKey)
    .length;
};

/* =========================================================
   CSS CLASS MAPPERS
   Each mapper returns only a known modifier so a stray value
   can never break the stylesheet.
   ========================================================= */

export const statusClassName = (status) => {
  switch (status) {
    case "Pending":
      return "pending";
    case "Scheduled":
      return "scheduled";
    case "Reminder Sent":
      return "sent";
    case "Completed":
      return "completed";
    case "Cancelled":
      return "cancelled";
    default:
      return "pending";
  }
};

export const priorityClassName = (priority) => {
  switch (priority) {
    case "High":
      return "high";
    case "Low":
      return "low";
    default:
      return "normal";
  }
};

export const communicationStatusClassName = (status) => {
  switch (status) {
    case "Delivered":
      return "delivered";
    case "Sent":
      return "sent";
    case "Pending":
      return "pending";
    case "Failed":
      return "failed";
    default:
      return "pending";
  }
};

export const loyaltyTierClassName = (tier) =>
  String(tier || "").trim().toLowerCase();

export const retentionStatusClassName = (status) => {
  switch (status) {
    case "Active":
      return "active";
    case "At Risk":
      return "at-risk";
    case "New":
      return "new";
    default:
      return "new";
  }
};

/* =========================================================
   DISPLAY HELPERS
   ========================================================= */

export const formatCurrencyINR = (value) =>
  value === null || value === undefined
    ? "—"
    : new Intl.NumberFormat("en-IN", {
        style: "currency",
        currency: "INR",
        maximumFractionDigits: 0,
      }).format(Number(value));

export const toStars = (rating) =>
  [1, 2, 3, 4, 5].map((step) => step <= Number(rating || 0));

export const averageRating = (entries) => {
  if (!Array.isArray(entries) || entries.length === 0) {
    return 0;
  }

  const total = entries.reduce(
    (sum, entry) => sum + Number(entry.rating || 0),
    0
  );

  return total / entries.length;
};

/* =========================================================
   SORTING
   Overdue first, then due today, then the nearest upcoming
   date. Closed follow-ups sink to the bottom of the list.
   ========================================================= */

const bucketWeight = {
  overdue: 0,
  "due-today": 1,
  upcoming: 2,
  completed: 3,
  cancelled: 4,
};

export const sortFollowUps = (followUps, todayKey = TODAY_KEY) =>
  [...followUps].sort((first, second) => {
    const firstWeight = bucketWeight[bucketFor(first, todayKey)] ?? 5;
    const secondWeight = bucketWeight[bucketFor(second, todayKey)] ?? 5;

    if (firstWeight !== secondWeight) {
      return firstWeight - secondWeight;
    }

    const firstDate = dateKey(first.dueDate);
    const secondDate = dateKey(second.dueDate);

    if (firstDate !== secondDate) {
      return firstDate < secondDate ? -1 : 1;
    }

    return String(first.id).localeCompare(String(second.id));
  });

/* =========================================================
   NEXT FOLLOW-UP ID
   Keeps the demonstration identifiers sequential after a save.
   ========================================================= */

export const nextFollowUpId = (followUps) => {
  const highest = followUps.reduce((max, entry) => {
    const numeric = Number(String(entry.id || "").replace(/\D/g, ""));
    return Number.isFinite(numeric) && numeric > max ? numeric : max;
  }, 2000);

  return `FU-${highest + 1}`;
};