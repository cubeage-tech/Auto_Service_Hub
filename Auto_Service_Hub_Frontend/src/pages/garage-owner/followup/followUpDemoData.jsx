/* =========================================================
   FOLLOW-UP & RETENTION — FRONTEND DEMONSTRATION DATA
   =========================================================
   Every record in this module is hand-written sample data for
   the user interface only.

   The Follow-up page performs NO network request of any kind.
   It does not read or write customers, vehicles, appointments,
   job cards, feedback, messages or retention records through
   the backend. Nothing in here is transmitted anywhere and no
   message, reminder or campaign is really delivered.
   ========================================================= */

/* =========================================================
   DEMONSTRATION LABEL
   Rendered on the page so a viewer can never mistake this
   sample content for live back-office data.
   ========================================================= */

export const DEMO_NOTICE =
  "Demonstration data — this Follow-up & Retention screen is frontend-only. " +
  "No messages are sent and nothing is saved to the backend.";

export const DEMO_SEND_NOTICE =
  "Sending is simulated. Messages stay in this browser session only.";

/* =========================================================
   FOLLOW-UP TYPES
   ========================================================= */

export const FOLLOW_UP_TYPES = [
  "Service Reminder",
  "Post-Service Check",
  "Insurance Renewal",
  "Annual Maintenance",
  "Payment Reminder",
  "Tyre & Battery Check",
  "Feedback Request",
];

/* =========================================================
   COMMUNICATION CHANNELS
   ========================================================= */

export const FOLLOW_UP_CHANNELS = [
  "Phone Call",
  "WhatsApp",
  "SMS",
  "Email",
  "In Person",
];

/* =========================================================
   FOLLOW-UP STATUSES
   Overdue is not listed here: it is derived from the due date
   so a reminder can be both "Pending" and overdue.
   ========================================================= */

export const FOLLOW_UP_STATUSES = [
  "Pending",
  "Scheduled",
  "Reminder Sent",
  "Completed",
  "Cancelled",
];

/* =========================================================
   ASSIGNED STAFF
   ========================================================= */

export const ASSIGNED_STAFF = [
  "Priya Nair (Advisor)",
  "Sunil Verma (Advisor)",
  "Meera Joshi (Front Desk)",
  "Ravi Kumar (Mechanic)",
  "Unassigned",
];

/* =========================================================
   INTERNAL MESSAGE TEMPLATES
   Used only to pre-fill the message box in the communication
   panel. {customer}, {vehicle} and {registration} are replaced
   with the selected follow-up's values.
   ========================================================= */

export const MESSAGE_TEMPLATES = {
  reminder:
    "Hello {customer}, this is a friendly reminder from SmartGarage. " +
    "Your {vehicle} ({registration}) is due for its next scheduled service. " +
    "Reply to this message to confirm a convenient slot.",
  feedback:
    "Hello {customer}, thank you for servicing your {vehicle} ({registration}) with us. " +
    "Could you spare a minute to rate your experience? Your feedback helps us improve.",
  payment:
    "Hello {customer}, a pending balance is showing against your {vehicle} ({registration}). " +
    "Please contact the front desk so we can share the invoice breakdown.",
  renewal:
    "Hello {customer}, your insurance for {vehicle} ({registration}) is approaching renewal. " +
    "We can arrange the paperwork and inspection at the garage.",
};

/* =========================================================
   DATE HELPERS
   Due dates are stored as day offsets from today so that the
   "Due Today", "Upcoming" and "Overdue" views always have
   meaningful content whenever the page is opened.
   ========================================================= */

const startOfToday = () => {
  const now = new Date();
  return new Date(now.getFullYear(), now.getMonth(), now.getDate());
};

export const isoFromOffset = (offsetDays) => {
  const date = startOfToday();
  date.setDate(date.getDate() + offsetDays);
  const year = date.getFullYear();
  const month = String(date.getMonth() + 1).padStart(2, "0");
  const day = String(date.getDate()).padStart(2, "0");
  return `${year}-${month}-${day}`;
};

export const TODAY_KEY = isoFromOffset(0);

