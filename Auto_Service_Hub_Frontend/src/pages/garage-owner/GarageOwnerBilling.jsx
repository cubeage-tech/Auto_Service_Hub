import React, { useCallback, useEffect, useMemo, useState } from "react";
import "./GarageOwnerBilling.css";
import { useAuth } from "../../hooks/useAuth";
import { getCustomers } from "../../services/customerService";
import { getJobCard, getJobCards, getJobCardTasks } from "../../services/jobCardService";
import {
  addInvoiceLabour,
  convertEstimateToInvoice,
  createEstimate,
  createInvoice,
  createPayment,
  deleteEstimate,
  deleteInvoice,
  deletePayment,
  downloadInvoiceExcel,
  downloadInvoicePdf,
  getEstimate,
  getEstimates,
  getInvoice,
  getInvoicePayments,
  getInvoices,
  getPayments,
  updateEstimate,
  updateInvoice,
} from "../../services/billingService";

/* ==================================================================
   CONSTANTS
   ================================================================== */

/** Invoice statuses the backend will accept from a client. */
const INVOICE_STATUS_PENDING = "PENDING";
const INVOICE_STATUS_CANCELLED = "CANCELLED";

/**
 * The backend has no overdue concept, so "overdue" is derived here: an unpaid
 * balance on an invoice dated more than this many days ago.
 */
const OVERDUE_AFTER_DAYS = 30;

/**
 * Roles allowed to write invoices and payments. GARAGE_OWNER is accepted as
 * well as OWNER because the backend mints the JWT role claim from the stored
 * role name — the garage owner's roles row is stored as GARAGE_OWNER — so that
 * raw name can appear in the token even though CustomUserDetailsService aliases
 * it to OWNER while loading authorities.
 *
 * MANAGER is absent on purpose: SRS 13 gives the Manager view-only billing, and
 * InvoiceController and PaymentController now list MANAGER on their read guards
 * only. The Manager still sees every invoice, payment and download control —
 * what goes away is create, edit, pay, cancel, delete and bill-labour.
 */
const WRITE_ROLES = ["OWNER", "GARAGE_OWNER", "BILLING_USER"];

/**
 * Service advisors may raise and edit estimates but not invoices — this
 * mirrors EstimateController's guard. MANAGER is listed explicitly because
 * WRITE_ROLES no longer carries it: the Manager keeps estimate create, edit and
 * delete (SRS 13). Converting is the exception — it raises an invoice, so it
 * stays behind canWrite along with every other invoice write.
 */
const ESTIMATE_WRITE_ROLES = [...WRITE_ROLES, "MANAGER", "SERVICE_ADVISOR"];

/**
 * Payment modes. `mode` is free text on the backend (PaymentRequestDTO), so
 * these are labels only — the value stored is whatever is sent here.
 *
 * PAY_LATER is the one mode with no payment behind it: the backend has no
 * credit or deferred-payment concept, so selecting it saves the invoice unpaid
 * and records nothing. See handleSaveDocument.
 */
const PAYMENT_MODES = ["UPI", "CASH", "CARD", "BANK_TRANSFER", "PAY_LATER"];
const PAYMENT_MODE_LABELS = {
  UPI: "UPI",
  CASH: "Cash",
  CARD: "Card",
  BANK_TRANSFER: "Bank Transfer",
  PAY_LATER: "Pay Later",
};
const PAY_LATER = "PAY_LATER";

/**
 * The only payment status that represents money actually received
 * (PaymentServiceImpl.PAYMENT_STATUS_SETTLED). Anything else — e.g. a failed or
 * voided payment — must not be shown as the invoice's mode.
 */
const PAYMENT_STATUS_SETTLED = "SUCCESS";

/**
 * Ceiling on the Notes textarea, matching @Size(max = 2000) on
 * InvoiceRequestDTO and EstimateRequestDTO. The two are kept in step so the UI
 * stops the user at exactly the point the API would start rejecting them,
 * rather than letting a save fail server-side after they have typed it.
 */
const NOTES_MAX_LENGTH = 2000;

/**
 * GST rates the backend accepts for a single document. Anything outside
 * 0-100 is rejected with a 409 by BillingCalculator.resolveRate.
 */
const GST_RATES = [18, 5, 12, 28];

const BILLING_ITEM_CATEGORIES = ["PART", "LABOUR", "PACKAGE", "OTHER"];

/**
 * Short labels so the category select can stay compact inside a line-item row
 * without wrapping. The submitted value is always the full enum name.
 */
const CATEGORY_LABELS = {
  PART: "Part",
  LABOUR: "Labour",
  PACKAGE: "Pkg",
  OTHER: "Other",
};

const INVOICE_STATUS_LABELS = {
  PENDING: "Pending",
  PARTIALLY_PAID: "Partial",
  PAID: "Paid",
  CANCELLED: "Cancelled",
};

const ESTIMATE_STATUS_LABELS = {
  DRAFT: "Draft",
  CONVERTED: "Converted",
};


/* ==================================================================
   FORMATTING
   ================================================================== */

const moneyFormatter = new Intl.NumberFormat("en-IN", {
  style: "currency",
  currency: "INR",
  minimumFractionDigits: 2,
  maximumFractionDigits: 2,
});

const dateFormatter = new Intl.DateTimeFormat("en-IN", {
  day: "2-digit",
  month: "short",
  year: "numeric",
});

const toAmount = (value) => {
  const parsed = Number(value);
  return Number.isFinite(parsed) ? parsed : 0;
};

const formatMoney = (value) => moneyFormatter.format(toAmount(value));

/**
 * Same value, trailing ".00" dropped, so a line total fits the compact
 * line-item column.
 */
const formatLineMoney = (value) => {
  const amount = toAmount(value);
  return Number.isInteger(amount)
    ? moneyFormatter.format(amount).replace(/\.00$/, "")
    : moneyFormatter.format(amount);
};

/** Trims a stored rate like 18.00 or 5.5 down to how it is shown in the UI. */
const formatRate = (value) => String(toAmount(value));

const formatDate = (value) => {
  if (!value) {
    return "—";
  }
  const parsed = new Date(value);
  return Number.isNaN(parsed.getTime()) ? "—" : dateFormatter.format(parsed);
};

const formatMode = (mode) => {
  if (!mode) {
    return "—";
  }
  return PAYMENT_MODE_LABELS[mode] || String(mode).replace(/_/g, " ").toLowerCase().replace(/^./, (c) => c.toUpperCase());
};


/* ==================================================================
   DERIVED HELPERS
   ================================================================== */

const normaliseRole = (user) =>
  String(user?.role || "").replace(/^ROLE_/, "").toUpperCase();

const isOverdue = (invoice) => {
  // A cancelled invoice is never chased for payment, so it is never overdue.
  if (isInvoiceCancelled(invoice) || toAmount(invoice?.outstandingAmount) <= 0) {
    return false;
  }
  if (!invoice?.invoiceDate) {
    return false;
  }
  const cutoff = new Date();
  cutoff.setDate(cutoff.getDate() - OVERDUE_AFTER_DAYS);
  const invoiceDate = new Date(invoice.invoiceDate);
  return !Number.isNaN(invoiceDate.getTime()) && invoiceDate < cutoff;
};

const invoiceStatusTone = (invoice) => {
  const status = String(invoice?.status || "").toUpperCase();
  if (status === "PAID") return "paid";
  if (status === "CANCELLED") return "cancelled";
  if (status === "PARTIALLY_PAID") return "partial";
  return isOverdue(invoice) ? "overdue" : "pending";
};

const estimateStatusTone = (estimate) =>
  String(estimate?.status || "").toUpperCase() === "CONVERTED" ? "converted" : "draft";

/** A paid invoice can no longer be edited or deleted (InvoiceServiceImpl). */
const isInvoiceSettled = (invoice) =>
  String(invoice?.status || "").toUpperCase() === "PAID";

const isInvoiceCancelled = (invoice) =>
  String(invoice?.status || "").toUpperCase() === INVOICE_STATUS_CANCELLED;

const isEstimateConverted = (estimate) =>
  String(estimate?.status || "").toUpperCase() === "CONVERTED";

const canPayInvoice = (invoice) =>
  !isInvoiceSettled(invoice) &&
  !isInvoiceCancelled(invoice) &&
  toAmount(invoice?.outstandingAmount) > 0;

const newLineItem = () => ({ description: "", qty: "", price: "", category: "PART" });

const emptyForm = () => ({
  documentType: "Estimate",
  customerId: "",
  jobCardId: "",
  lineItems: [newLineItem(), newLineItem()],
  fixedLabour: [],
  discount: "0",
  gstRate: "18",
  recordPayment: false,
  paymentAmount: "",
  paymentMode: "UPI",
  paymentRef: "",
  notes: "",
});

/** A row the user has touched at all, as opposed to an untouched spare row. */
const isFilledLine = (line) =>
  line.description.trim() !== "" || line.qty !== "" || line.price !== "";

/**
 * Only rows the user actually filled in are sent. The form seeds spare blank
 * rows for convenience, and InvoiceItemRequestDTO.description is @NotBlank —
 * sending one of those blanks as a line would fail the whole save with a 400.
 */
const buildPayloadItems = (lineItems) =>
  lineItems.filter(isFilledLine).map((line) => ({
    description: line.description.trim(),
    quantity: Number(line.qty),
    unitPrice: Number(line.price),
    category: line.category || "PART",
  }));

