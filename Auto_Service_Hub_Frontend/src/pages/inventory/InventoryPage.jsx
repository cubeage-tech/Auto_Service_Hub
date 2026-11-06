import React, { useState } from "react";
import "./InventoryPage.css";

const SUPPLIER_OPTIONS = [
  "Castrol India",
  "Bosch India",
  "Shell India",
  "NGK India",
  "MRF Ltd",
  "Amaron India",
  "Mahle Filters",
  "Mann Filters",
];

function InventoryPage() {
  const [search, setSearch] = useState("");
  const [category, setCategory] = useState("All Categories");
  const [stock, setStock] = useState("All Stock");
  /* =====================================================
     ADD PART MODAL
  ===================================================== */

  const [showAddPartModal, setShowAddPartModal] = useState(false);
  const [customSuppliers, setCustomSuppliers] = useState([]);

  const [partForm, setPartForm] = useState({
    partName: "",
    sku: "",
    category: "Lubricants",
    unit: "Piece",
    openingStock: "",
    minimumQty: "",
    unitPrice: "",
    sellingPrice: "",
    supplier: "",
    storageLocation: "",
    hsnCode: "",
  });

  /* =====================================================
     INVENTORY DATA
  ===================================================== */

  const [inventory, setInventory] = useState([
    {
      sku: "ENO-7742",
      name: "Engine Oil 5W-30 (1L)",
      category: "Lubricants",
      location: "Rack A1",
      stock: 48,
      min: 20,
      price: "₹320",
      supplier: "Castrol India",
      status: "OK",
    },
    {
      sku: "BRP-2211",
      name: "Brake Pads — Front (Pair)",
      category: "Brakes",
      location: "Rack B3",
      stock: 12,
      min: 8,
      price: "₹1,200",
      supplier: "Bosch India",
      status: "OK",
    },
    {
      sku: "ACF-5503",
      name: "AC Filter",
      category: "Filters",
      location: "Rack C2",
      stock: 3,
      min: 5,
      price: "₹450",
      supplier: "Mahle Filters",
      status: "Low Stock",
    },
    {
      sku: "ATF-9902",
      name: "ATF Oil (1L)",
      category: "Lubricants",
      location: "Rack A2",
      stock: 18,
      min: 10,
      price: "₹580",
      supplier: "Shell India",
      status: "OK",
    },
    {
      sku: "SPK-1144",
      name: "Spark Plugs (Set of 4)",
      category: "Ignition",
      location: "Rack D1",
      stock: 22,
      min: 8,
      price: "₹880",
      supplier: "NGK India",
      status: "OK",
    },
    {
      sku: "TYR-3389",
      name: "Tyre 195/65 R15",
      category: "Tyres",
      location: "Bay Store",
      stock: 4,
      min: 4,
      price: "₹4,800",
      supplier: "MRF Ltd",
      status: "Low Stock",
    },
    {
      sku: "BAT-6670",
      name: "Battery 60Ah",
      category: "Electrical",
      location: "Rack E1",
      stock: 7,
      min: 3,
      price: "₹6,200",
      supplier: "Amaron India",
      status: "OK",
    },
    {
      sku: "OIF-3301",
      name: "Oil Filter",
      category: "Filters",
      location: "Rack C1",
      stock: 2,
      min: 10,
      price: "₹180",
      supplier: "Mann Filters",
      status: "Low Stock",
    },
  ]);

  /* =====================================================
     FILTERED INVENTORY
  ===================================================== */

  const filteredInventory = inventory.filter((item) => {
    const searchMatch =
      item.sku.toLowerCase().includes(search.toLowerCase()) ||
      item.name.toLowerCase().includes(search.toLowerCase()) ||
      item.category.toLowerCase().includes(search.toLowerCase());

    const categoryMatch =
      category === "All Categories" ||
      item.category === category;

    const stockMatch =
      stock === "All Stock" ||
      (stock === "In Stock" && item.status === "OK") ||
      (stock === "Low Stock" && item.status === "Low Stock") ||
      (stock === "Out of Stock" && item.stock === 0);

    return searchMatch && categoryMatch && stockMatch;
  });

  /* =====================================================
     FORM CHANGE
  ===================================================== */

  const handlePartFormChange = (e) => {
    const { name, value } = e.target;

    if (name === "supplier" && value === "Add New Supplier...") {
      const newSupplier = window.prompt("Enter new supplier name:");
      if (newSupplier && newSupplier.trim()) {
        const trimmed = newSupplier.trim();
        setCustomSuppliers((previous) =>
          previous.includes(trimmed) ? previous : [...previous, trimmed]
        );
        setPartForm((previous) => ({
          ...previous,
          supplier: trimmed,
        }));
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
     OPEN MODAL
  ===================================================== */

  const openAddPartModal = () => {
    setPartForm({
      partName: "",
      sku: "",
      category: "Lubricants",
      unit: "Piece",
      openingStock: "",
      minimumQty: "",
      unitPrice: "",
      sellingPrice: "",
      supplier: "",
      storageLocation: "",
      hsnCode: "",
    });

    setShowAddPartModal(true);
  };

  /* =====================================================
     CLOSE MODAL
  ===================================================== */

  const closeAddPartModal = () => {
    setShowAddPartModal(false);
  };

  /* =====================================================
     ADD PART
  ===================================================== */

  const handleAddPart = (e) => {
    e.preventDefault();

    if (!partForm.partName.trim()) {
      alert("Please enter part name.");
      return;
    }

    if (!partForm.sku.trim()) {
      alert("Please enter SKU / Part Number.");
      return;
    }

    if (partForm.openingStock === "") {
      alert("Please enter opening stock quantity.");
      return;
    }

    if (partForm.minimumQty === "") {
      alert("Please enter minimum quantity.");
      return;
    }

    if (partForm.unitPrice === "") {
      alert("Please enter unit price.");
      return;
    }

    if (partForm.sellingPrice === "") {
      alert("Please enter selling price.");
      return;
    }

    if (!partForm.supplier || partForm.supplier === "Add New Supplier...") {
      alert("Please select a supplier.");
      return;
    }

    if (!partForm.storageLocation.trim()) {
      alert("Please enter storage location.");
      return;
    }

    if (!partForm.hsnCode.trim()) {
      alert("Please enter HSN code.");
      return;
    }

    const openingStock = Number(
      partForm.openingStock
    ) || 0;

    const minimumQty = Number(
      partForm.minimumQty
    ) || 0;

    const unitPrice = Number(
      partForm.unitPrice
    ) || 0;

    const duplicateSKU = inventory.some(
      (item) =>
        item.sku.toLowerCase() ===
        partForm.sku.trim().toLowerCase()
    );

    if (duplicateSKU) {
      alert("This SKU already exists.");
      return;
    }

    const newPart = {
      sku: partForm.sku.trim().toUpperCase(),

      name: partForm.partName.trim(),

      category: partForm.category,

      location:
        partForm.storageLocation.trim(),

      stock: openingStock,

      min: minimumQty,

      price:
        `₹${unitPrice.toLocaleString("en-IN")}`,

      supplier: partForm.supplier,

      status:
        openingStock === 0
          ? "Out of Stock"
          : openingStock <= minimumQty
          ? "Low Stock"
          : "OK",
    };

    setInventory((previous) => [
      ...previous,
      newPart,
    ]);

    setShowAddPartModal(false);

    setPartForm({
      partName: "",
      sku: "",
      category: "Lubricants",
      unit: "Piece",
      openingStock: "",
      minimumQty: "",
      unitPrice: "",
      sellingPrice: "",
      supplier: "",
      storageLocation: "",
      hsnCode: "",
    });

    alert("Spare part added successfully.");
  };

  /* =====================================================
     STATISTICS
  ===================================================== */

  const totalSKUs = inventory.length;

  const lowStockCount = inventory.filter(
    (item) =>
      item.stock > 0 &&
      item.stock <= item.min
  ).length;

  const outOfStockCount = inventory.filter(
    (item) => item.stock === 0
  ).length;

  const inventoryValue = inventory.reduce(
    (total, item) => {
      const numericPrice =
        Number(
          String(item.price)
            .replace("₹", "")
            .replace(/,/g, "")
        ) || 0;

      return (
        total +
        numericPrice * item.stock
      );
    },
    0
  );

  const formattedInventoryValue =
    inventoryValue >= 100000
      ? `₹${(
          inventoryValue / 100000
        ).toFixed(1)}L`
      : `₹${inventoryValue.toLocaleString(
          "en-IN"
        )}`;

  return (
    <>
      <section className="inventory-content">


          <div className="inventory-heading">

            <div>

              <div className="inventory-date">
                — INVENTORY MANAGEMENT
              </div>

              <h1>
                SPARE PARTS INVENTORY
              </h1>

              <p>
                Manage parts, stock levels, suppliers and procurement.
              </p>

            </div>


            <button
              type="button"
              className="inventory-add-button"
              onClick={openAddPartModal}
            >
              + ADD PART
            </button>

          </div>


          {/* =================================================
              STAT CARDS
          ================================================= */}

          <div className="inventory-stat-grid">

            <StatCard
              title="TOTAL SKUS"
              value={totalSKUs}
              text="Active inventory items"
            />

            <StatCard
              title="LOW STOCK"
              value={lowStockCount}
              text="Items need attention"
              green
            />

            <StatCard
              title="OUT OF STOCK"
              value={outOfStockCount}
              text={
                outOfStockCount === 0
                  ? "No unavailable parts"
                  : "Unavailable parts"
              }
            />

            <StatCard
              title="INVENTORY VALUE"
              value={formattedInventoryValue}
              text="Current stock value"
            />

          </div>


          {/* =================================================
              FILTER BAR
          ================================================= */}

          <div className="inventory-filter-bar">

            <div className="inventory-search-wrapper">

              <span>
                ⌕
              </span>

              <input
                type="text"
                value={search}
                onChange={(e) =>
                  setSearch(e.target.value)
                }
                placeholder="Search by SKU, name, category..."
              />

            </div>


            <select
              value={category}
              onChange={(e) =>
                setCategory(e.target.value)
              }
            >

              <option>
                All Categories
              </option>

              <option>
                Lubricants
              </option>

              <option>
                Brakes
              </option>

              <option>
                Filters
              </option>

              <option>
                Electrical
              </option>

              <option>
                Ignition
              </option>

              <option>
                Tyres
              </option>

            </select>


            <select
              value={stock}
              onChange={(e) =>
                setStock(e.target.value)
              }
            >

              <option>
                All Stock
              </option>

              <option>
                In Stock
              </option>

              <option>
                Low Stock
              </option>

              <option>
                Out of Stock
              </option>

            </select>

          </div>


          {/* =================================================
              TABLE
          ================================================= */}

          <div className="inventory-table-panel">

            <div className="inventory-table-header">

              <span>
                INVENTORY ITEMS
              </span>

              <span>
                {filteredInventory.length} ITEMS
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

                    <th>SUPPLIER</th>

                    <th>STATUS</th>

                  </tr>

                </thead>


                <tbody>

                  {filteredInventory.map(
                    (item) => (
                      <InventoryRow
                        key={item.sku}
                        {...item}
                      />
                    )
                  )}


                  {filteredInventory.length ===
                    0 && (

                    <tr>

                      <td
                        colSpan="9"
                        className="inventory-empty"
                      >
                        No inventory items found.
                      </td>

                    </tr>

                  )}

                </tbody>

              </table>

            </div>

          </div>

        </section>


      {/* =====================================================
          ADD SPARE PART MODAL
      ===================================================== */}

      {showAddPartModal && (

        <div
          className="inventory-modal-overlay"
          onMouseDown={(e) => {

            if (
              e.target ===
              e.currentTarget
            ) {
              closeAddPartModal();
            }

          }}
        >

          <div className="inventory-add-modal">

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
                onClick={closeAddPartModal}
              >
                ×
              </button>

            </div>


            {/* =================================================
                MODAL FORM
            ================================================= */}

            <form
              className="inventory-add-form"
              onSubmit={handleAddPart}
            >

              {/* PART NAME */}

              <div className="inventory-form-field full">

                <label>
                  PART NAME
                </label>

                <input
                  type="text"
                  name="partName"
                  value={partForm.partName}
                  onChange={handlePartFormChange}
                  placeholder="Engine Oil 5W-30 (1L)"
                />

              </div>


              {/* SKU */}

              <div className="inventory-form-field full">

                <label>
                  SKU / PART NUMBER
                </label>

                <input
                  type="text"
                  name="sku"
                  value={partForm.sku}
                  onChange={handlePartFormChange}
                  placeholder="ENO-0001"
                />

              </div>


              {/* CATEGORY + UNIT */}

              <div className="inventory-form-two">

                <div className="inventory-form-field">

                  <label>
                    CATEGORY
                  </label>

                  <select
                    name="category"
                    value={partForm.category}
                    onChange={handlePartFormChange}
                  >

                    <option>
                      Lubricants
                    </option>

                    <option>
                      Brakes
                    </option>

                    <option>
                      Filters
                    </option>

                    <option>
                      Electrical
                    </option>

                    <option>
                      Ignition
                    </option>

                    <option>
                      Tyres
                    </option>

                    <option>
                      Engine Parts
                    </option>

                    <option>
                      Transmission
                    </option>

                  </select>

                </div>


                <div className="inventory-form-field">

                  <label>
                    UNIT
                  </label>

                  <select
                    name="unit"
                    value={partForm.unit}
                    onChange={handlePartFormChange}
                  >

                    <option>
                      Piece
                    </option>

                    <option>
                      Set
                    </option>

                    <option>
                      Pair
                    </option>

                    <option>
                      Litre
                    </option>

                    <option>
                      Kg
                    </option>

                    <option>
                      Box
                    </option>

                  </select>

                </div>

              </div>


              {/* OPENING STOCK + MINIMUM */}

              <div className="inventory-form-two">

                <div className="inventory-form-field">

                  <label>
                    OPENING STOCK QTY
                  </label>

                  <input
                    type="number"
                    min="0"
                    name="openingStock"
                    value={partForm.openingStock}
                    onChange={handlePartFormChange}
                    placeholder="10"
                  />

                </div>


                <div className="inventory-form-field">

                  <label>
                    MINIMUM QTY ALERT
                  </label>

                  <input
                    type="number"
                    min="0"
                    name="minimumQty"
                    value={partForm.minimumQty}
                    onChange={handlePartFormChange}
                    placeholder="5"
                  />

                </div>

              </div>


              {/* UNIT PRICE + SELLING PRICE */}

              <div className="inventory-form-two">

                <div className="inventory-form-field">

                  <label>
                    UNIT PRICE (₹)
                  </label>

                  <input
                    type="number"
                    min="0"
                    name="unitPrice"
                    value={partForm.unitPrice}
                    onChange={handlePartFormChange}
                    placeholder="320"
                  />

                </div>


                <div className="inventory-form-field">

                  <label>
                    SELLING PRICE (₹)
                  </label>

                  <input
                    type="number"
                    min="0"
                    name="sellingPrice"
                    value={partForm.sellingPrice}
                    onChange={handlePartFormChange}
                    placeholder="380"
                  />

                </div>

              </div>


              {/* SUPPLIER */}

              <div className="inventory-form-field full">

                <label>
                  SUPPLIER
                </label>

                <select
                  name="supplier"
                  value={partForm.supplier}
                  onChange={handlePartFormChange}
                >
                  <option value="">Select supplier...</option>
                  {SUPPLIER_OPTIONS.map((supplier) => (
                    <option key={supplier} value={supplier}>
                      {supplier}
                    </option>
                  ))}
                  {customSuppliers.map((supplier) => (
                    <option key={supplier} value={supplier}>
                      {supplier}
                    </option>
                  ))}
                  <option value="Add New Supplier...">Add New Supplier...</option>
                </select>

              </div>


              {/* STORAGE LOCATION */}

              <div className="inventory-form-field full">

                <label>
                  STORAGE LOCATION
                </label>

                <input
                  type="text"
                  name="storageLocation"
                  value={
                    partForm.storageLocation
                  }
                  onChange={
                    handlePartFormChange
                  }
                  placeholder="Rack A1, Shelf 2"
                />

              </div>


              {/* HSN CODE */}

              <div className="inventory-form-field full">

                <label>
                  HSN CODE (FOR GST)
                </label>

                <input
                  type="text"
                  name="hsnCode"
                  value={partForm.hsnCode}
                  onChange={handlePartFormChange}
                  placeholder="27101990"
                />

              </div>


              {/* DIVIDER + BUTTONS */}

              <div className="inventory-modal-actions">

                <button
                  type="submit"
                  className="inventory-save-button"
                >
                  ADD PART
                </button>

                <button
                  type="button"
                  className="inventory-cancel-button"
                  onClick={closeAddPartModal}
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


/* =========================================================
   STAT CARD
========================================================= */

function StatCard({
  title,
  value,
  text,
  green = false,
}) {
  return (
    <div className="inventory-stat-card">

      <div className="inventory-stat-label">
        {title}
      </div>

      <div
        className={`inventory-stat-value ${
          green ? "green" : ""
        }`}
      >
        {value}
      </div>

      <div className="inventory-stat-text">
        {text}
      </div>

    </div>
  );
}


/* =========================================================
   INVENTORY ROW
========================================================= */

function InventoryRow({
  sku,
  name,
  category,
  location,
  stock,
  min,
  price,
  supplier,
  status,
}) {
  const lowStock =
    status === "Low Stock";

  const outOfStock =
    status === "Out of Stock";

  return (
    <tr>

      <td className="inventory-sku">
        {sku}
      </td>

      <td className="inventory-part-name">
        {name}
      </td>

      <td>
        {category}
      </td>

      <td>
        {location}
      </td>

      <td
        className={
          lowStock || outOfStock
            ? "inventory-stock low"
            : "inventory-stock"
        }
      >
        {stock}
      </td>

      <td>
        {min}
      </td>

      <td className="inventory-price">
        {price}
      </td>

      <td>
        {supplier}
      </td>

      <td>

        <span
          className={`inventory-status ${
            lowStock || outOfStock
              ? "low"
              : "ok"
          }`}
        >
          {status}
        </span>

      </td>

    </tr>
  );
}


export default InventoryPage;