/* =========================================================
   FOLLOW-UP SEED RECORDS
   ========================================================= */

const RAW_FOLLOW_UPS = [
  {
    id: "FU-2041",
    customer: "Rajesh Kumar",
    phone: "+91 98765 43210",
    vehicle: "Toyota Fortuner",
    registration: "DL-01-CZ-9834",
    type: "Service Reminder",
    channel: "WhatsApp",
    assignedTo: "Priya Nair (Advisor)",
    status: "Pending",
    dueOffsetDays: 0,
    priority: "High",
    notes: "Second free service due at 20,000 km. Owner prefers a Saturday slot.",
  },
  {
    id: "FU-2042",
    customer: "Anita Sharma",
    phone: "+91 90210 11876",
    vehicle: "Hyundai i20",
    registration: "MH-02-DK-4411",
    type: "Feedback Request",
    channel: "SMS",
    assignedTo: "Meera Joshi (Front Desk)",
    status: "Pending",
    dueOffsetDays: 0,
    priority: "Normal",
    notes: "Vehicle delivered yesterday after brake pad replacement.",
  },
  {
    id: "FU-2043",
    customer: "Imran Sheikh",
    phone: "+91 98450 33129",
    vehicle: "Maruti Swift",
    registration: "KA-05-MJ-7788",
    type: "Payment Reminder",
    channel: "Phone Call",
    assignedTo: "Sunil Verma (Advisor)",
    status: "Pending",
    dueOffsetDays: -3,
    priority: "High",
    notes: "Invoice INV-2231 balance of 8,240 is outstanding beyond 30 days.",
  },
  {
    id: "FU-2044",
    customer: "Kavita Rao",
    phone: "+91 99670 55214",
    vehicle: "Honda City",
    registration: "MH-12-PQ-3320",
    type: "Insurance Renewal",
    channel: "Email",
    assignedTo: "Priya Nair (Advisor)",
    status: "Scheduled",
    dueOffsetDays: -7,
    priority: "High",
    notes: "Comprehensive policy expires this month. Quote shared on email.",
  },
  {
    id: "FU-2045",
    customer: "Deepak Menon",
    phone: "+91 90040 77312",
    vehicle: "Mahindra XUV700",
    registration: "TN-09-BF-6612",
    type: "Post-Service Check",
    channel: "WhatsApp",
    assignedTo: "Ravi Kumar (Mechanic)",
    status: "Reminder Sent",
    dueOffsetDays: -1,
    priority: "Normal",
    notes: "Confirm the suspension noise reported after the road test is gone.",
  },
  {
    id: "FU-2046",
    customer: "Sneha Iyer",
    phone: "+91 97400 62198",
    vehicle: "Tata Nexon EV",
    registration: "KA-01-ZX-1180",
    type: "Annual Maintenance",
    channel: "Phone Call",
    assignedTo: "Sunil Verma (Advisor)",
    status: "Pending",
    dueOffsetDays: 1,
    priority: "Normal",
    notes: "AMC renewal discussion plus battery health report walkthrough.",
  },
  {
    id: "FU-2047",
    customer: "Arjun Bhatia",
    phone: "+91 98250 44107",
    vehicle: "Volkswagen Polo",
    registration: "GJ-01-LK-9042",
    type: "Tyre & Battery Check",
    channel: "SMS",
    assignedTo: "Ravi Kumar (Mechanic)",
    status: "Pending",
    dueOffsetDays: 2,
    priority: "Normal",
    notes: "42,000 km on the clock. Tyre tread depth reported as marginal.",
  },
  {
    id: "FU-2048",
    customer: "Fatima Ansari",
    phone: "+91 88790 30166",
    vehicle: "Renault Kwid",
    registration: "UP-16-AT-2255",
    type: "Service Reminder",
    channel: "WhatsApp",
    assignedTo: "Priya Nair (Advisor)",
    status: "Pending",
    dueOffsetDays: 5,
    priority: "Normal",
    notes: "Third paid service due. Front-left headlamp alignment to be checked.",
  },
  {
    id: "FU-2049",
    customer: "Nikhil Deshpande",
    phone: "+91 96190 87743",
    vehicle: "Kia Seltos",
    registration: "MH-14-GT-5590",
    type: "Insurance Renewal",
    channel: "Email",
    assignedTo: "Meera Joshi (Front Desk)",
    status: "Scheduled",
    dueOffsetDays: 12,
    priority: "Normal",
    notes: "Zero-depreciation add-on to be quoted along with the renewal.",
  },
  {
    id: "FU-2050",
    customer: "Priyanka Sen",
    phone: "+91 70420 91834",
    vehicle: "Skoda Slavia",
    registration: "WB-02-HE-1038",
    type: "Annual Maintenance",
    channel: "Phone Call",
    assignedTo: "Unassigned",
    status: "Cancelled",
    dueOffsetDays: 25,
    priority: "Low",
    notes: "Customer asked to be contacted again after relocation is complete.",
  },
  {
    id: "FU-2038",
    customer: "Manish Gupta",
    phone: "+91 93110 20458",
    vehicle: "Hyundai Creta",
    registration: "DL-08-EH-7712",
    type: "Post-Service Check",
    channel: "WhatsApp",
    assignedTo: "Sunil Verma (Advisor)",
    status: "Completed",
    dueOffsetDays: -2,
    priority: "Normal",
    notes: "Customer confirmed the AC cooling issue is resolved.",
  },
  {
    id: "FU-2039",
    customer: "Lakshmi Narayan",
    phone: "+91 94440 70031",
    vehicle: "Maruti Baleno",
    registration: "TN-11-BK-8846",
    type: "Feedback Request",
    channel: "SMS",
    assignedTo: "Meera Joshi (Front Desk)",
    status: "Completed",
    dueOffsetDays: -5,
    priority: "Low",
    notes: "Feedback received. Customer highlighted the waiting lounge.",
  },
];