const triggerBrowserDownload = (blob, filename) => {
  const url = URL.createObjectURL(blob);
  const link = document.createElement("a");
  link.href = url;
  link.download = filename;
  document.body.appendChild(link);
  link.click();
  link.remove();
  URL.revokeObjectURL(url);
};


/* ==================================================================
   PAGE
   ================================================================== */

function GarageOwnerBilling() {
  const { user } = useAuth();
  const role = normaliseRole(user);
  const canWrite = WRITE_ROLES.includes(role);
  const canWriteEstimates = ESTIMATE_WRITE_ROLES.includes(role);

  /* ---------- documents ---------- */

  const [activeTab, setActiveTab] = useState("invoices");
  const [invoices, setInvoices] = useState([]);
  const [estimates, setEstimates] = useState([]);
  const [payments, setPayments] = useState([]);

  /* ---------- form options ---------- */

  const [customers, setCustomers] = useState([]);
  const [jobCards, setJobCards] = useState([]);

  /* ---------- page state ---------- */

  const [isLoading, setIsLoading] = useState(true);
  const [isSaving, setIsSaving] = useState(false);
  const [loadError, setLoadError] = useState("");
  const [successMessage, setSuccessMessage] = useState("");
  const [notice, setNotice] = useState("");
  const [formError, setFormError] = useState("");

  /* ---------- document form ---------- */

  const [showDocumentModal, setShowDocumentModal] = useState(false);
  const [editingDocument, setEditingDocument] = useState(null);
  const [form, setForm] = useState(emptyForm);

  /* ---------- detail modal ---------- */

  const [detailDocument, setDetailDocument] = useState(null);
  const [detailPayments, setDetailPayments] = useState([]);
  const [detailTasks, setDetailTasks] = useState([]);
  const [isDetailLoading, setIsDetailLoading] = useState(false);
  const [detailError, setDetailError] = useState("");

  /* ---------- payment form ---------- */

  const [paymentTarget, setPaymentTarget] = useState(null);
  const [paymentAmount, setPaymentAmount] = useState("");
  const [paymentMode, setPaymentMode] = useState("UPI");
  const [paymentRef, setPaymentRef] = useState("");

  /* ---------- row action in flight ---------- */

  const [busyActions, setBusyActions] = useState([]);
  const [labourTaskId, setLabourTaskId] = useState("");

  const patchForm = (changes) => setForm((current) => ({ ...current, ...changes }));

  /* =========================================
     LOADERS
  ========================================= */

  const loadDocuments = useCallback(async ({ showLoading = true } = {}) => {
    if (showLoading) {
      setIsLoading(true);
    }
    setLoadError("");

    try {
      const [loadedInvoices, loadedEstimates, loadedPayments] = await Promise.all([
        getInvoices(),
        getEstimates(),
        // Feeds the table's MODE column. An invoice carries no payment mode of
        // its own — the mode is recorded against the payment — so what is shown
        // is read off the invoice's most recent settled payment. Fetched once
        // per page load rather than per invoice, so the list does not become
        // N+1 requests.
        getPayments(),
      ]);
      setInvoices(loadedInvoices);
      setEstimates(loadedEstimates);
      setPayments(loadedPayments);
    } catch (error) {
      setLoadError(
        error instanceof Error ? error.message : "Unable to load billing documents."
      );
    } finally {
      if (showLoading) {
        setIsLoading(false);
      }
    }
  }, []);

  const loadFormOptions = useCallback(async () => {
    try {
      const [loadedCustomers, loadedJobCards] = await Promise.all([
        getCustomers(),
        getJobCards(),
      ]);
      setCustomers(loadedCustomers);
      setJobCards(loadedJobCards);
    } catch (error) {
      setNotice(
        error instanceof Error
          ? `Billing form options could not be loaded: ${error.message}`
          : "Billing form options could not be loaded."
      );
    }
  }, []);

  useEffect(() => {
    void loadDocuments();
  }, [loadDocuments]);

  useEffect(() => {
    void loadFormOptions();
  }, [loadFormOptions]);

  /* =========================================
     DERIVED DATA
  ========================================= */

  const jobCardById = useMemo(
    () => new Map(jobCards.map((jobCard) => [Number(jobCard.id), jobCard])),
    [jobCards]
  );

  const jobCardNumber = useCallback(
    (jobCardId) => {
      if (jobCardId == null) {
        return "—";
      }
      return jobCardById.get(Number(jobCardId))?.jobCardNumber || `#${jobCardId}`;
    },
    [jobCardById]
  );

  const customerJobCards = useMemo(() => {
    if (!form.customerId) {
      return [];
    }
    return jobCards.filter(
      (jobCard) => Number(jobCard.customerId) === Number(form.customerId)
    );
  }, [jobCards, form.customerId]);

  /**
   * Live arithmetic on the lines the user has typed. This is a PREVIEW only —
   * GST and total are deliberately not derived here, because BillingCalculator
   * owns that rule and its rate is server configuration. The authoritative
   * figures are whatever the saved document comes back with.
   */
  const preview = useMemo(() => {
    let subtotal = 0;

    form.lineItems.forEach((line) => {
      const quantity = Number(line.qty);
      const price = Number(line.price);
      if (Number.isFinite(quantity) && Number.isFinite(price) && quantity > 0 && price >= 0) {
        subtotal += quantity * price;
      }
    });

    const discount = Math.max(0, toAmount(form.discount));
    return { subtotal, discount, taxable: Math.max(0, subtotal - discount) };
  }, [form.lineItems, form.discount]);

  const statistics = useMemo(() => {
    const now = new Date();
    const thisMonth = now.getMonth();
    const thisYear = now.getFullYear();

    let monthTotal = 0;
    let pendingTotal = 0;
    let overdueTotal = 0;
    let invoicedTotal = 0;
    let invoicedCount = 0;

    invoices.forEach((invoice) => {
      if (isInvoiceCancelled(invoice)) {
        return;
      }

      const total = toAmount(invoice.total);
      invoicedTotal += total;
      invoicedCount += 1;

      const invoiceDate = invoice.invoiceDate ? new Date(invoice.invoiceDate) : null;
      if (
        invoiceDate &&
        !Number.isNaN(invoiceDate.getTime()) &&
        invoiceDate.getFullYear() === thisYear &&
        invoiceDate.getMonth() === thisMonth
      ) {
        monthTotal += total;
      }

      const outstanding = toAmount(invoice.outstandingAmount);
      if (outstanding > 0) {
        pendingTotal += outstanding;
        if (isOverdue(invoice)) {
          overdueTotal += outstanding;
        }
      }
    });

    return {
      monthTotal,
      pendingTotal,
      overdueTotal,
      average: invoicedCount ? invoicedTotal / invoicedCount : 0,
    };
  }, [invoices]);

  /* =========================================
     PAYMENT MODE PER INVOICE (table MODE column)
     ========================================= */

  /**
   * invoiceId -> mode of its most recent settled payment.
   *
   * Only SUCCESS payments count, matching PaymentServiceImpl.PAYMENT_STATUS_SETTLED
   * and therefore the same rule InvoiceServiceImpl uses for amountPaid — so the
   * mode shown always belongs to money that actually came in. Where an invoice
   * has no settled payment the map has no entry and the cell renders a dash.
   */
  const paymentModeByInvoice = useMemo(() => {
    const map = new Map();
    payments.forEach((payment) => {
      if (String(payment?.status || "").toUpperCase() !== PAYMENT_STATUS_SETTLED) {
        return;
      }
      if (payment.invoiceId == null || !payment.mode) {
        return;
      }
      const current = map.get(payment.invoiceId);
      // getPayments() is sorted paidAt desc, but the id is used as the
      // tiebreaker so the winner does not depend on the response ordering.
      const isNewer =
        current === undefined || toAmount(payment.id) > toAmount(current.id);
      if (isNewer) {
        map.set(payment.invoiceId, payment);
      }
    });
    return map;
  }, [payments]);

  /* =========================================
     DOCUMENT FORM — OPEN / CLOSE
  ========================================= */

  const openCreateModal = () => {
    setEditingDocument(null);
    setForm(emptyForm());
    setFormError("");
    setSuccessMessage("");
    setNotice("");
    setShowDocumentModal(true);
  };

  /**
   * Opens the form pre-filled for an update. Lines the backend generated from
   * a job card task (jobTaskId != null) are split out and shown read-only:
   * InvoiceServiceImpl.replaceItems re-creates the lines it is sent AND then
   * re-adds the task-sourced ones, so echoing them back would bill the labour
   * twice on every edit.
   */
  const openEditModal = async (target) => {
    const type = target.type;
    const jobCardId = target.data.jobCardId ?? "";
    const items = target.data.items ?? [];

    const fixedLabour = items.filter((item) => item.jobTaskId != null);
    const editableItems = items.filter((item) => item.jobTaskId == null);

    // The job card list is paged, so an older card may not be in it. Falling
    // back to GET /job-cards/{id} keeps the edit form submittable rather than
    // stranding it with an unresolvable customer.
    let jobCard = jobCardById.get(Number(jobCardId));
    if (!jobCard && jobCardId !== "") {
      try {
        jobCard = await getJobCard(jobCardId);
      } catch {
        jobCard = null;
      }
    }

    setEditingDocument({ type, id: target.data.id });
    setForm({
      documentType: type,
      customerId: jobCard?.customerId != null ? String(jobCard.customerId) : "",
      jobCardId: jobCardId === "" ? "" : String(jobCardId),
      lineItems:
        editableItems.length > 0
          ? editableItems.map((item) => ({
              description: item.description ?? "",
              qty: item.quantity == null ? "" : String(item.quantity),
              price: item.unitPrice == null ? "" : String(item.unitPrice),
              category: item.category || "PART",
            }))
          : [newLineItem()],
      fixedLabour,
      discount:
        target.data.discount == null ? "0" : String(Number(target.data.discount)),
      // Prefill from the stored rate so editing never silently re-prices the
      // document at whatever the server default happens to be today.
      gstRate:
        target.data.gstRate == null
          ? String(GST_RATES[0])
          : String(Number(target.data.gstRate)),
      recordPayment: false,
      paymentAmount: "",
      paymentMode: "UPI",
      paymentRef: "",
      // Repopulated from the stored document so editing never wipes the notes.
      // The response carries null when the document has none, which the textarea
      // wants as "" — hence the ?? fallback.
      notes: target.data.notes ?? "",
    });
    setFormError("");
    setSuccessMessage("");
    setNotice("");
    setShowDocumentModal(true);
  };

  const closeDocumentModal = () => {
    if (isSaving) {
      return;
    }
    setShowDocumentModal(false);
    setEditingDocument(null);
    setFormError("");
  };

  /* =========================================
     DOCUMENT FORM — LINE ITEMS
  ========================================= */

  const addLineItem = () => {
    setForm((current) => ({
      ...current,
      lineItems: [...current.lineItems, newLineItem()],
    }));
  };

  const removeLineItem = (index) => {
    setForm((current) => ({
      ...current,
      lineItems: current.lineItems.filter((_, itemIndex) => itemIndex !== index),
    }));
  };

  const updateLineItem = (index, field, value) => {
    setForm((current) => ({
      ...current,
      lineItems: current.lineItems.map((line, lineIndex) =>
        lineIndex === index ? { ...line, [field]: value } : line
      ),
    }));
  };

  const handleCustomerChange = (customerId) => {
    // Changing the customer invalidates the job card, which is scoped to them.
    patchForm({ customerId, jobCardId: "" });
  };

  /* =========================================
     DOCUMENT FORM — VALIDATION
  ========================================= */

  const validateDocumentForm = () => {
    if (!form.customerId) {
      return "Select a customer.";
    }

    if (!form.jobCardId) {
      return customerJobCards.length
        ? "Select a job card. The backend requires one on every estimate and invoice."
        : "This customer has no job cards yet. An estimate or invoice must be raised against a job card.";
    }

    const filled = form.lineItems.filter(isFilledLine);

    if (filled.length === 0) {
      return "Add at least one line item.";
    }

    for (const line of filled) {
      const label = line.description.trim() || "(no description)";
      if (!line.description.trim()) {
        return `Line "${label}" needs a description.`;
      }

      const quantity = Number(line.qty);
      if (line.qty === "" || !Number.isInteger(quantity) || quantity <= 0) {
        return `Line "${label}" needs a whole quantity greater than 0.`;
      }

      const price = Number(line.price);
      if (line.price === "" || !Number.isFinite(price) || price < 0) {
        return `Line "${label}" needs a unit price of 0 or more.`;
      }
      // BillingCalculator rejects sub-paise precision, and a number input can
      // still be typed in exponent form ("1e-7"), so the parsed value is what
      // gets checked rather than the raw string.
      if (Math.round(price * 100) !== price * 100) {
        return `Line "${label}" has a unit price with more than 2 decimal places.`;
      }
    }

    const discount = toAmount(form.discount);
    if (discount < 0) {
      return "Discount cannot be negative.";
    }
    if (discount > preview.subtotal) {
      return `Discount (${formatMoney(discount)}) cannot exceed the line subtotal (${formatMoney(
        preview.subtotal
      )}).`;
    }

    if (form.recordPayment) {
      // Pay Later deliberately records no payment, so it needs no amount.
      if (form.paymentMode !== PAY_LATER) {
        const amount = Number(form.paymentAmount);
        if (form.paymentAmount === "" || !Number.isFinite(amount) || amount <= 0) {
          return "Enter the payment amount to record.";
        }
      }
    }

    return "";
  };

  /* =========================================
     DOCUMENT FORM — SAVE
  ========================================= */

  const handleSaveDocument = async (event) => {
    event.preventDefault();

    const validationError = validateDocumentForm();
    if (validationError) {
      setFormError(validationError);
      return;
    }

    const type = form.documentType;
    const isInvoice = type === "Invoice";
    const isEditing = editingDocument !== null;

    const payload = {
      jobCardId: Number(form.jobCardId),
      discount: toAmount(form.discount),
      // The rate is the one tax decision the backend accepts per document. The
      // GST and total it derives from this are still calculated server-side.
      gstRate: Number(form.gstRate),
      items: buildPayloadItems(form.lineItems),
      // Free-text remarks, accepted by both InvoiceRequestDTO and
      // EstimateRequestDTO and persisted in the matching `notes` column. The
      // value is trimmed here so the stored value matches what is sent, and a
      // cleared textarea is sent as null rather than "" — the service stores
      // blank as NULL either way, and null keeps the request honest about
      // "no notes" rather than asserting an empty string is content.
      notes: form.notes.trim() === "" ? null : form.notes.trim(),
    };

    // Subtotal, GST and total are intentionally NOT sent: BillingCalculator
    // derives all three server-side and ignores anything supplied for them.
    // Status is only sent on create. On an edit the backend preserves whatever
    // it already holds, which matters because a PARTIALLY_PAID invoice would be
    // pushed back to PENDING if we resent a status.
    if (isInvoice && !isEditing) {
      payload.status = INVOICE_STATUS_PENDING;
    }

    setIsSaving(true);
    setFormError("");
    setSuccessMessage("");
    setNotice("");

    try {
      let saved;
      if (isInvoice) {
        saved = isEditing
          ? await updateInvoice(editingDocument.id, payload)
          : await createInvoice(payload);
      } else {
        saved = isEditing
          ? await updateEstimate(editingDocument.id, payload)
          : await createEstimate(payload);
      }

      const label = isInvoice ? `Invoice INV-${saved.id}` : `Estimate #${saved.id}`;
      const verb = isEditing ? "updated" : "saved";

      // Notes round-trip check. The create/update call returns the document as
      // saved, so comparing its notes against what was sent proves the value
      // actually reached the database instead of merely being echoed back by
      // the request object. Both sides are normalised the same way (null for
      // blank) or a legitimately-cleared note would read as a mismatch.
      const notesSent = payload.notes;
      const notesReturned = saved.notes == null || saved.notes === "" ? null : saved.notes;
      if (notesReturned !== notesSent) {
        setNotice((current) =>
          `${
            current ? current + " " : ""
          }The notes were not stored as sent (sent ${
            JSON.stringify(notesSent)
          }, API returned ${JSON.stringify(notesReturned)}).`
        );
      }

      // Payment recording is a second, real call — POST /payments. It is only
      // attempted after the document itself exists, so a failure here leaves a
      // saved invoice rather than a half-written one.
      //
      // PAY LATER records nothing. The backend has no deferred-payment or
      // credit concept, and the only way to stop a payment settling the invoice
      // would be to fabricate a non-SUCCESS payment row. Leaving the invoice
      // PENDING with its full balance outstanding is the honest mapping.
      const payLater = form.paymentMode === PAY_LATER;

      if (isInvoice && !isEditing && form.recordPayment && payLater) {
        setSuccessMessage(
          `${label} ${verb}. Marked Pay Later — no payment was recorded and the full ` +
            `${formatMoney(saved.total)} is outstanding.`
        );
      } else if (isInvoice && !isEditing && form.recordPayment) {
        try {
          const payment = await createPayment({
            invoiceId: saved.id,
            amount: Number(form.paymentAmount),
            mode: form.paymentMode,
            transactionRef: form.paymentRef.trim() || null,
          });
          setSuccessMessage(
            `${label} ${verb}. Payment of ${formatMoney(payment.amount)} recorded (${formatMode(
              payment.mode
            )}).`
          );
        } catch (paymentError) {
          setNotice(
            `${label} was ${verb} with a total of ${formatMoney(
              saved.total
            )}, but the payment was not recorded: ${
              paymentError instanceof Error ? paymentError.message : "unknown error"
            }`
          );
        }
      } else {
        setSuccessMessage(`${label} ${verb}.`);
      }

      setShowDocumentModal(false);
      setEditingDocument(null);
      await loadDocuments({ showLoading: false });
    } catch (error) {
      setFormError(error instanceof Error ? error.message : "Unable to save the document.");
    } finally {
      setIsSaving(false);
    }
  };

  /* =========================================
     DETAIL MODAL
  ========================================= */

  const openInvoiceDetail = async (invoice) => {
    setDetailDocument({ type: "Invoice", data: invoice });
    setDetailPayments([]);
    setDetailTasks([]);
    setDetailError("");
    setIsDetailLoading(true);

    try {
      const [fresh, payments, tasks] = await Promise.all([
        getInvoice(invoice.id),
        getInvoicePayments(invoice.id),
        invoice.jobCardId ? getJobCardTasks(invoice.jobCardId) : Promise.resolve([]),
      ]);
      setDetailDocument({ type: "Invoice", data: fresh });
      setDetailPayments(payments);
      setDetailTasks(tasks);
    } catch (error) {
      setDetailError(
        error instanceof Error ? error.message : "Unable to load this invoice."
      );
    } finally {
      setIsDetailLoading(false);
    }
  };

  const openEstimateDetail = async (estimate) => {
    setDetailDocument({ type: "Estimate", data: estimate });
    setDetailPayments([]);
    setDetailTasks([]);
    setDetailError("");
    setIsDetailLoading(true);

    try {
      setDetailDocument({ type: "Estimate", data: await getEstimate(estimate.id) });
    } catch (error) {
      setDetailError(
        error instanceof Error ? error.message : "Unable to load this estimate."
      );
    } finally {
      setIsDetailLoading(false);
    }
  };

  const closeDetailModal = () => {
    setDetailDocument(null);
    setDetailPayments([]);
    setDetailTasks([]);
    setDetailError("");
  };

  /* =========================================
     ROW ACTIONS
  ========================================= */

  /**
   * Runs a row action, surfacing a real success message only when the backend
   * call actually succeeded. Returns null on failure so callers do not go on
   * to close a modal or refresh a panel for an action that was rejected.
   */
  const runAction = async (key, action, successText) => {
    setBusyActions((current) => [...current, key]);
    setSuccessMessage("");
    setNotice("");

    try {
      const result = await action();
      setSuccessMessage(typeof successText === "function" ? successText(result) : successText);
      await loadDocuments({ showLoading: false });
      return result ?? true;
    } catch (error) {
      setNotice(error instanceof Error ? error.message : "The action could not be completed.");
      return null;
    } finally {
      setBusyActions((current) => current.filter((entry) => entry !== key));
    }
  };

  const isBusy = (prefix) => busyActions.some((key) => key.startsWith(prefix));

  const handleConvertEstimate = (estimate) => {
    if (
      !window.confirm(
        `Convert estimate #${estimate.id} into an invoice? The estimate is then locked and cannot be edited or converted again.`
      )
    ) {
      return;
    }

    void runAction(
      `estimate-${estimate.id}-convert`,
      () => convertEstimateToInvoice(estimate.id),
      (invoice) => `Estimate #${estimate.id} converted. Invoice INV-${invoice.id} created.`
    );
  };

  const handleDownload = (invoice, format) => {
    void runAction(
      `invoice-${invoice.id}-${format}`,
      async () => {
        const { blob, filename } =
          format === "pdf"
            ? await downloadInvoicePdf(invoice.id)
            : await downloadInvoiceExcel(invoice.id);
        triggerBrowserDownload(blob, filename);
        return filename;
      },
      (filename) => `Downloaded ${filename}.`
    );
  };

  /**
   * Cancelling is a full PUT with status CANCELLED, so the stored lines have to
   * travel with it — the endpoint is a replace, not a patch. PAID and
   * PARTIALLY_PAID are rejected server-side because they are derived from
   * payments; CANCELLED is one of the two client-settable values.
   */
  const handleCancelInvoice = async (invoice) => {
    if (!window.confirm(`Cancel invoice INV-${invoice.id}? It stays on record but cannot be paid or edited.`)) {
      return;
    }

    const cancelled = await runAction(
      `invoice-${invoice.id}-cancel`,
      async () => {
        const current = await getInvoice(invoice.id);
        return updateInvoice(invoice.id, {
          jobCardId: current.jobCardId,
          discount: current.discount,
          status: INVOICE_STATUS_CANCELLED,
          invoiceDate: current.invoiceDate,
          // Task-sourced labour lines are omitted on purpose: the backend
          // re-creates them itself on every write, so echoing them back would
          // double them.
          items: (current.items || [])
            .filter((item) => item.jobTaskId == null)
            .map((item) => ({
              description: item.description,
              quantity: item.quantity,
              unitPrice: item.unitPrice,
              category: item.category || "PART",
            })),
        });
      },
      (cancelled) => `Invoice INV-${cancelled.id} cancelled.`
    );

    if (cancelled !== null && detailDocument?.type === "Invoice" && detailDocument.data.id === invoice.id) {
      await openInvoiceDetail(invoice);
    }
  };

  const handleDeleteDocument = async (target) => {
    const type = target.type;
    const label =
      type === "Invoice" ? `invoice INV-${target.data.id}` : `estimate #${target.data.id}`;

    if (!window.confirm(`Permanently delete this ${label}? This cannot be undone.`)) {
      return;
    }

    const deletedId = await runAction(
      `${type.toLowerCase()}-${target.data.id}-delete`,
      async () => {
        try {
          if (type === "Invoice") {
            await deleteInvoice(target.data.id);
          } else {
            await deleteEstimate(target.data.id);
          }
        } catch (error) {
          // Observed backend behaviour: DELETE /invoices/{id} answers a plain
          // 500 INTERNAL_ERROR when the invoice still has payments against it,
          // because payments.invoice_id is a foreign key and
          // GlobalExceptionHandler does not map the constraint violation. The
          // delete rolls back, so the invoice is untouched. Saying so beats
          // showing the raw "Something went wrong".
          if (
            type === "Invoice" &&
            toAmount(target.data.amountPaid) > 0 &&
            /Something went wrong|INTERNAL_ERROR/i.test(error?.message || "")
          ) {
            throw new Error(
              `${error.message} This invoice still has ${formatMoney(
                target.data.amountPaid
              )} of recorded payments, which the database will not let it delete. ` +
                "Cancel the invoice instead if it should not have been raised."
            );
          }
          throw error;
        }
        return target.data.id;
      },
      (id) => `The ${type === "Invoice" ? "invoice INV-" + id : "estimate #" + id} was deleted.`
    );

    // Only close the panel if the DELETE actually went through.
    if (deletedId !== null && detailDocument && detailDocument.data.id === target.data.id) {
      closeDetailModal();
    }
  };

  /* =========================================
     PAYMENTS
  ========================================= */

  const openPaymentModal = (invoice) => {
    setPaymentTarget(invoice);
    setPaymentAmount(String(invoice.outstandingAmount ?? ""));
    setPaymentMode("UPI");
    setPaymentRef("");
    setFormError("");
    setSuccessMessage("");
    setNotice("");
  };

  const closePaymentModal = () => {
    if (isSaving) {
      return;
    }
    setPaymentTarget(null);
    setFormError("");
  };

  const handleRecordPayment = async (event) => {
    event.preventDefault();

    const amount = Number(paymentAmount);
    if (paymentAmount === "" || !Number.isFinite(amount) || amount <= 0) {
      setFormError("Enter a payment amount greater than 0.");
      return;
    }
    if (toAmount(paymentTarget?.outstandingAmount) > 0 && amount > toAmount(paymentTarget.outstandingAmount)) {
      setFormError(
        `Payment (${formatMoney(amount)}) cannot exceed the outstanding balance (${formatMoney(
          paymentTarget.outstandingAmount
        )}).`
      );
      return;
    }

    setIsSaving(true);
    setFormError("");

    try {
      const payment = await createPayment({
        invoiceId: paymentTarget.id,
        amount,
        mode: paymentMode,
        transactionRef: paymentRef.trim() || null,
      });

      setPaymentTarget(null);
      setSuccessMessage(
        `Payment of ${formatMoney(payment.amount)} recorded against INV-${payment.invoiceId}. Invoice is now ${String(
          payment.invoiceStatus
        ).replace(/_/g, " ").toLowerCase()}, ${formatMoney(payment.invoiceOutstandingAmount)} outstanding.`
      );
      await loadDocuments({ showLoading: false });

      if (detailDocument?.type === "Invoice" && detailDocument.data.id === paymentTarget.id) {
        await openInvoiceDetail(paymentTarget);
      }
    } catch (error) {
      setFormError(error instanceof Error ? error.message : "Unable to record the payment.");
    } finally {
      setIsSaving(false);
    }
  };

  const handleDeletePayment = async (payment) => {
    if (
      !window.confirm(
        `Delete this ${formatMoney(payment.amount)} payment? The invoice balance is recalculated.`
      )
    ) {
      return;
    }

    const removed = await runAction(
      `payment-${payment.id}-delete`,
      async () => {
        await deletePayment(payment.id);
        return payment;
      },
      (entry) =>
        `Payment of ${formatMoney(entry.amount)} deleted. The invoice balance was recalculated.`
    );

    if (removed !== null && detailDocument?.type === "Invoice") {
      await openInvoiceDetail(detailDocument.data);
    }
  };

  /**
   * Bills a job card task's labour onto the invoice through the backend's
   * POST /invoices/{id}/labour. The description, quantity and price all come
   * from the task, and the server recalculates the invoice totals.
   */
  const handleAddLabour = async (taskId) => {
    const invoice = detailDocument?.data;
    if (!invoice || !taskId) {
      setDetailError("Choose a job card task to bill as labour.");
      return;
    }

    const added = await runAction(
      `invoice-${invoice.id}-labour`,
      () => addInvoiceLabour(invoice.id, taskId),
      (updated) => `Labour added to INV-${updated.id}. New total ${formatMoney(updated.total)}.`
    );

    if (added === null) {
      return;
    }

    setLabourTaskId("");
    if (detailDocument?.type === "Invoice") {
      await openInvoiceDetail(invoice);
    }
  };

  return (
    <>
      {/* =========================================
          BILLING PAGE
      ========================================= */}

      <div className="billing-page">

        {/* =========================================
            PAGE TOP
        ========================================= */}

        <div className="billing-page-top">

          <h1>
            BILLING &amp; PAYMENTS
          </h1>

          {canWriteEstimates && (
            <button
              type="button"
              className="billing-estimate-button"
              onClick={openCreateModal}
            >
              + NEW ESTIMATE
            </button>
          )}

        </div>


        {/* =========================================
            FEEDBACK
        ========================================= */}

        {successMessage && (
          <div
            className="billing-feedback billing-feedback-success"
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

        {notice && (
          <div
            className="billing-feedback billing-feedback-warning"
            role="status"
          >
            <span>{notice}</span>
            <button
              type="button"
              onClick={() => setNotice("")}
            >
              Dismiss
            </button>
          </div>
        )}

        {loadError && (
          <div
            className="billing-feedback billing-feedback-error"
            role="alert"
          >
            <span>{loadError}</span>
            <button
              type="button"
              onClick={() => void loadDocuments()}
            >
              Retry
            </button>
          </div>
        )}


        {/* =========================================
            STATISTICS
        ========================================= */}

        <div className="billing-stats">

          <div className="billing-stat">

            <span>
              THIS MONTH
            </span>

            <strong title="Total of invoices raised in the current calendar month, excluding cancelled invoices.">
              {formatMoney(statistics.monthTotal)}
            </strong>

          </div>


          <div className="billing-stat">

            <span>
              PENDING
            </span>

            <strong
              className="lime"
              title="Outstanding balance across every invoice that is not cancelled."
            >
              {formatMoney(statistics.pendingTotal)}
            </strong>

          </div>


          <div className="billing-stat">

            <span>
              OVERDUE
            </span>

            <strong title={`Outstanding on invoices dated more than ${OVERDUE_AFTER_DAYS} days ago. The backend has no overdue status, so this is derived from the invoice date and balance.`}>
              {formatMoney(statistics.overdueTotal)}
            </strong>

          </div>


          <div className="billing-stat">

            <span>
              AVG INVOICE
            </span>

            <strong title="Average total across all invoices raised, excluding cancelled invoices.">
              {formatMoney(statistics.average)}
            </strong>

          </div>

        </div>


        {/* =========================================
            TABS
        ========================================= */}

        <div className="billing-tabs" role="tablist">

          <button
            type="button"
            role="tab"
            aria-selected={activeTab === "invoices"}
            className={`billing-tab ${activeTab === "invoices" ? "active" : ""}`}
            onClick={() => setActiveTab("invoices")}
          >
            INVOICES ({invoices.length})
          </button>

          <button
            type="button"
            role="tab"
            aria-selected={activeTab === "estimates"}
            className={`billing-tab ${activeTab === "estimates" ? "active" : ""}`}
            onClick={() => setActiveTab("estimates")}
          >
            ESTIMATES ({estimates.length})
          </button>

        </div>


        {/* =========================================
            BILLING TABLE
        ========================================= */}

        <div className="billing-table-card">

          <div className="billing-table-wrapper">

            {activeTab === "invoices" ? (
              <table className="billing-table">

                <thead>

                  <tr>

                    <th>
                      INVOICE
                    </th>

                    <th>
                      CUSTOMER
                    </th>

                    <th>
                      VEHICLE
                    </th>

                    <th>
                      JOB CARD
                    </th>

                    <th>
                      DATE
                    </th>

                    <th>
                      AMOUNT
                    </th>

                    {/* Labelled without a fixed rate on purpose: the GST rate is
                        stored per document and is 18/5/12/28, so each cell shows
                        the rate that invoice was actually billed at instead of
                        the column claiming 18% for every row. */}
                    <th>
                      GST
                    </th>

                    <th>
                      TOTAL
                    </th>

                    <th>
                      STATUS
                    </th>

                    <th>
                      MODE
                    </th>

                    <th>
                      VIEW
                    </th>

                  </tr>

                </thead>


                <tbody>

                  {isLoading && (
                    <tr>
                      <td colSpan="11" className="billing-table-state">
                        Loading invoices…
                      </td>
                    </tr>
                  )}

                  {!isLoading && invoices.length === 0 && (
                    <tr>
                      <td colSpan="11" className="billing-table-state">
                        No invoices yet. Use “+ NEW ESTIMATE” to raise the first one.
                      </td>
                    </tr>
                  )}

                  {invoices.map((invoice) => {
                    const settled = isInvoiceSettled(invoice);
                    const overdue = isOverdue(invoice);
                    const busy = isBusy(`invoice-${invoice.id}-`);

                    return (
                      <tr key={invoice.id}>

                        <td className="billing-invoice">
                          INV-{invoice.id}
                        </td>

                        <td className="billing-customer">
                          {invoice.customerName || "—"}
                        </td>

                        <td>{invoice.vehicleInfo || "—"}</td>

                        <td>{jobCardNumber(invoice.jobCardId)}</td>

                        <td>{formatDate(invoice.invoiceDate)}</td>

                        <td className="billing-amount">
                          {formatMoney(invoice.subtotal)}
                        </td>

                        <td className="billing-gst">
                          {formatMoney(invoice.gst)}
                          {invoice.gstRate != null && (
                            <span className="billing-gst-rate">
                              {formatRate(invoice.gstRate)}%
                            </span>
                          )}
                        </td>

                        <td className="billing-total">
                          {formatMoney(invoice.total)}
                        </td>

                        <td>
                          <span
                            className={`billing-status ${invoiceStatusTone(invoice)}`}
                            title={
                              overdue
                                ? `Outstanding for more than ${OVERDUE_AFTER_DAYS} days. Derived on screen — the backend stores ${invoice.status}.`
                                : String(invoice.status || "")
                            }
                          >
                            {overdue
                              ? "Overdue"
                              : INVOICE_STATUS_LABELS[String(invoice.status || "").toUpperCase()] ||
                                invoice.status ||
                                "—"}
                          </span>
                        </td>

                        {/* Read off the invoice's most recent settled payment; an
                            invoice that has never been paid shows a dash rather
                            than an invented mode. */}
                        <td className="billing-mode">
                          {formatMode(paymentModeByInvoice.get(invoice.id)?.mode)}
                        </td>

                        <td>
                          <div className="billing-row-actions">

                            <button
                              type="button"
                              className="billing-view"
                              onClick={() => void openInvoiceDetail(invoice)}
                            >
                              View
                            </button>

                            {canWrite && !settled && !isInvoiceCancelled(invoice) && (
                              <button
                                type="button"
                                className="billing-action"
                                disabled={Boolean(busy)}
                                onClick={() => void openEditModal({ type: "Invoice", data: invoice })}
                              >
                                Edit
                              </button>
                            )}

                            {canWrite && canPayInvoice(invoice) && (
                              <button
                                type="button"
                                className="billing-action billing-action-primary"
                                disabled={Boolean(busy)}
                                onClick={() => openPaymentModal(invoice)}
                              >
                                Pay
                              </button>
                            )}

                            {canWrite && !settled && !isInvoiceCancelled(invoice) && (
                              <button
                                type="button"
                                className="billing-action billing-action-danger"
                                disabled={Boolean(busy)}
                                onClick={() => void handleCancelInvoice(invoice)}
                              >
                                Cancel
                              </button>
                            )}

                            {canWrite && !settled && (
                              <button
                                type="button"
                                className="billing-action billing-action-danger"
                                disabled={Boolean(busy)}
                                onClick={() => void handleDeleteDocument({ type: "Invoice", data: invoice })}
                              >
                                Delete
                              </button>
                            )}

                          </div>
                        </td>

                      </tr>
                    );
                  })}

                </tbody>

              </table>
            ) : (
              <table className="billing-table">

                <thead>

                  <tr>

                    <th>
                      ESTIMATE
                    </th>

                    <th>
                      JOB CARD
                    </th>

                    <th>
                      CREATED
                    </th>

                    <th>
                      AMOUNT
                    </th>

                    <th>
                      DISCOUNT
                    </th>

                    <th>
                      TAX
                    </th>

                    <th>
                      TOTAL
                    </th>

                    <th>
                      STATUS
                    </th>

                    <th></th>

                  </tr>

                </thead>


                <tbody>

                  {isLoading && (
                    <tr>
                      <td colSpan="9" className="billing-table-state">
                        Loading estimates…
                      </td>
                    </tr>
                  )}

                  {!isLoading && estimates.length === 0 && (
                    <tr>
                      <td colSpan="9" className="billing-table-state">
                        No estimates yet. Use “+ NEW ESTIMATE” to raise the first one.
                      </td>
                    </tr>
                  )}

                  {estimates.map((estimate) => {
                    const converted = isEstimateConverted(estimate);
                    const busy = isBusy(`estimate-${estimate.id}-`);

                    return (
                      <tr key={estimate.id}>

                        <td className="billing-invoice">
                          EST-{estimate.id}
                        </td>

                        <td className="billing-customer">
                          {estimate.jobCardNumber || jobCardNumber(estimate.jobCardId)}
                        </td>

                        <td>{formatDate(estimate.createdAt)}</td>

                        <td className="billing-amount">
                          {formatMoney(estimate.subtotal)}
                        </td>

                        <td>{formatMoney(estimate.discount)}</td>

                        <td>{formatMoney(estimate.tax)}</td>

                        <td className="billing-total">
                          {formatMoney(estimate.total)}
                        </td>

                        <td>
                          <span className={`billing-status ${estimateStatusTone(estimate)}`}>
                            {ESTIMATE_STATUS_LABELS[String(estimate.status || "").toUpperCase()] ||
                              estimate.status ||
                              "—"}
                          </span>
                        </td>

                        <td>
                          <div className="billing-row-actions">

                            <button
                              type="button"
                              className="billing-view"
                              onClick={() => void openEstimateDetail(estimate)}
                            >
                              View
                            </button>

                            {canWriteEstimates && !converted && (
                              <button
                                type="button"
                                className="billing-action"
                                disabled={Boolean(busy)}
                                onClick={() => void openEditModal({ type: "Estimate", data: estimate })}
                              >
                                Edit
                              </button>
                            )}

                            {canWrite && !converted && (
                              <button
                                type="button"
                                className="billing-action billing-action-primary"
                                disabled={Boolean(busy)}
                                onClick={() => handleConvertEstimate(estimate)}
                              >
                                Convert
                              </button>
                            )}

                            {canWriteEstimates && !converted && (
                              <button
                                type="button"
                                className="billing-action billing-action-danger"
                                disabled={Boolean(busy)}
                                onClick={() => void handleDeleteDocument({ type: "Estimate", data: estimate })}
                              >
                                Delete
                              </button>
                            )}

                          </div>
                        </td>

                      </tr>
                    );
                  })}

                </tbody>

              </table>
            )}

          </div>

        </div>

      </div>


      {/* =========================================
          NEW / EDIT ESTIMATE + INVOICE MODAL
      ========================================= */}

      {showDocumentModal && (

        <div
          className="billing-modal-overlay"
          onMouseDown={(e) => {

            if (
              e.target === e.currentTarget
            ) {
              closeDocumentModal();
            }

          }}
        >

          <div
            className="billing-estimate-modal"
            role="dialog"
            aria-modal="true"
            aria-label={`${editingDocument ? "Edit" : "New"} ${form.documentType}`}
          >

            {/* =====================================
                MODAL HEADER
            ===================================== */}

            <div className="billing-modal-header">

              <div>

                <div className="billing-modal-eyebrow">
                  {editingDocument ? "EDIT" : "NEW"} {form.documentType.toUpperCase()}
                </div>

              </div>

              <button
                type="button"
                className="billing-modal-close"
                onClick={closeDocumentModal}
              >
                ×
              </button>

            </div>


            {/* =====================================
                FORM
            ===================================== */}

            <form
              className="billing-estimate-form"
              onSubmit={handleSaveDocument}
            >

              {formError && (
                <div
                  className="billing-feedback billing-feedback-error"
                  role="alert"
                >
                  <span>{formError}</span>
                </div>
              )}


              {/* =================================
                  ESTIMATE / INVOICE
              =================================

                  Estimate creation also allows SERVICE_ADVISOR; invoice
                  creation does not (InvoiceController's guard), so the option
                  is hidden rather than offered and then refused with a 403. */}

              {!editingDocument && (
                <div className="billing-type-selector">

                  <label
                    className={`billing-type-option ${
                      form.documentType === "Estimate"
                        ? "selected"
                        : ""
                    }`}
                  >

                    <input
                      type="radio"
                      name="estimateType"
                      value="Estimate"
                      checked={
                        form.documentType === "Estimate"
                      }
                      onChange={(e) =>
                        patchForm({ documentType: e.target.value })
                      }
                    />

                    <span className="billing-radio-circle"></span>

                    <span>
                      Estimate
                    </span>

                  </label>


                  {canWrite && (
                    <label
                      className={`billing-type-option ${
                        form.documentType === "Invoice"
                          ? "selected"
                          : ""
                      }`}
                    >

                      <input
                        type="radio"
                        name="estimateType"
                        value="Invoice"
                        checked={
                          form.documentType === "Invoice"
                        }
                        onChange={(e) =>
                          patchForm({ documentType: e.target.value })
                        }
                      />

                      <span className="billing-radio-circle"></span>

                      <span>
                        Invoice
                      </span>

                    </label>
                  )}

                </div>
              )}


              {/* =================================
                  CUSTOMER
              ================================= */}

              <div className="billing-form-field">

                <label htmlFor="billing-customer">
                  CUSTOMER
                </label>

                <select
                  id="billing-customer"
                  value={form.customerId}
                  disabled={Boolean(editingDocument)}
                  onChange={(e) =>
                    handleCustomerChange(e.target.value)
                  }
                >

                  <option value="">
                    Select customer...
                  </option>

                  {customers.map((option) => (
                    <option key={option.id} value={option.id}>
                      {option.name}
                      {option.phone ? ` — ${option.phone}` : ""}
                    </option>
                  ))}

                </select>

              </div>


              {/* =================================
                  JOB CARD
              ================================= */}

              <div className="billing-form-field">

                <label htmlFor="billing-job-card">
                  JOB CARD *
                </label>

                <select
                  id="billing-job-card"
                  value={form.jobCardId}
                  disabled={!form.customerId || Boolean(editingDocument)}
                  onChange={(e) =>
                    patchForm({ jobCardId: e.target.value })
                  }
                >

                  <option value="">
                    {form.customerId ? "Select job card..." : "Select a customer first"}
                  </option>

                  {customerJobCards.map((jobCard) => (
                    <option key={jobCard.id} value={jobCard.id}>
                      {jobCard.jobCardNumber}
                      {jobCard.serviceType ? ` — ${jobCard.serviceType}` : ""}
                      {jobCard.vehicleInfo ? ` (${jobCard.vehicleInfo})` : ""}
                    </option>
                  ))}

                </select>

                {form.customerId && customerJobCards.length === 0 && (
                  <span className="billing-hint">
                    This customer has no job cards yet, so nothing can be billed against
                    them.
                  </span>
                )}

              </div>


              {/* =================================
                  TASK-SOURCED LABOUR (READ ONLY)
              =================================

                  These lines were generated by the backend from a job card
                  task through POST /invoices/{id}/labour. They are not sent
                  back on save — InvoiceServiceImpl.replaceItems re-creates
                  them itself — so they are shown here rather than editable. */}

              {form.fixedLabour.length > 0 && (
                <div className="billing-fixed-labour">

                  <div className="billing-section-label">
                    JOB CARD LABOUR
                  </div>

                  {form.fixedLabour.map((item) => (
                    <div className="billing-detail-row" key={item.id}>
                      <span>
                        {item.description} · {item.quantity} ×{" "}
                        {formatMoney(item.unitPrice)} = {formatMoney(item.lineAmount)}
                      </span>
                      <span className="billing-detail-tag">task #{item.jobTaskId}</span>
                    </div>
                  ))}

                  <span className="billing-hint">
                    Billed from job card tasks. The server keeps these in place when
                    this invoice is updated.
                  </span>

                </div>
              )}


              {/* =================================
                  LINE ITEMS
              ================================= */}

              <div className="billing-line-items">

                <div className="billing-section-label">
                  LINE ITEMS
                </div>


                {form.lineItems.map((item, index) => {
                  const quantity = Number(item.qty);
                  const price = Number(item.price);
                  const lineAmount =
                    Number.isFinite(quantity) &&
                    Number.isFinite(price) &&
                    quantity > 0 &&
                    price >= 0
                      ? quantity * price
                      : null;

                  return (
                    <div
                      className="billing-line-item"
                      key={index}
                    >

                      <input
                        type="text"
                        placeholder="Item / service"
                        aria-label={`Line ${index + 1} description`}
                        value={item.description}
                        onChange={(e) =>
                          updateLineItem(
                            index,
                            "description",
                            e.target.value
                          )
                        }
                      />

<input
                          type="number"
                          min="1"
                          step="1"
                          placeholder="Qty"
                          title="Quantity"
                          aria-label={`Line ${index + 1} quantity`}
                          value={item.qty}
                          onChange={(e) =>
                            updateLineItem(
                              index,
                              "qty",
                              e.target.value
                            )
                          }
                        />

                        <input
                          type="number"
                          min="0"
                          step="0.01"
                          placeholder="Price"
                          title="Unit price in rupees"
                          aria-label={`Line ${index + 1} unit price`}
                          value={item.price}
                          onChange={(e) =>
                            updateLineItem(
                              index,
                              "price",
                              e.target.value
                            )
                          }
                        />

                      <select
                        aria-label={`Line ${index + 1} category`}
                        value={item.category}
                        onChange={(e) =>
                          updateLineItem(
                            index,
                            "category",
                            e.target.value
                          )
                        }
                      >
                        {BILLING_ITEM_CATEGORIES.map((category) => (
                          <option key={category} value={category}>
                            {CATEGORY_LABELS[category]}
                          </option>
                        ))}
                      </select>

                      <span className="billing-line-total">
                        {lineAmount === null ? "₹ —" : formatLineMoney(lineAmount)}
                      </span>

                      <button
                        type="button"
                        className="billing-line-remove"
                        aria-label={`Remove line ${index + 1}`}
                        title={`Remove line ${index + 1}`}
                        disabled={form.lineItems.length === 1}
                        onClick={() => removeLineItem(index)}
                      >
                        ×
                      </button>

                    </div>
                  );
                })}


                <button
                  type="button"
                  className="billing-add-line"
                  onClick={addLineItem}
                >
                  + Add Line Item
                </button>

              </div>


              {/* =================================
                  DISCOUNT + PREVIEW
              ================================= */}

              <div className="billing-form-grid">

                <div className="billing-form-field">

                  <label htmlFor="billing-discount">
                    DISCOUNT (₹)
                  </label>

                  <input
                    id="billing-discount"
                    type="number"
                    min="0"
                    step="0.01"
                    value={form.discount}
                    onChange={(e) =>
                      patchForm({ discount: e.target.value })
                    }
                  />

                </div>


                <div className="billing-form-field">

                  <label htmlFor="billing-gst-rate">
                    GST RATE (%)
                  </label>

                  <select
                    id="billing-gst-rate"
                    value={form.gstRate}
                    onChange={(e) =>
                      patchForm({ gstRate: e.target.value })
                    }
                  >
                    {GST_RATES.map((rate) => (
                      <option key={rate} value={rate}>
                        {rate}
                      </option>
                    ))}
                  </select>

                  <span className="billing-hint" id="billing-gst-hint">
                    Applied by the server to the post-discount amount. GST and the
                    grand total are calculated on save.
                  </span>

                </div>

              </div>


              {/* =================================
                  PAYMENT MODE
              =================================

                  Full-width row directly under Discount / GST Rate, matching the
                  reference layout. It sits OUTSIDE the "record payment" panel and
                  is gated on NEITHER the checkbox NOR the Estimate/Invoice
                  selector, because the single "+ NEW ESTIMATE" button opens this
                  modal on documentType "Estimate" (the emptyForm default). Any
                  gate on documentType therefore hid the field on open, which is
                  exactly the "missing payment box" this replaces.

                  Showing it on an Estimate is safe: it is only a local form
                  selection. handleSaveDocument reads it exclusively under
                  `isInvoice && !isEditing`, and it is never added to the
                  estimate payload — EstimateRequestDTO has no payment field, so
                  nothing unsupported is sent. An edit stays excluded because
                  payments are recorded on create only; the separate Record
                  Payment dialog covers an invoice that already exists. */}

              {!editingDocument && (
                <div className="billing-form-field">

                  <label htmlFor="billing-payment-mode">
                    PAYMENT MODE
                  </label>

                  <select
                    id="billing-payment-mode"
                    value={form.paymentMode}
                    onChange={(e) =>
                      patchForm({ paymentMode: e.target.value })
                    }
                  >
                    {PAYMENT_MODES.map((mode) => (
                      <option key={mode} value={mode}>
                        {PAYMENT_MODE_LABELS[mode] || mode}
                      </option>
                    ))}
                  </select>

                </div>
              )}


              {/* =================================
                  RECORD PAYMENT ON SAVE
              ================================= */}

              {form.documentType === "Invoice" && !editingDocument && (
                <div className="billing-payment-inline">

                  <label className="billing-checkbox">
                    <input
                      type="checkbox"
                      checked={form.recordPayment}
                      onChange={(e) =>
                        patchForm({ recordPayment: e.target.checked })
                      }
                    />
                    Record a payment immediately after saving this invoice
                  </label>

                  {form.recordPayment && (
                    <div className="billing-payment-fields">

                      {form.paymentMode !== PAY_LATER && (
                        <div className="billing-form-field">

                          <label htmlFor="billing-payment-amount">
                            PAYMENT AMOUNT (₹)
                          </label>

                          <input
                            id="billing-payment-amount"
                            type="number"
                            min="0"
                            step="0.01"
                            placeholder="Leave blank to pay nothing"
                            value={form.paymentAmount}
                            onChange={(e) =>
                              patchForm({ paymentAmount: e.target.value })
                            }
                          />

                        </div>
                      )}

                    </div>
                  )}

                  <span className="billing-hint">
                    {form.paymentMode === PAY_LATER
                      ? "The backend has no deferred-payment concept, so Pay Later saves the invoice unpaid and records no payment. The full total stays outstanding."
                      : "Recorded against a separate payment via POST /payments — an invoice has no payment mode of its own."}
                  </span>

                </div>
              )}


              {/* =================================
                  NOTES
              =================================

                  Persisted: InvoiceRequestDTO/EstimateRequestDTO carry a
                  `notes` field, stored in invoices.notes / estimates.notes and
                  returned on read, so what is typed here survives a save and
                  comes back when the document is reopened. */}

              <div className="billing-form-field">

                <label htmlFor="billing-notes">
                  NOTES
                </label>

                <textarea
                  id="billing-notes"
                  className="billing-notes"
                  placeholder="Payment terms, warranty, parts ETA…"
                  value={form.notes}
                  maxLength={NOTES_MAX_LENGTH}
                  onChange={(e) =>
                    patchForm({ notes: e.target.value })
                  }
                  aria-describedby="billing-notes-hint"
                />

                <span className="billing-hint" id="billing-notes-hint">
                  {form.notes.length > 0
                    ? `${form.notes.length}/${NOTES_MAX_LENGTH} — saved with this ${
                        form.documentType.toLowerCase()
                      }.`
                    : `Optional. Up to ${NOTES_MAX_LENGTH} characters; saved with this ${
                        form.documentType.toLowerCase()
                      }.`}
                </span>

              </div>


              {/* =================================
                  ACTIONS
              ================================= */}

              <div className="billing-modal-actions">

                <button
                  type="submit"
                  className="billing-modal-create"
                  disabled={isSaving}
                >
                  {isSaving
                    ? "SAVING…"
                    : `${editingDocument ? "UPDATE" : "SAVE"} ${form.documentType.toUpperCase()}`}
                </button>

                <button
                  type="button"
                  className="billing-modal-cancel"
                  disabled={isSaving}
                  onClick={closeDocumentModal}
                >
                  Cancel
                </button>

              </div>

            </form>

          </div>

        </div>

      )}


      {/* =========================================
          DETAIL MODAL
      ========================================= */}

      {detailDocument && (

        <div
          className="billing-modal-overlay"
          onMouseDown={(e) => {

            if (
              e.target === e.currentTarget
            ) {
              closeDetailModal();
            }

          }}
        >

          <div
            className="billing-detail-modal"
            role="dialog"
            aria-modal="true"
            aria-label={
              detailDocument.type === "Invoice"
                ? `Invoice INV-${detailDocument.data.id}`
                : `Estimate #${detailDocument.data.id}`
            }
          >

            <div className="billing-modal-header">

              <div>

                <div className="billing-modal-eyebrow">
                  {detailDocument.type === "Invoice"
                    ? `INVOICE INV-${detailDocument.data.id}`
                    : `ESTIMATE #${detailDocument.data.id}`}
                </div>

              </div>

              <button
                type="button"
                className="billing-modal-close"
                onClick={closeDetailModal}
              >
                ×
              </button>

            </div>


            <div className="billing-detail-body">

              {/* PDF/Excel exports used to sit in every row of the billing list.
                  The Figma table has a single VIEW action, so they moved here to
                  the existing document modal: out of the list, still reachable,
                  and no new modal was introduced. The endpoints and the service
                  functions are untouched. */}
              {detailDocument.type === "Invoice" && (
                <div className="billing-detail-exports">
                  <button
                    type="button"
                    className="billing-action"
                    onClick={() =>
                      handleDownload(detailDocument.data, "pdf")
                    }
                  >
                    PDF
                  </button>

                  <button
                    type="button"
                    className="billing-action"
                    onClick={() =>
                      handleDownload(detailDocument.data, "xlsx")
                    }
                  >
                    Excel
                  </button>
                </div>
              )}

              {detailError && (
                <div
                  className="billing-feedback billing-feedback-error"
                  role="alert"
                >
                  <span>{detailError}</span>
                </div>
              )}


              {detailDocument.type === "Invoice" && (
                <div className="billing-detail-meta">
                  <div>
                    <span>STATUS</span>
                    <strong>
                      {INVOICE_STATUS_LABELS[String(detailDocument.data.status).toUpperCase()] ||
                        detailDocument.data.status}
                    </strong>
                  </div>
                  <div>
                    <span>CUSTOMER</span>
                    <strong>{detailDocument.data.customerName || "—"}</strong>
                  </div>
                  <div>
                    <span>VEHICLE</span>
                    <strong>{detailDocument.data.vehicleInfo || "—"}</strong>
                  </div>
                  <div>
                    <span>JOB CARD</span>
                    <strong>{jobCardNumber(detailDocument.data.jobCardId)}</strong>
                  </div>
                  <div>
                    <span>DATE</span>
                    <strong>{formatDate(detailDocument.data.invoiceDate)}</strong>
                  </div>
                  {detailDocument.data.estimateId && (
                    <div>
                      <span>FROM ESTIMATE</span>
                      <strong>#{detailDocument.data.estimateId}</strong>
                    </div>
                  )}
                </div>
              )}


              {detailDocument.type === "Estimate" && (
                <div className="billing-detail-meta">
                  <div>
                    <span>STATUS</span>
                    <strong>
                      {ESTIMATE_STATUS_LABELS[String(detailDocument.data.status).toUpperCase()] ||
                        detailDocument.data.status}
                    </strong>
                  </div>
                  <div>
                    <span>JOB CARD</span>
                    <strong>
                      {detailDocument.data.jobCardNumber ||
                        jobCardNumber(detailDocument.data.jobCardId)}
                    </strong>
                  </div>
                  <div>
                    <span>CREATED</span>
                    <strong>{formatDate(detailDocument.data.createdAt)}</strong>
                  </div>
                </div>
              )}


              <table className="billing-detail-table">

                <thead>
                  <tr>
                    <th>DESCRIPTION</th>
                    <th>CATEGORY</th>
                    <th>QTY</th>
                    <th>UNIT PRICE</th>
                    <th>AMOUNT</th>
                  </tr>
                </thead>

                <tbody>
                  {(detailDocument.data.items || []).map((item) => (
                    <tr key={item.id}>
                      <td>
                        {item.description}
                        {item.jobTaskId ? (
                          <span className="billing-detail-tag">job task #{item.jobTaskId}</span>
                        ) : null}
                      </td>
                      <td>{item.category || "PART"}</td>
                      <td>{item.quantity}</td>
                      <td>{formatMoney(item.unitPrice)}</td>
                      <td>{formatMoney(item.lineAmount)}</td>
                    </tr>
                  ))}

                  {(detailDocument.data.items || []).length === 0 && (
                    <tr>
                      <td colSpan="5" className="billing-table-state">
                        This document has no line items.
                      </td>
                    </tr>
                  )}
                </tbody>

              </table>


              <div className="billing-detail-totals">

                <div className="billing-preview-row">
                  <span>Subtotal</span>
                  <strong>{formatMoney(detailDocument.data.subtotal)}</strong>
                </div>

                <div className="billing-preview-row">
                  <span>Discount</span>
                  <strong>− {formatMoney(detailDocument.data.discount)}</strong>
                </div>

                <div className="billing-preview-row">
                  <span>
                    {detailDocument.type === "Invoice" ? "GST" : "Tax"}
                    {detailDocument.data.gstRate != null
                      ? ` (${formatRate(detailDocument.data.gstRate)}%)`
                      : ""}
                  </span>
                  <strong>
                    {formatMoney(
                      detailDocument.type === "Invoice"
                        ? detailDocument.data.gst
                        : detailDocument.data.tax
                    )}
                  </strong>
                </div>

                <div className="billing-preview-row billing-preview-total">
                  <span>Total</span>
                  <strong>{formatMoney(detailDocument.data.total)}</strong>
                </div>

                {detailDocument.type === "Invoice" && (
                  <>
                    <div className="billing-preview-row">
                      <span>Paid</span>
                      <strong>{formatMoney(detailDocument.data.amountPaid)}</strong>
                    </div>

                    <div className="billing-preview-row billing-preview-total">
                      <span>Outstanding</span>
                      <strong>{formatMoney(detailDocument.data.outstandingAmount)}</strong>
                    </div>
                  </>
                )}

              </div>


              {/* Rendered only when the document actually carries notes, so a
                  document without them looks the same as it always did. */}
              {detailDocument.data.notes && (
                <>
                  <div className="billing-section-label">
                    NOTES
                  </div>

                  <div className="billing-notes-view">{detailDocument.data.notes}</div>
                </>
              )}


              {detailDocument.type === "Invoice" && (
                <>
                  <div className="billing-section-label">
                    PAYMENTS
                  </div>

                  {isDetailLoading && (
                    <div className="billing-hint">Loading…</div>
                  )}

                  {!isDetailLoading && detailPayments.length === 0 && (
                    <div className="billing-hint">
                      No payments recorded against this invoice.
                    </div>
                  )}

                  {!isDetailLoading &&
                    detailPayments.map((payment) => (
                      <div className="billing-detail-row" key={payment.id}>
                        <span>
                          {formatMoney(payment.amount)} · {formatMode(payment.mode)} ·{" "}
                          {formatDate(payment.paidAt || payment.createdAt)}
                          {payment.transactionRef
                            ? ` · ref ${payment.transactionRef}`
                            : ""}
                        </span>

                        {canWrite && (
                          <button
                            type="button"
                            className="billing-action billing-action-danger"
                            disabled={isBusy(`payment-${payment.id}-delete`)}
                            onClick={() => void handleDeletePayment(payment)}
                          >
                            Delete
                          </button>
                        )}
                      </div>
                    ))}


                  {/* Billing a job card task's labour uses the backend's own
                      POST /invoices/{id}/labour, which derives the line from the
                      task and recalculates the totals. */}
                  {canWrite && canPayInvoice(detailDocument.data) && (
                    <div className="billing-labour">

                      <label htmlFor="billing-labour-task">
                        BILL LABOUR FROM JOB CARD TASK
                      </label>

                      <div className="billing-labour-controls">

                        <select
                          id="billing-labour-task"
                          value={labourTaskId}
                          disabled={detailTasks.length === 0}
                          onChange={(e) => setLabourTaskId(e.target.value)}
                        >
                          <option value="">
                            {detailTasks.length === 0
                              ? "This job card has no tasks"
                              : "Select a task..."}
                          </option>

                          {detailTasks.map((task) => (
                            <option key={task.id} value={task.id}>
                              {task.description}
                              {task.labourCost == null
                                ? ""
                                : ` — ${formatMoney(task.labourCost)}`}
                            </option>
                          ))}
                        </select>

                        <button
                          type="button"
                          className="billing-action billing-action-primary"
                          disabled={
                            detailTasks.length === 0 ||
                            !labourTaskId ||
                            busyActions.length > 0
                          }
                          onClick={() => handleAddLabour(labourTaskId)}
                        >
                          Add labour
                        </button>

                      </div>

                    </div>
                  )}
                </>
              )}


              {detailDocument.type === "Estimate" && (
                <div className="billing-hint">
                  The backend only generates documents for invoices
                  (PDF and Excel). Estimates have no printable endpoint, so they
                  cannot be downloaded or printed from here.
                </div>
              )}

            </div>

          </div>

        </div>

      )}


      {/* =========================================
          RECORD PAYMENT MODAL
      ========================================= */}

      {paymentTarget && (

        <div
          className="billing-modal-overlay"
          onMouseDown={(e) => {

            if (
              e.target === e.currentTarget
            ) {
              closePaymentModal();
            }

          }}
        >

          <div
            className="billing-estimate-modal"
            role="dialog"
            aria-modal="true"
            aria-label={`Record payment for invoice INV-${paymentTarget.id}`}
          >

            <div className="billing-modal-header">

              <div>
                <div className="billing-modal-eyebrow">
                  RECORD PAYMENT — INV-{paymentTarget.id}
                </div>
              </div>

              <button
                type="button"
                className="billing-modal-close"
                onClick={closePaymentModal}
              >
                ×
              </button>

            </div>


            <form
              className="billing-estimate-form"
              onSubmit={handleRecordPayment}
            >

              {formError && (
                <div
                  className="billing-feedback billing-feedback-error"
                  role="alert"
                >
                  <span>{formError}</span>
                </div>
              )}


              <div className="billing-preview">
                <div className="billing-preview-row">
                  <span>Invoice total</span>
                  <strong>{formatMoney(paymentTarget.total)}</strong>
                </div>
                <div className="billing-preview-row">
                  <span>Already paid</span>
                  <strong>{formatMoney(paymentTarget.amountPaid)}</strong>
                </div>
                <div className="billing-preview-row billing-preview-total">
                  <span>Outstanding</span>
                  <strong>{formatMoney(paymentTarget.outstandingAmount)}</strong>
                </div>
              </div>


              <div className="billing-form-grid">

                <div className="billing-form-field">

                  <label htmlFor="billing-record-amount">
                    AMOUNT (₹)
                  </label>

                  <input
                    id="billing-record-amount"
                    type="number"
                    min="0"
                    step="0.01"
                    value={paymentAmount}
                    onChange={(e) => setPaymentAmount(e.target.value)}
                  />

                </div>


                <div className="billing-form-field">

                  <label htmlFor="billing-record-mode">
                    PAYMENT MODE
                  </label>

                  <select
                    id="billing-record-mode"
                    value={paymentMode}
                    onChange={(e) => setPaymentMode(e.target.value)}
                  >
                    {/* Pay Later is deliberately absent here: this dialog records
                        money that was actually received, and there is nothing
                        received to record under "Pay Later". */}
                    {PAYMENT_MODES.filter((mode) => mode !== PAY_LATER).map((mode) => (
                      <option key={mode} value={mode}>
                        {PAYMENT_MODE_LABELS[mode] || mode}
                      </option>
                    ))}
                  </select>

                </div>

              </div>


              <div className="billing-form-field">

                <label htmlFor="billing-record-ref">
                  TRANSACTION REFERENCE
                </label>

                <input
                  id="billing-record-ref"
                  type="text"
                  placeholder="Optional"
                  value={paymentRef}
                  onChange={(e) => setPaymentRef(e.target.value)}
                />

              </div>


              <div className="billing-hint">
                The invoice status is derived from the balance once the payment is
                saved: fully paid becomes PAID, a part payment becomes PARTIALLY_PAID.
                It cannot be set by hand.
              </div>


              <div className="billing-modal-actions">

                <button
                  type="submit"
                  className="billing-modal-create"
                  disabled={isSaving}
                >
                  {isSaving ? "RECORDING…" : "RECORD PAYMENT"}
                </button>

                <button
                  type="button"
                  className="billing-modal-cancel"
                  disabled={isSaving}
                  onClick={closePaymentModal}
                >
                  Cancel
                </button>

              </div>

            </form>

          </div>

        </div>

      )}

    </>
  );
}

export default GarageOwnerBilling;
