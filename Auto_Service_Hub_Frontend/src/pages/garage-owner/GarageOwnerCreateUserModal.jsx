/* =========================================================
   GARAGE OWNER — CREATE USER MODAL
   (Part 5.3 fields, Part 5.4 rules, Part 5.5 error display)

   The dialog behind the "+ CREATE USER" button on the User
   Management page: header, the account fields and the footer
   actions.

   The fields are full name, username, email, phone number,
   role, password, password confirmation and account status.
   They live in component state, and the dialog never calls the
   API itself: it validates, then hands the account up through
   onSubmit in the shape the create endpoint expects.

   The form is checked before it is handed back: the required
   fields, the email format and the password match (Part 5.4).
   onSubmit only runs once everything passes.

   Whatever those checks turn up is shown underneath the field it
   belongs to, and the field itself carries the existing error
   styling plus aria-invalid / aria-describedby, so the messages
   are announced as well as seen (Part 5.5).

   What the dialog does with the outcome it is handed back:

     submitting     while the create request is in flight the
                    fields, Cancel and the close icon are held, and
                    Escape and the backdrop stop closing the dialog,
                    so a post is never abandoned half-way
     submitError    the API's own message, shown above the footer:
                    the backend's field validation text, or the
                    conflict raised because the username or email is
                    already taken

   Props
     open           boolean  — when false the dialog renders nothing
     onClose        function — called by the close icon, Cancel and
                               the backdrop
     onSubmit       function — called by the Create User submit, with
                               the account fields in the API's shape
     onClearSubmitError
                    function — called when a field changes, so a stale
                               API error is dropped
     roleOptions    string[] — role choices for the Role select.
                               The page supplies the list as it
                               stands, and Owner is sent as
                               GARAGE_OWNER, the name the roles
                               table stores it under
     statusOptions  string[] — status choices for the Account Status
                               select
     submitting     boolean  — true while the create request is running
     submitError    string   — API error to show in the dialog
========================================================= */

import React, { useEffect, useState } from "react";


/* =========================================================
   ACCOUNT STATUS
   A new account always starts on Active.
========================================================= */

const DEFAULT_STATUS = "Active";


/* =========================================================
   EMPTY FORM
   One key per field of the dialog, so a single change handler can
   serve them all. Reopening the modal starts from a clean form.
   Every key is checked by the validation helpers below.
========================================================= */

const emptyForm = () => ({
  fullName: "",
  username: "",
  email: "",
  phone: "",
  role: "",
  password: "",
  confirmPassword: "",
  status: DEFAULT_STATUS,
});


/* =========================================================
   OPTION LISTS
   The role and status choices belong to the page, so the modal
   renders whichever lists it is given, exactly as they stand:
   the workshop's role list is the single source of the role
   choices and nothing is filtered out of it here.
========================================================= */

const optionsOrEmpty = (options) => (Array.isArray(options) ? options : []);

/* =========================================================
   VALIDATION HELPERS   (Part 5.4)
   - Email pattern matches: something@something.tld
   - Required-fields list drives the empty checks, and the
     matching map supplies the message for each of them
   - A text field holding only whitespace counts as empty
========================================================= */

const EMAIL_PATTERN = /^[^\s@]+@[^\s@]+\.[^\s@]+$/;

const REQUIRED_FIELDS = [
  "fullName",
  "username",
  "email",
  "phone",
  "role",
  "password",
  "confirmPassword",
];

const REQUIRED_MESSAGES = {
  fullName: "Full Name is required.",
  username: "Username is required.",
  email: "Email is required.",
  phone: "Phone Number is required.",
  role: "Select a role.",
  password: "Password is required.",
  confirmPassword: "Confirm Password is required.",
};


/* =========================================================
   THE ROLE AS THE API STORES IT

   The Role list offers Owner, and the roles table keeps the
   garage owner's row under the name GARAGE_OWNER. The Owner
   choice is sent under that name, so the account is linked to
   the row that already exists — choosing Owner neither creates
   nor renames a role.

   Every other choice travels exactly as it was chosen: the
   endpoint upper-cases the name and reduces separators to
   underscores, so "Service Advisor" reaches SERVICE_ADVISOR
   without the browser rewriting anything.
========================================================= */