export const FOLLOW_UP_SEED = RAW_FOLLOW_UPS.map((entry, index) => ({
  ...entry,
  rowId: `followup-${String(entry.id).toLowerCase()}-${index}`,
  dueDate: isoFromOffset(entry.dueOffsetDays),
}));

/* =========================================================
   COMMUNICATION HISTORY STATUSES
   "Failed" exists so the panel can show an unsuccessful
   attempt. Delivery is never real: nothing leaves the browser.
   ========================================================= */

export const COMMUNICATION_STATUSES = ["Delivered", "Sent", "Pending", "Failed"];

/* =========================================================
   COMMUNICATION HISTORY SEED
   Keyed by follow-up id. A follow-up with no entry simply has
   no recorded contact yet.
   ========================================================= */

const RAW_COMMUNICATIONS = {
  "FU-2041": [
    {
      channel: "WhatsApp",
      status: "Pending",
      atOffsetDays: -1,
      note: "Reminder drafted and queued for the Saturday slot.",
    },
  ],
  "FU-2042": [
    {
      channel: "SMS",
      status: "Delivered",
      atOffsetDays: -1,
      note: "Delivery confirmation received for the feedback link.",
    },
    {
      channel: "Phone Call",
      status: "Sent",
      atOffsetDays: -4,
      note: "Advisor called to confirm pickup time before delivery.",
    },
  ],
  "FU-2043": [
    {
      channel: "Phone Call",
      status: "Failed",
      atOffsetDays: -6,
      note: "No answer on the registered number. Retry required.",
    },
    {
      channel: "SMS",
      status: "Delivered",
      atOffsetDays: -9,
      note: "Payment link shared along with the invoice copy.",
    },
  ],
  "FU-2044": [
    {
      channel: "Email",
      status: "Sent",
      atOffsetDays: -8,
      note: "Renewal quote and add-on comparison attached.",
    },
  ],
  "FU-2045": [
    {
      channel: "WhatsApp",
      status: "Delivered",
      atOffsetDays: -2,
      note: "Customer acknowledged and will report back after the road test.",
    },
  ],
  "FU-2038": [
    {
      channel: "WhatsApp",
      status: "Delivered",
      atOffsetDays: -3,
      note: "Post-service check message delivered. Customer replied.",
    },
    {
      channel: "Phone Call",
      status: "Sent",
      atOffsetDays: -2,
      note: "Customer confirmed the AC cooling issue is resolved.",
    },
  ],
  "FU-2039": [
    {
      channel: "SMS",
      status: "Delivered",
      atOffsetDays: -6,
      note: "Feedback form link delivered and opened.",
    },
  ],
};

