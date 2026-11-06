import React, { useCallback, useEffect, useMemo, useState } from "react";
import {
  createPart,
  createSupplier,
  getLowStockParts,
  getParts,
  getSuppliers,
} from "../../services/inventoryService";
import "./GarageOwnerInventory.css";

const DEFAULT_SUPPLIER_ORDER = [
  "Castrol India",
  "Bosch India",
  "Shell India",
  "NGK India",
  "MRF Ltd",
  "Amaron India",
  "Mahle Filters",
  "Mann Filters",
];

/**
 * Part categories, mirroring the backend's PartCategory enum.
 *
 * <p>The API sends each category as its readable label ("Engine Parts") and
 * accepts either that label or the enum constant name, so these values can be
 * posted straight back.
 */
const PART_CATEGORIES = [
  "Lubricants",
  "Brakes",
  "Filters",
  "Ignition",
  "Tyres",
  "Electrical",
  "Engine Parts",
  "Suspension",
  "Other",
];

const UNCATEGORISED = "Uncategorised";
const NOT_SET = "—";
const NO_SUPPLIER = "Not Assigned";

const EMPTY_PART_FORM = {
  partName: "",
  sku: "",
  category: "Lubricants",
  unit: "Piece",
  openingStock: "0",
  minimumQty: "0",
  unitPrice: "",
  sellingPrice: "",
  supplier: "",
  storageLocation: "",
  hsnCode: "",
};

const formatPrice = (value) =>
  `₹${Number(value ?? 0).toLocaleString("en-IN")}`;

const toCount = (value) => {
  const count = Number(value);
  return Number.isFinite(count) ? count : 0;
};

/** Map a PartResponseDTO onto the row shape the existing table renders. */
function toInventoryRow(part) {
  const stock = toCount(part.stockQty);
  const minQty = toCount(part.minStock);

  return {
    id: part.id,
    sku: part.sku ?? "",
    name: part.name ?? "",
    category: part.category ?? UNCATEGORISED,
    location: part.storageLocation ?? NOT_SET,
    hsnCode: part.hsnCode ?? NOT_SET,
    stock,
    minQty,
    unitCost:
      part.purchasePrice == null ? null : Number(part.purchasePrice),
    price:
      part.purchasePrice == null
        ? NOT_SET
        : formatPrice(part.purchasePrice),
    supplier: part.supplierName ?? NO_SUPPLIER,
    status:
      stock === 0
        ? "Out of Stock"
        : stock <= minQty
        ? "Low Stock"
        : "OK",
  };
}