const ROLE_REQUEST_VALUES = {
  Owner: "GARAGE_OWNER",
};

const requestedRoleValue = (roleLabel) =>
  ROLE_REQUEST_VALUES[roleLabel] || roleLabel;


/* =========================================================
   FIELD ERROR MESSAGE   (Part 5.5)
   Rendered straight under the field it belongs to, using the
   error style already defined for this modal. Renders nothing
   while the field is valid, and the field it describes points
   at it through aria-describedby.
========================================================= */

function FieldError(props) {
  const { id, message } = props;

  if (!message) {
    return null;
  }

  return (
    <span className="garage-users-modal-error" id={id} role="alert">
      {message}
    </span>
  );
}


function GarageOwnerCreateUserModal(props) {
  const {
    open,
    onClose,
    onSubmit,
    onClearSubmitError,
    roleOptions,
    statusOptions,
    submitting,
    submitError,
  } = props;

  const [form, setForm] = useState(emptyForm);
  const [errors, setErrors] = useState({});

  const roleChoices = optionsOrEmpty(roleOptions);
  const statusChoices = optionsOrEmpty(statusOptions);

  /* Both props are optional, so a caller that passes neither gets
     the old behaviour: an enabled form and no API error shown. */
  const isSubmitting = Boolean(submitting);


  /* =========================================================
     REOPENING STARTS FROM A CLEAN FORM
  ========================================================= */

  useEffect(() => {
    if (open) {
      setForm(emptyForm());
      setErrors({});
    }
  }, [open]);

  /* =========================================================
     ESC CLOSES THE DIALOG
     Same keydown pattern as the other modals in this module.
  ========================================================= */

  useEffect(() => {
    if (!open) return undefined;

    const handleKeyDown = (event) => {
      if (event.key === "Escape" && !isSubmitting) {
        onClose();
      }
    };

    window.addEventListener("keydown", handleKeyDown);

    return () => window.removeEventListener("keydown", handleKeyDown);
  }, [open, onClose, isSubmitting]);


  /* =========================================================
     CLOSED STATE — render nothing
  ========================================================= */

  if (!open) {
    return null;
  }


  /* =========================================================
     INVALID FIELD HELPERS   (Part 5.5)
     The error the form is holding for a field drives everything
     the user sees and hears about it: the red styling of the
     field and of its label, and the message printed under it.
  ========================================================= */

  const isInvalid = (name) => Boolean(errors[name]);

  const fieldClass = (name) =>
    isInvalid(name)
      ? "garage-users-modal-field error"
      : "garage-users-modal-field";

  const inputClass = (name) => (isInvalid(name) ? "error" : undefined);


  /* =========================================================
     FIELD CHANGES
     Every input and select is controlled: the field's own name is
     the key it writes to in the form state.
  ========================================================= */

  const validateField = (name, value, currentForm) => {
    const nextErrors = {};
    const text = value.trim();

    if (name === "fullName") {
      if (!text) nextErrors.fullName = REQUIRED_MESSAGES.fullName;
    } else if (name === "username") {
      if (!text) nextErrors.username = REQUIRED_MESSAGES.username;
    } else if (name === "email") {
      if (!text) {
        nextErrors.email = REQUIRED_MESSAGES.email;
      } else if (!EMAIL_PATTERN.test(text)) {
        nextErrors.email = "Enter a valid email address.";
      }
    } else if (name === "phone") {
      if (!text) nextErrors.phone = REQUIRED_MESSAGES.phone;
    } else if (name === "role") {
      if (!text) nextErrors.role = REQUIRED_MESSAGES.role;
    } else if (name === "password") {
      if (!text) {
        nextErrors.password = REQUIRED_MESSAGES.password;
      } else if (currentForm.confirmPassword.trim() && value !== currentForm.confirmPassword) {
        nextErrors.confirmPassword = "Passwords do not match.";
      }
    } else if (name === "confirmPassword") {
      if (!text) {
        nextErrors.confirmPassword = REQUIRED_MESSAGES.confirmPassword;
      } else if (currentForm.password.trim() && value !== currentForm.password) {
        nextErrors.confirmPassword = "Passwords do not match.";
      }
    }

    return nextErrors;
  };

  const handleChange = (event) => {
    const { name, value } = event.target;

    /* The API error described the account that was submitted, so
       the first edit makes it out of date — same as the other
       modals in this module. */
    if (typeof onClearSubmitError === "function") {
      onClearSubmitError();
    }

    setForm((previous) => {
      const nextForm = { ...previous, [name]: value };

      setErrors((previousErrors) => {
        const fieldErrors = validateField(name, value, nextForm);
        const nextErrors = { ...previousErrors };
        const isPasswordField =
          name === "password" || name === "confirmPassword";

        /* The corrected field always drops its own error, and a
           password change re-checks the pair with the values that
           were just typed, so a match clears the mismatch error. */
        delete nextErrors[name];

        if (isPasswordField) {
          delete nextErrors.password;
          delete nextErrors.confirmPassword;
        }

        Object.assign(nextErrors, fieldErrors);

        return nextErrors;
      });

      return nextForm;
    });
  };


  /* =========================================================
     WHOLE-FORM VALIDATION
     Called on submit. Every required field has to hold something
     other than whitespace, the email has to look like an address,
     and the two passwords have to match. The messages are the
     same ones the per-field checks use, so submit and typing can
     never disagree.
  ========================================================= */

  const validateForm = (currentForm) => {
    const validationErrors = {};

    REQUIRED_FIELDS.forEach((field) => {
      if (!currentForm[field].trim()) {
        validationErrors[field] = REQUIRED_MESSAGES[field];
      }
    });

    if (!validationErrors.email && !EMAIL_PATTERN.test(currentForm.email.trim())) {
      validationErrors.email = "Enter a valid email address.";
    }

    if (
      currentForm.password.trim() &&
      currentForm.confirmPassword.trim() &&
      currentForm.password !== currentForm.confirmPassword
    ) {
      validationErrors.confirmPassword = "Passwords do not match.";
    }

    return validationErrors;
  };


  /* =========================================================
     THE ACCOUNT HANDED UP

     The same seven fields UserRequestDTO takes, and nothing else.
     The text fields are trimmed; the password is not — it is
     stored exactly as typed. Confirm Password stays in the
     browser: the API has no field for it, and it is only ever a
     second reading of what was already typed.

     The role is sent as the name the roles table stores it under:
     Owner becomes GARAGE_OWNER, which is the row the garage owner
     is kept under. Every other choice is passed through unchanged
     and resolved by the endpoint, so "Service Advisor" reaches
     SERVICE_ADVISOR without the browser rewriting it.
  ========================================================= */

  const buildAccount = (currentForm) => {
    const roleLabel = currentForm.role.trim();

    return {
      fullName: currentForm.fullName.trim(),
      username: currentForm.username.trim(),
      email: currentForm.email.trim(),
      phone: currentForm.phone.trim(),
      role: requestedRoleValue(roleLabel),
      password: currentForm.password,
      status: currentForm.status.trim(),
    };
  };


  /* =========================================================
      SUBMIT
      Validate first; if any errors exist, prevent submission.
      onSubmit is called only when all fields are valid, and not
      while a previous create request is still running.
   ========================================================= */

  const handleSubmit = (event) => {
    event.preventDefault();

    if (isSubmitting) {
      return;
    }

    const validationErrors = validateForm(form);
    if (validationErrors && Object.keys(validationErrors).length > 0) {
      setErrors(validationErrors);
      return;
    }

    setErrors({});

    if (typeof onSubmit === "function") {
      onSubmit(buildAccount(form));
    }
  };


  return (
    <div
      className="garage-users-modal-overlay"
      onMouseDown={(event) => {
        // Only a click on the backdrop itself closes the dialog,
        // and never while a create request is in flight; clicks
        // inside the panel keep it open.
        if (event.target === event.currentTarget && !isSubmitting) {
          onClose();
        }
      }}
    >

      <div
        className="garage-users-modal"
        role="dialog"
        aria-modal="true"
        aria-labelledby="garage-users-modal-title"
      >

        <div className="garage-users-modal-header">

          <h2 id="garage-users-modal-title">
            Create User
          </h2>

          <button
            type="button"
            className="garage-users-modal-close"
            onClick={onClose}
            disabled={isSubmitting}
            aria-label="Close create user dialog"
          >
            ×
          </button>

        </div>


        <form className="garage-users-modal-form" onSubmit={handleSubmit}>

          {/* =====================================================
              ACCOUNT FIELDS
              Every field except the account status is required.
              Two columns on wider screens, one below 620px.
          ===================================================== */}

          <div className="garage-users-modal-fields">

            {/* FULL NAME + USERNAME */}

            <div className="garage-users-modal-row">

              <div className={fieldClass("fullName")}>

                <label htmlFor="garage-users-create-full-name">
                  FULL NAME
                  <span
                    className="garage-users-modal-required"
                    aria-hidden="true"
                  >
                    *
                  </span>
                </label>

                <input
                  id="garage-users-create-full-name"
                  type="text"
                  name="fullName"
                  placeholder="Arjun Mehta"
                  value={form.fullName}
                  onChange={handleChange}
                  className={inputClass("fullName")}
                  aria-invalid={isInvalid("fullName") ? "true" : undefined}
                  aria-describedby={
                    isInvalid("fullName")
                      ? "garage-users-create-full-name-error"
                      : undefined
                  }
                  required
                />

                <FieldError
                  id="garage-users-create-full-name-error"
                  message={errors.fullName}
                />

              </div>


              <div className={fieldClass("username")}>

                <label htmlFor="garage-users-create-username">
                  USERNAME
                  <span
                    className="garage-users-modal-required"
                    aria-hidden="true"
                  >
                    *
                  </span>
                </label>

                <input
                  id="garage-users-create-username"
                  type="text"
                  name="username"
                  placeholder="arjun.mehta"
                  value={form.username}
                  onChange={handleChange}
                  className={inputClass("username")}
                  aria-invalid={isInvalid("username") ? "true" : undefined}
                  aria-describedby={
                    isInvalid("username")
                      ? "garage-users-create-username-error"
                      : undefined
                  }
                  required
                />

                <FieldError
                  id="garage-users-create-username-error"
                  message={errors.username}
                />

              </div>

            </div>


            {/* EMAIL + PHONE NUMBER */}

            <div className="garage-users-modal-row">

              <div className={fieldClass("email")}>

                <label htmlFor="garage-users-create-email">
                  EMAIL ADDRESS
                  <span
                    className="garage-users-modal-required"
                    aria-hidden="true"
                  >
                    *
                  </span>
                </label>

                <input
                  id="garage-users-create-email"
                  type="email"
                  name="email"
                  placeholder="arjun.mehta@smartgarage.in"
                  value={form.email}
                  onChange={handleChange}
                  className={inputClass("email")}
                  aria-invalid={isInvalid("email") ? "true" : undefined}
                  aria-describedby={
                    isInvalid("email")
                      ? "garage-users-create-email-error"
                      : undefined
                  }
                  required
                />

                <FieldError
                  id="garage-users-create-email-error"
                  message={errors.email}
                />

              </div>


              <div className={fieldClass("phone")}>

                <label htmlFor="garage-users-create-phone">
                  PHONE NUMBER
                  <span
                    className="garage-users-modal-required"
                    aria-hidden="true"
                  >
                    *
                  </span>
                </label>

                <input
                  id="garage-users-create-phone"
                  type="tel"
                  name="phone"
                  placeholder="+91 98765 43210"
                  value={form.phone}
                  onChange={handleChange}
                  className={inputClass("phone")}
                  aria-invalid={isInvalid("phone") ? "true" : undefined}
                  aria-describedby={
                    isInvalid("phone")
                      ? "garage-users-create-phone-error"
                      : undefined
                  }
                  required
                />

                <FieldError
                  id="garage-users-create-phone-error"
                  message={errors.phone}
                />

              </div>

            </div>


            {/* ROLE + ACCOUNT STATUS */}

            <div className="garage-users-modal-row">

              <div className={fieldClass("role")}>

                <label htmlFor="garage-users-create-role">
                  ROLE
                  <span
                    className="garage-users-modal-required"
                    aria-hidden="true"
                  >
                    *
                  </span>
                </label>

                <select
                  id="garage-users-create-role"
                  name="role"
                  value={form.role}
                  onChange={handleChange}
                  className={inputClass("role")}
                  aria-invalid={isInvalid("role") ? "true" : undefined}
                  aria-describedby={
                    isInvalid("role")
                      ? "garage-users-create-role-error"
                      : undefined
                  }
                  required
                >
                  <option value="">
                    Select a role
                  </option>

                  {roleChoices.map((role) => (
                    <option value={role} key={role}>
                      {role}
                    </option>
                  ))}
                </select>

                <FieldError
                  id="garage-users-create-role-error"
                  message={errors.role}
                />

              </div>


              <div className="garage-users-modal-field">

                <label htmlFor="garage-users-create-status">
                  ACCOUNT STATUS
                  <span
                    className="garage-users-modal-required"
                    aria-hidden="true"
                  >
                    *
                  </span>
                </label>

                <select
                  id="garage-users-create-status"
                  name="status"
                  value={form.status}
                  onChange={handleChange}
                  required
                >
                  {statusChoices.map((status) => (
                    <option value={status} key={status}>
                      {status}
                    </option>
                  ))}
                </select>

              </div>

            </div>


            {/* PASSWORD + CONFIRM PASSWORD */}

            <div className="garage-users-modal-row">

              <div className={fieldClass("password")}>

                <label htmlFor="garage-users-create-password">
                  PASSWORD
                  <span
                    className="garage-users-modal-required"
                    aria-hidden="true"
                  >
                    *
                  </span>
                </label>

                <input
                  id="garage-users-create-password"
                  type="password"
                  name="password"
                  placeholder="Create a password"
                  value={form.password}
                  onChange={handleChange}
                  className={inputClass("password")}
                  aria-invalid={isInvalid("password") ? "true" : undefined}
                  aria-describedby={
                    isInvalid("password")
                      ? "garage-users-create-password-error"
                      : undefined
                  }
                  required
                />

                <FieldError
                  id="garage-users-create-password-error"
                  message={errors.password}
                />

              </div>


              <div className={fieldClass("confirmPassword")}>

                <label htmlFor="garage-users-create-confirm-password">
                  CONFIRM PASSWORD
                  <span
                    className="garage-users-modal-required"
                    aria-hidden="true"
                  >
                    *
                  </span>
                </label>

                <input
                  id="garage-users-create-confirm-password"
                  type="password"
                  name="confirmPassword"
                  placeholder="Re-enter the password"
                  value={form.confirmPassword}
                  onChange={handleChange}
                  className={inputClass("confirmPassword")}
                  aria-invalid={isInvalid("confirmPassword") ? "true" : undefined}
                  aria-describedby={
                    isInvalid("confirmPassword")
                      ? "garage-users-create-confirm-password-error"
                      : undefined
                  }
                  required
                />

                <FieldError
                  id="garage-users-create-confirm-password-error"
                  message={errors.confirmPassword}
                />

              </div>

            </div>

          </div>


          {/* =====================================================
              API ERROR

              Whatever the create request was refused with: the
              backend's field validation text, or the conflict
              raised because the username or email is already in
              use or the role does not exist.
          ===================================================== */}

          {submitError && (
            <div
              className="garage-users-modal-api-error"
              role="alert"
            >
              {submitError}
            </div>
          )}


          <div className="garage-users-modal-footer">

            <button
              type="button"
              className="garage-users-modal-cancel"
              onClick={onClose}
              disabled={isSubmitting}
            >
              Cancel
            </button>

            <button
              type="submit"
              className="garage-users-modal-submit"
              disabled={isSubmitting}
            >
              {isSubmitting ? "Creating..." : "Create User"}
            </button>

          </div>

        </form>

      </div>

    </div>
  );
}

export default GarageOwnerCreateUserModal;