export const COMMUNICATION_HISTORY_SEED = Object.entries(
  RAW_COMMUNICATIONS
).reduce((history, [followUpId, entries]) => {
  history[followUpId] = entries.map((entry, index) => ({
    ...entry,
    id: `${followUpId}-comm-${index}`,
    at: isoFromOffset(entry.atOffsetDays),
  }));
  return history;
}, {});

/* =========================================================
   CUSTOMER FEEDBACK SEED
   Ratings are whole stars out of five, matching the rating
   style already used on the Mechanics page.
   ========================================================= */

const RAW_FEEDBACK = [
  {
    id: "FB-9014",
    customer: "Manish Gupta",
    vehicle: "Hyundai Creta",
    registration: "DL-08-EH-7712",
    service: "AC Service",
    rating: 5,
    atOffsetDays: -2,
    comment:
      "Cooling is back to new. The advisor explained the compressor findings clearly and the invoice matched the estimate.",
    responded: false,
    response: "",
  },
  {
    id: "FB-9013",
    customer: "Lakshmi Narayan",
    vehicle: "Maruti Baleno",
    registration: "TN-11-BK-8846",
    service: "Periodic Service",
    rating: 4,
    atOffsetDays: -5,
    comment:
      "Work quality was good and the vehicle was ready on time. The waiting lounge could use more seating.",
    responded: true,
    response: "Thank you for the suggestion — extra seating is being arranged.",
  },
  {
    id: "FB-9012",
    customer: "Imran Sheikh",
    vehicle: "Maruti Swift",
    registration: "KA-05-MJ-7788",
    service: "Brake Overhaul",
    rating: 3,
    atOffsetDays: -8,
    comment:
      "Brakes feel fine now, but the pickup was delayed by almost three hours without an update.",
    responded: false,
    response: "",
  },
  {
    id: "FB-9011",
    customer: "Kavita Rao",
    vehicle: "Honda City",
    registration: "MH-12-PQ-3320",
    service: "Full Service",
    rating: 5,
    atOffsetDays: -12,
    comment:
      "Detailed inspection report with photographs was a pleasant surprise. Will recommend to family.",
    responded: true,
    response: "Thank you — the inspection report is now standard on every full service.",
  },
  {
    id: "FB-9010",
    customer: "Arjun Bhatia",
    vehicle: "Volkswagen Polo",
    registration: "GJ-01-LK-9042",
    service: "Tyre Rotation",
    rating: 4,
    atOffsetDays: -16,
    comment:
      "Wheel alignment feels precise and the tyre wear observation was shared honestly.",
    responded: false,
    response: "",
  },
  {
    id: "FB-9009",
    customer: "Deepak Menon",
    vehicle: "Mahindra XUV700",
    registration: "TN-09-BF-6612",
    service: "Suspension Repair",
    rating: 2,
    atOffsetDays: -21,
    comment:
      "A faint rattle is still audible over speed breakers. Requesting a re-inspection.",
    responded: true,
    response: "Re-inspection booked for this week with the same technician.",
  },
];

export const FEEDBACK_SEED = RAW_FEEDBACK.map((entry) => ({
  ...entry,
  date: isoFromOffset(entry.atOffsetDays),
}));

/* =========================================================
   RETENTION SEED
   Loyalty tiers mirror the tiers used by the Customers page:
   Bronze, Silver, Gold and Platinum.
   ========================================================= */