function GarageOwnerInventory() {
  /* =====================================================
     INVENTORY DATA
  ===================================================== */

  const [inventory, setInventory] = useState([]);
  const [suppliers, setSuppliers] = useState([]);
  const [lowStockIds, setLowStockIds] = useState(null);
  const [isLoading, setIsLoading] = useState(true);
  const [isCreating, setIsCreating] = useState(false);
  const [loadError, setLoadError] = useState("");
  const [createError, setCreateError] = useState("");
  const [successMessage, setSuccessMessage] = useState("");

  const orderedSuppliers = useMemo(() => {
    const map = new Map(suppliers.map((s) => [s.name, s]));
    const result = [];

    DEFAULT_SUPPLIER_ORDER.forEach((name) => {
      if (map.has(name)) {
        result.push(map.get(name));
        map.delete(name);
      } else {
        result.push({ id: name, name });
      }
    });

    map.forEach((s) => {
      result.push(s);
    });

    return result;
  }, [suppliers]);


  /* =====================================================
     FILTER STATES
  ===================================================== */

  const [search, setSearch] = useState("");
  const [category, setCategory] = useState("All Categories");
  const [stockFilter, setStockFilter] = useState("All Stock");


  /* =====================================================
     ADD PART MODAL
  ===================================================== */

  const [showAddPart, setShowAddPart] = useState(false);


  /* =====================================================
     ADD PART FORM
  ===================================================== */

  const [partForm, setPartForm] = useState(EMPTY_PART_FORM);


  /* =====================================================
     LOAD INVENTORY
  ===================================================== */

  const loadInventory = useCallback(async ({ showLoading = true } = {}) => {
    if (showLoading) {
      setIsLoading(true);
    }
    setLoadError("");

    try {
      // The low-stock feed and the supplier list are supporting detail: if
      // either is unavailable the page still renders from the parts list.
      const [parts, lowStockParts, supplierList] = await Promise.all([
        getParts(),
        getLowStockParts().catch(() => null),
        getSuppliers().catch(() => []),
      ]);

      setInventory(parts.map(toInventoryRow));
      setLowStockIds(
        lowStockParts
          ? new Set(lowStockParts.map((part) => String(part.id)))
          : null
      );
      setSuppliers(supplierList);

      return true;
    } catch (error) {
      setLoadError(
        error instanceof Error
          ? error.message
          : "Unable to load inventory."
      );
      return false;
    } finally {
      if (showLoading) {
        setIsLoading(false);
      }
    }
  }, []);

  useEffect(() => {
    void loadInventory();
  }, [loadInventory]);


  /* =====================================================
     CATEGORIES
  ===================================================== */

// "All Categories" plus every category actually present in the loaded parts.
const categories = useMemo(
    () => [
      "All Categories",
      ...Array.from(new Set(inventory.map((item) => item.category))),
    ],
    [inventory]
  );


  /* =====================================================
     FILTERED INVENTORY
  ===================================================== */

  const filteredItems = useMemo(() => {
    return inventory.filter((item) => {
      const searchValue = search.toLowerCase().trim();

      const searchMatch =
        item.sku.toLowerCase().includes(searchValue) ||
        item.name.toLowerCase().includes(searchValue) ||
        item.category.toLowerCase().includes(searchValue) ||
        item.location.toLowerCase().includes(searchValue) ||
        item.hsnCode.toLowerCase().includes(searchValue) ||
        item.supplier.toLowerCase().includes(searchValue);

      const categoryMatch =
        category === "All Categories" ||
        item.category === category;

      let stockMatch = true;

      if (stockFilter === "Low Stock") {
        stockMatch =
          item.stock <= item.minQty &&
          item.stock > 0;
      }

      if (stockFilter === "Out of Stock") {
        stockMatch = item.stock === 0;
      }

      if (stockFilter === "In Stock") {
        stockMatch = item.stock > item.minQty;
      }

      return (
        searchMatch &&
        categoryMatch &&
        stockMatch
      );
    });
  }, [inventory, search, category, stockFilter]);


  /* =====================================================
     STATISTICS
  ===================================================== */

  const lowStock = lowStockIds
    ? inventory.filter(
        (item) =>
          item.stock > 0 &&
          lowStockIds.has(String(item.id))
      ).length
    : inventory.filter(
        (item) =>
          item.stock <= item.minQty &&
          item.stock > 0
      ).length;

  const outOfStock = inventory.filter(
    (item) => item.stock === 0
  ).length;

  const inventoryValue = inventory.reduce((total, item) => {
    if (item.unitCost == null) {
      return total;
    }
    return total + item.unitCost * item.stock;
  }, 0);

  const formatInventoryValue = (value) =>
    value >= 100000
      ? `₹${(value / 100000).toLocaleString("en-IN", {
          maximumFractionDigits: 1,
        })}L`
      : formatPrice(value);


  /* =====================================================
     OPEN ADD PART
  ===================================================== */

  const openAddPart = () => {
    setCreateError("");
    setShowAddPart(true);
  };


  /* =====================================================
     CLOSE ADD PART
  ===================================================== */

  const closeAddPart = () => {
    if (isCreating) {
      return;
    }
    setCreateError("");
    setShowAddPart(false);
  };


  /* =====================================================
     FORM INPUT CHANGE
  ===================================================== */

  const handlePartChange = async (e) => {
    const { name, value } = e.target;

    setCreateError("");

    if (name === "supplier" && value === "Add New Supplier...") {
      const newSupplierName = window.prompt("Enter new supplier name:");
      if (newSupplierName && newSupplierName.trim()) {
        const trimmed = newSupplierName.trim();
        try {
          const created = await createSupplier({ name: trimmed });
          setSuppliers((previous) => [...previous, created]);
          setPartForm((previous) => ({
            ...previous,
            supplier: String(created.id),
          }));
        } catch {
          const localSupplier = { id: trimmed, name: trimmed };
          setSuppliers((previous) => [...previous, localSupplier]);
          setPartForm((previous) => ({
            ...previous,
            supplier: trimmed,
          }));
        }
      } else {
        setPartForm((previous) => ({
          ...previous,
          supplier: "",
        }));
      }
      return;
    }

    setPartForm((previous) => ({
      ...previous,
      [name]: value,
    }));
  };


  /* =====================================================
     ADD PART
  ===================================================== */

  const handleAddPart = async (e) => {
    e.preventDefault();

    if (isCreating) {
      return;
    }

    const partName = partForm.partName.trim();
    const sku = partForm.sku.trim();

    if (!partName || !sku) {
      setCreateError("Please enter Part Name and SKU.");
      return;
    }

    const sellingPrice = partForm.sellingPrice.trim();

    if (!sellingPrice) {
      setCreateError("Selling price is required by the backend.");
      return;
    }

    setIsCreating(true);
    setCreateError("");
    setSuccessMessage("");

// Every field the form collects is now persisted by PartRequestDTO.
const request = {
      name: partName,
      sku,
      unit: partForm.unit.trim() || null,
      stockQty: toCount(partForm.openingStock),
      minStock: toCount(partForm.minimumQty),
      purchasePrice:
        partForm.unitPrice.trim() === ""
          ? null
          : Number(partForm.unitPrice),
      sellingPrice: Number(sellingPrice),
      category: partForm.category || null,
      // The dropdown carries the supplier id as its value, so an unsaved part
      // is left unassigned rather than pointing at the wrong supplier.
      supplierId:
        !partForm.supplier || isNaN(Number(partForm.supplier))
          ? null
          : Number(partForm.supplier),
      storageLocation: partForm.storageLocation.trim() || null,
      hsnCode: partForm.hsnCode.trim() || null,
    };

    try {
      const savedPart = await createPart(request);

      setShowAddPart(false);
      setPartForm(EMPTY_PART_FORM);
      setSearch("");
      setSuccessMessage(
        `${savedPart?.name || partName} (${savedPart?.sku || sku}) added successfully.`
      );

      await loadInventory({ showLoading: false });
    } catch (error) {
      setCreateError(
        error instanceof Error
          ? error.message
          : "Unable to add the spare part."
      );
    } finally {
      setIsCreating(false);
    }
  };


  return (
    <div className="inventory-page">


      {/* =====================================================
          PAGE HEADER
      ===================================================== */}

      <div className="inventory-page-top">

        <div className="inventory-page-title">

          <div className="inventory-eyebrow">
            — INVENTORY MANAGEMENT
          </div>

          <h1>
            SPARE PARTS INVENTORY
          </h1>

          <p>
            Manage parts, stock levels, suppliers and procurement.
          </p>

        </div>


        {/* =================================================
            ADD PART BUTTON
        ================================================= */}

        <button
          type="button"
          className="inventory-add-button"
          onClick={openAddPart}
        >
          + ADD PART
        </button>

      </div>


      {/* =====================================================
          FEEDBACK
      ===================================================== */}

      {successMessage && (
        <div
          className="inventory-feedback inventory-feedback-success"
          role="status"
        >
          {successMessage}
        </div>
      )}

      {loadError && (
        <div
          className="inventory-feedback inventory-feedback-error"
          role="alert"
        >
          <span>
            {loadError}
          </span>

          <button
            type="button"
            onClick={() => void loadInventory()}
          >
            Retry
          </button>
        </div>
      )}


      {/* =====================================================
          STATISTICS
      ===================================================== */}

      <div className="inventory-stats">

        <div className="inventory-stat-card">

          <span>
            TOTAL SKUS
          </span>

          <strong>
            {inventory.length}
          </strong>

          <small>
            Active inventory items
          </small>

        </div>


        <div className="inventory-stat-card">

          <span>
            LOW STOCK
          </span>

          <strong className="lime">
            {lowStock}
          </strong>

          <small>
            Items need attention
          </small>

        </div>


        <div className="inventory-stat-card">

          <span>
            OUT OF STOCK
          </span>

          <strong>
            {outOfStock}
          </strong>

          <small>
            Items currently unavailable
          </small>

        </div>


        <div className="inventory-stat-card">

          <span>
            INVENTORY VALUE
          </span>

          <strong>
            {formatInventoryValue(inventoryValue)}
          </strong>

          <small>
            Current stock value
          </small>

        </div>

      </div>


      {/* =====================================================
          FILTERS
      ===================================================== */}

      <div className="inventory-filters">

        <div className="inventory-search">

          <span>
            ⌕
          </span>

          <input
            type="text"
            placeholder="Search by SKU, name, category, supplier..."
            value={search}
            onChange={(e) =>
              setSearch(e.target.value)
            }
          />

        </div>


        <select
          value={category}
          onChange={(e) =>
            setCategory(e.target.value)
          }
        >

          {categories.map((item) => (
            <option
              key={item}
              value={item}
            >
              {item}
            </option>
          ))}

        </select>


        <select
          value={stockFilter}
          onChange={(e) =>
            setStockFilter(e.target.value)
          }
        >

          <option value="All Stock">
            All Stock
          </option>

          <option value="In Stock">
            In Stock
          </option>

          <option value="Low Stock">
            Low Stock
          </option>

          <option value="Out of Stock">
            Out of Stock
          </option>

        </select>

      </div>


      {/* =====================================================
          INVENTORY TABLE
      ===================================================== */}

      <div className="inventory-table-card">

        <div className="inventory-table-heading">

          <strong>
            INVENTORY ITEMS
          </strong>

          <span>
            {filteredItems.length} ITEMS
          </span>

        </div>


        <div className="inventory-table-wrapper">

          <table className="inventory-table">

            <thead>

              <tr>
                <th>SKU</th>
                <th>PART NAME</th>
                <th>CATEGORY</th>
                <th>LOCATION</th>
                <th>STOCK</th>
                <th>MIN QTY</th>
                <th>UNIT PRICE</th>
                <th>HSN CODE</th>
                <th>SUPPLIER</th>
                <th>STATUS</th>
                <th>ACTION</th>
              </tr>

            </thead>


            <tbody>

              {isLoading && inventory.length === 0 ? (

                <tr>

                  <td
                    colSpan="11"
                    className="inventory-empty"
                  >
                    Loading inventory…
                  </td>

                </tr>

              ) : filteredItems.length > 0 ? (

                filteredItems.map((item) => (

                  <tr key={item.id ?? item.sku}>

                    <td className="inventory-sku">
                      {item.sku}
                    </td>

                    <td className="inventory-part-name">
                      {item.name}
                    </td>

                    <td>
                      {item.category}
                    </td>

                    <td>
                      {item.location}
                    </td>

                    <td
                      className={
                        item.stock <= item.minQty
                          ? "inventory-stock-low"
                          : "inventory-stock"
                      }
                    >
                      {item.stock}
                    </td>

                    <td>
                      {item.minQty}
                    </td>

                    <td className="inventory-price">
                      {item.price}
                    </td>

                    <td>
                      {item.hsnCode}
                    </td>

                    <td>
                      {item.supplier}
                    </td>

                    <td>

                      <span
                        className={
                          item.status === "Low Stock"
                            ? "inventory-status low"
                            : item.status === "Out of Stock"
                            ? "inventory-status low"
                            : "inventory-status ok"
                        }
                      >
                        {item.status}
                      </span>

                    </td>

                    <td>

                      <button
                        type="button"
                        className="inventory-edit"
                        onClick={() =>
                          alert(`Edit ${item.sku}`)
                        }
                      >
                        Edit
                      </button>

                    </td>

                  </tr>

                ))

              ) : (

                <tr>

                  <td
                    colSpan="11"
                    className="inventory-empty"
                  >
                    {loadError
                      ? "Inventory could not be loaded."
                      : search || stockFilter !== "All Stock"
                      ? "No inventory items match your filters."
                      : "No inventory items found."}
                  </td>

                </tr>

              )}

            </tbody>

          </table>

        </div>

      </div>


      {/* =====================================================
          ADD PART MODAL
      ===================================================== */}

      {showAddPart && (

        <div
          className="inventory-modal-overlay"
          onMouseDown={(e) => {

            if (
              e.target === e.currentTarget
            ) {
              closeAddPart();
            }

          }}
        >

          <div
            className="inventory-modal"
            onMouseDown={(e) =>
              e.stopPropagation()
            }
          >


            {/* =================================================
                MODAL HEADER
            ================================================= */}

            <div className="inventory-modal-header">

              <h2>
                ADD SPARE PART
              </h2>

              <button
                type="button"
                className="inventory-modal-close"
                onClick={closeAddPart}
                aria-label="Close"
              >
                ×
              </button>

            </div>


            {/* =================================================
                FORM
            ================================================= */}

            <form
              className="inventory-add-form"
              onSubmit={handleAddPart}
            >


              {createError && (
                <div
                  className="inventory-feedback inventory-feedback-error"
                  role="alert"
                >
                  <span>
                    {createError}
                  </span>
                </div>
              )}


              {/* PART NAME + SKU */}

              <div className="inventory-form-row">

                <div className="inventory-form-group">

                  <label>
                    PART NAME
                  </label>

                  <input
                    type="text"
                    name="partName"
                    value={partForm.partName}
                    onChange={handlePartChange}
                    placeholder="Enter part name"
                    required
                  />

                </div>


                <div className="inventory-form-group">

                  <label>
                    SKU / PART NUMBER
                  </label>

                  <input
                    type="text"
                    name="sku"
                    value={partForm.sku}
                    onChange={handlePartChange}
                    placeholder="ENO-0001"
                    required
                  />

                </div>

              </div>


              {/* CATEGORY + UNIT */}

              <div className="inventory-form-row">

                <div className="inventory-form-group">

                  <label>
                    CATEGORY
                  </label>

                  <select
                    name="category"
                    value={partForm.category}
                    onChange={handlePartChange}
                  >

                    {PART_CATEGORIES.map((option) => (
                      <option key={option}>
                        {option}
                      </option>
                    ))}

                  </select>

                </div>


                <div className="inventory-form-group">

                  <label>
                    UNIT
                  </label>

                  <select
                    name="unit"
                    value={partForm.unit}
                    onChange={handlePartChange}
                  >

                    <option>
                      Piece
                    </option>

                    <option>
                      Set
                    </option>

                    <option>
                      Litre
                    </option>

                    <option>
                      Kg
                    </option>

                  </select>

                </div>

              </div>


              {/* OPENING STOCK + MINIMUM QTY */}

              <div className="inventory-form-row">

                <div className="inventory-form-group">

                  <label>
                    OPENING STOCK QTY
                  </label>

                  <input
                    type="number"
                    name="openingStock"
                    min="0"
                    value={partForm.openingStock}
                    onChange={handlePartChange}
                  />

                </div>


                <div className="inventory-form-group">

                  <label>
                    MINIMUM QTY
                  </label>

                  <input
                    type="number"
                    name="minimumQty"
                    min="0"
                    value={partForm.minimumQty}
                    onChange={handlePartChange}
                  />

                </div>

              </div>


              {/* UNIT PRICE + SELLING PRICE */}

              <div className="inventory-form-row">

                <div className="inventory-form-group">

                  <label>
                    UNIT PRICE (₹)
                  </label>

                  <input
                    type="number"
                    name="unitPrice"
                    min="0"
                    value={partForm.unitPrice}
                    onChange={handlePartChange}
                  />

                </div>


                <div className="inventory-form-group">

                  <label>
                    SELLING PRICE (₹)
                  </label>

                  <input
                    type="number"
                    name="sellingPrice"
                    min="0"
                    value={partForm.sellingPrice}
                    onChange={handlePartChange}
                  />

                </div>

              </div>


              {/* SUPPLIER */}

              <div className="inventory-form-group full">

                <label>
                  SUPPLIER
                </label>

                <select
                  name="supplier"
                  value={partForm.supplier}
                  onChange={handlePartChange}
                >
                  <option value="">
                    Select supplier...
                  </option>

                  {orderedSuppliers.map((supplier) => (
                    <option
                      key={supplier.id}
                      value={supplier.id}
                    >
                      {supplier.name}
                    </option>
                  ))}

                  <option value="Add New Supplier...">Add New Supplier...</option>
                </select>

              </div>


              {/* STORAGE LOCATION */}

              <div className="inventory-form-group full">

                <label>
                  STORAGE LOCATION
                </label>

                <input
                  type="text"
                  name="storageLocation"
                  value={partForm.storageLocation}
                  onChange={handlePartChange}
                  placeholder="Rack A1, Shelf 2"
                />

              </div>


              {/* HSN CODE */}

              <div className="inventory-form-group full">

                <label>
                  HSN CODE (FOR GST)
                </label>

                <input
                  type="text"
                  name="hsnCode"
                  value={partForm.hsnCode}
                  onChange={handlePartChange}
                  placeholder="27101990"
                />

              </div>


              {/* =================================================
                  MODAL ACTIONS
              ================================================= */}

              <div className="inventory-modal-actions">

                <button
                  type="submit"
                  className="inventory-modal-add-button"
                  disabled={isCreating}
                >
                  {isCreating ? "ADDING…" : "ADD PART"}
                </button>

                <button
                  type="button"
                  className="inventory-modal-cancel-button"
                  onClick={closeAddPart}
                  disabled={isCreating}
                >
                  Cancel
                </button>

              </div>

            </form>

          </div>

        </div>

      )}

    </div>
  );
}

export default GarageOwnerInventory;