const RAW_RETENTION = [
  {
    id: "RC-3301",
    customer: "Rajesh Kumar",
    vehicle: "Toyota Fortuner",
    registration: "DL-01-CZ-9834",
    loyaltyTier: "Platinum",
    visits: 11,
    lastVisitOffsetDays: -18,
    nextServiceOffsetDays: 14,
    lifetimeValue: 486500,
    status: "Active",
    note: "Never missed a scheduled service in four years.",
  },
  {
    id: "RC-3302",
    customer: "Kavita Rao",
    vehicle: "Honda City",
    registration: "MH-12-PQ-3320",
    loyaltyTier: "Gold",
    visits: 7,
    lastVisitOffsetDays: -32,
    nextServiceOffsetDays: 9,
    lifetimeValue: 268400,
    status: "Active",
    note: "Consistently rates the workshop five stars.",
  },
  {
    id: "RC-3303",
    customer: "Imran Sheikh",
    vehicle: "Maruti Swift",
    registration: "KA-05-MJ-7788",
    loyaltyTier: "Silver",
    visits: 4,
    lastVisitOffsetDays: -74,
    nextServiceOffsetDays: -6,
    lifetimeValue: 92400,
    status: "At Risk",
    note: "No visit in over two months and an unpaid invoice is pending.",
  },
  {
    id: "RC-3304",
    customer: "Priyanka Sen",
    vehicle: "Skoda Slavia",
    registration: "WB-02-HE-1038",
    loyaltyTier: "Bronze",
    visits: 2,
    lastVisitOffsetDays: -96,
    nextServiceOffsetDays: -11,
    lifetimeValue: 41300,
    status: "At Risk",
    note: "Relocation requested a pause on all reminders.",
  },
  {
    id: "RC-3305",
    customer: "Sneha Iyer",
    vehicle: "Tata Nexon EV",
    registration: "KA-01-ZX-1180",
    loyaltyTier: "Gold",
    visits: 6,
    lastVisitOffsetDays: -41,
    nextServiceOffsetDays: 21,
    lifetimeValue: 197800,
    status: "Active",
    note: "AMC renewal discussion scheduled with the advisor.",
  },
  {
    id: "RC-3306",
    customer: "Fatima Ansari",
    vehicle: "Renault Kwid",
    registration: "UP-16-AT-2255",
    loyaltyTier: "Bronze",
    visits: 1,
    lastVisitOffsetDays: -12,
    nextServiceOffsetDays: 48,
    lifetimeValue: 12800,
    status: "New",
    note: "First-time customer acquired through a referral campaign.",
  },
];

export const RETENTION_SEED = RAW_RETENTION.map((entry) => ({
  ...entry,
  lastVisit: isoFromOffset(entry.lastVisitOffsetDays),
  nextService: isoFromOffset(entry.nextServiceOffsetDays),
}));

/* =========================================================
   RETENTION ACTIVITY SEED
   ========================================================= */

const RAW_RETENTION_ACTIVITIES = [
  {
    id: "RA-7005",
    kind: "Win-back",
    title: "Win-back call completed",
    detail: "Imran Sheikh accepted a discounted brake inspection slot.",
    atOffsetDays: -1,
  },
  {
    id: "RA-7004",
    kind: "Referral",
    title: "Referral reward issued",
    detail: "Fatima Ansari joined through Rajesh Kumar's referral code.",
    atOffsetDays: -3,
  },
  {
    id: "RA-7003",
    kind: "Loyalty",
    title: "Tier upgrade applied",
    detail: "Kavita Rao upgraded from Silver to Gold after the seventh visit.",
    atOffsetDays: -6,
  },
  {
    id: "RA-7002",
    kind: "Offer",
    title: "Festive service offer sent",
    detail: "A 15% labour discount was shared with 42 lapsed customers.",
    atOffsetDays: -11,
  },
  {
    id: "RA-7001",
    kind: "AMC",
    title: "AMC renewal confirmed",
    detail: "Sneha Iyer renewed the annual maintenance contract for one year.",
    atOffsetDays: -15,
  },
];

export const RETENTION_ACTIVITIES_SEED = RAW_RETENTION_ACTIVITIES.map(
  (entry) => ({
    ...entry,
    date: isoFromOffset(entry.atOffsetDays),
  })
);


