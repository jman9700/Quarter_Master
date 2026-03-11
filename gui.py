"""
Quarter Master - GUI
Run this file to launch the application.

Requires: Python 3.10+, tkinter (built-in)
Optional: Pillow  ->  pip install Pillow   (needed to display item images)
"""

import os
import tkinter as tk
from tkinter import ttk, messagebox
from datetime import date

try:
    from PIL import Image, ImageTk
    PIL_AVAILABLE = True
except ImportError:
    PIL_AVAILABLE = False

from inventory import (
    BASE_DIR,
    Amount,
    get_all_instances,
    get_all_item_types,
    add_item_type,
    add_item_instance,
)

# ---------------------------------------------------------------------------
# Theme
# ---------------------------------------------------------------------------

BG        = "#1e1e2e"
CARD      = "#2a2a3e"
TEXT      = "#cdd6f4"
SUBTEXT   = "#a6adc8"
ACCENT    = "#89b4fa"
GREEN     = "#a6e3a1"
YELLOW    = "#f9e2af"
RED       = "#f38ba8"
MUTED     = "#585b70"

FONT_H1   = ("Helvetica", 15, "bold")
FONT_BODY = ("Helvetica", 11)
FONT_SMALL= ("Helvetica", 9)

UNIT_OPTIONS = ["Count", "Oz", "Grams"]

# ---------------------------------------------------------------------------
# Reusable widgets
# ---------------------------------------------------------------------------

class ScrollableFrame(tk.Frame):
    """A vertically scrollable container."""

    def __init__(self, parent, **kwargs):
        super().__init__(parent, **kwargs)

        self._canvas = tk.Canvas(self, bg=BG, highlightthickness=0)
        scrollbar    = ttk.Scrollbar(self, orient="vertical", command=self._canvas.yview)
        self.inner   = tk.Frame(self._canvas, bg=BG)

        self.inner.bind(
            "<Configure>",
            lambda e: self._canvas.configure(scrollregion=self._canvas.bbox("all"))
        )

        self._canvas.create_window((0, 0), window=self.inner, anchor="nw")
        self._canvas.configure(yscrollcommand=scrollbar.set)

        self._canvas.pack(side="left", fill="both", expand=True)
        scrollbar.pack(side="right", fill="y")

        self._canvas.bind("<Enter>", self._bind_scroll)
        self._canvas.bind("<Leave>", self._unbind_scroll)

    def _bind_scroll(self, _):
        self._canvas.bind_all("<MouseWheel>", self._on_scroll)

    def _unbind_scroll(self, _):
        self._canvas.unbind_all("<MouseWheel>")

    def _on_scroll(self, event):
        self._canvas.yview_scroll(-1 * (event.delta // 120), "units")


class HealthBar(tk.Canvas):
    """A color-coded progress bar representing item freshness."""

    def __init__(self, parent, pct: float, **kwargs):
        kwargs.setdefault("height", 18)
        kwargs.setdefault("highlightthickness", 0)
        kwargs.setdefault("bg", CARD)
        super().__init__(parent, **kwargs)
        self._pct = max(0.0, min(100.0, pct))
        self.bind("<Configure>", self._draw)
        self.after(50, self._draw)

    def _draw(self, _event=None):
        w = self.winfo_width()
        h = self.winfo_height()
        if w <= 1:
            self.after(50, self._draw)
            return

        self.delete("all")

        # Track
        self.create_rectangle(0, 0, w, h, fill=MUTED, outline="")

        # Fill
        fill_w = int(w * self._pct / 100)
        if fill_w > 0:
            color = GREEN if self._pct > 50 else YELLOW if self._pct > 25 else RED
            self.create_rectangle(0, 0, fill_w, h, fill=color, outline="")

        # Percentage label
        self.create_text(
            w // 2, h // 2,
            text=f"{self._pct:.0f}%",
            fill=TEXT,
            font=FONT_SMALL,
        )


# ---------------------------------------------------------------------------
# Helper
# ---------------------------------------------------------------------------

def _health_pct(instance, item_type) -> float:
    """Return freshness as a percentage (0–100)."""
    if item_type is None or item_type.lifespan <= 0:
        return 0.0
    elapsed   = (date.today() - instance.logged).days
    remaining = item_type.lifespan - elapsed
    return max(0.0, min(100.0, remaining / item_type.lifespan * 100))


def _label(parent, text, fg=TEXT, font=FONT_BODY, **kwargs):
    return tk.Label(parent, text=text, bg=CARD, fg=fg, font=font,
                    anchor="w", **kwargs)


# ---------------------------------------------------------------------------
# Dialogs
# ---------------------------------------------------------------------------

class _BaseDialog(tk.Toplevel):
    """Shared boilerplate for modal dialogs."""

    def __init__(self, parent, title: str):
        super().__init__(parent)
        self.title(title)
        self.configure(bg=BG)
        self.resizable(False, False)
        self.grab_set()
        self._parent = parent

    def _center(self):
        self.update_idletasks()
        px = self._parent.winfo_rootx()
        py = self._parent.winfo_rooty()
        pw = self._parent.winfo_width()
        ph = self._parent.winfo_height()
        x  = px + (pw - self.winfo_width())  // 2
        y  = py + (ph - self.winfo_height()) // 2
        self.geometry(f"+{x}+{y}")

    def _row(self, row: int, label: str, widget: tk.Widget):
        pad = {"padx": 14, "pady": 6}
        tk.Label(self, text=label, bg=BG, fg=TEXT,
                 font=FONT_BODY).grid(row=row, column=0, sticky="w", **pad)
        widget.grid(row=row, column=1, sticky="ew", **pad)

    def _buttons(self, row: int, submit_label="Add"):
        frame = tk.Frame(self, bg=BG)
        frame.grid(row=row, column=0, columnspan=2, pady=12)
        tk.Button(frame, text=submit_label, command=self._submit,
                  bg=ACCENT, fg=BG, font=FONT_BODY, padx=16).pack(side="left", padx=6)
        tk.Button(frame, text="Cancel", command=self.destroy,
                  bg=MUTED, fg=TEXT, font=FONT_BODY, padx=10).pack(side="left", padx=6)


class AddInstanceDialog(_BaseDialog):
    """Popup: log a new item instance."""

    def __init__(self, parent, on_submit):
        super().__init__(parent, "Add Item to Inventory")
        self.on_submit = on_submit

        self.upc_var = tk.StringVar()

        # Validation: allow only digits, max 12
        vcmd = (self.register(self._validate_upc), "%P")
        upc_entry = tk.Entry(self, textvariable=self.upc_var, width=22,
                             validate="key", validatecommand=vcmd)

        self._row(0, "UPC", upc_entry)
        self._buttons(1)
        self._center()

        # Auto-focus and auto-submit on 12 digits
        upc_entry.focus_set()
        self.upc_var.trace_add("write", self._on_upc_change)

    def _validate_upc(self, proposed: str) -> bool:
        return proposed.isdigit() and len(proposed) <= 12 or proposed == ""

    def _on_upc_change(self, *_):
        if len(self.upc_var.get()) == 12:
            self._submit()

    def _submit(self):
        try:
            upc = int(self.upc_var.get().strip())
        except ValueError:
            messagebox.showerror("Invalid UPC", "UPC must be a whole number.", parent=self)
            return

        self.destroy()
        self.on_submit(upc)


class AddItemTypeDialog(_BaseDialog):
    """Popup: register a new item type."""

    def __init__(self, parent, on_submit):
        super().__init__(parent, "Add Item Type")
        self.on_submit = on_submit

        self.upc_var      = tk.StringVar()
        self.name_var     = tk.StringVar()
        self.lifespan_var = tk.StringVar()
        self.qty_var      = tk.StringVar()
        self.unit_var     = tk.StringVar(value=UNIT_OPTIONS[0])
        self.image_var    = tk.StringVar()

        self._row(0, "UPC",                  tk.Entry(self, textvariable=self.upc_var,      width=28))
        self._row(1, "Name",                 tk.Entry(self, textvariable=self.name_var,     width=28))
        self._row(2, "Lifespan (days)",      tk.Entry(self, textvariable=self.lifespan_var, width=28))
        self._row(3, "Quantity",             tk.Entry(self, textvariable=self.qty_var,      width=28))
        self._row(4, "Unit",                 ttk.Combobox(self, textvariable=self.unit_var,
                                                          values=UNIT_OPTIONS, state="readonly", width=26))
        self._row(5, "Image path (optional)",tk.Entry(self, textvariable=self.image_var,    width=28))
        self._buttons(6)
        self._center()

    def _submit(self):
        try:
            upc = int(self.upc_var.get().strip())
        except ValueError:
            messagebox.showerror("Invalid UPC", "UPC must be a whole number.", parent=self)
            return

        name = self.name_var.get().strip()
        if not name:
            messagebox.showerror("Missing Name", "Name is required.", parent=self)
            return

        try:
            lifespan = int(self.lifespan_var.get().strip())
        except ValueError:
            messagebox.showerror("Invalid Lifespan", "Lifespan must be a whole number.", parent=self)
            return

        try:
            qty = float(self.qty_var.get().strip())
        except ValueError:
            messagebox.showerror("Invalid Quantity", "Quantity must be a number.", parent=self)
            return

        amount = Amount(qty, self.unit_var.get())
        image  = self.image_var.get().strip()
        self.destroy()
        self.on_submit(upc, name, lifespan, amount, image)


# ---------------------------------------------------------------------------
# Screens
# ---------------------------------------------------------------------------

class InventoryScreen(tk.Frame):
    """Primary screen: logged item instances with health bars."""

    def __init__(self, parent, on_switch, **kwargs):
        super().__init__(parent, bg=BG, **kwargs)
        self._on_switch = on_switch
        self._build()

    def _build(self):
        # ── Header ──────────────────────────────────────────────────────────
        header = tk.Frame(self, bg=BG)
        header.pack(fill="x", padx=16, pady=(14, 6))

        tk.Label(header, text="Inventory", bg=BG, fg=TEXT,
                 font=FONT_H1).pack(side="left")
        tk.Button(header, text="UPC Lookup →", command=self._on_switch,
                  bg=MUTED, fg=TEXT, font=FONT_BODY, relief="flat",
                  padx=10, pady=4).pack(side="right")
        tk.Button(header, text="+ Add Item", command=self._open_add,
                  bg=ACCENT, fg=BG, font=FONT_BODY, relief="flat",
                  padx=10, pady=4).pack(side="right", padx=8)

        # ── List ─────────────────────────────────────────────────────────────
        self._scroll = ScrollableFrame(self, bg=BG)
        self._scroll.pack(fill="both", expand=True, padx=16, pady=(0, 12))

    def refresh(self):
        for w in self._scroll.inner.winfo_children():
            w.destroy()

        instances = get_all_instances()
        type_map  = {t.upc: t for t in get_all_item_types()}

        if not instances:
            tk.Label(self._scroll.inner, text="No items logged yet.",
                     bg=BG, fg=MUTED, font=FONT_BODY).pack(pady=40)
            return

        for inst in instances:
            itype = type_map.get(inst.upc)
            pct   = _health_pct(inst, itype)
            self._card(inst, itype, pct)

    def _card(self, inst, itype, pct: float):
        card = tk.Frame(self._scroll.inner, bg=CARD, padx=14, pady=10)
        card.pack(fill="x", pady=4)

        if itype:
            amount_text = f"{itype.amount.get_quantity()} {itype.amount.get_unit()}"
        else:
            amount_text = "Unknown amount"

        _label(card, inst.name,   font=FONT_H1).pack(fill="x")
        _label(card, amount_text, fg=ACCENT).pack(fill="x")
        _label(card, f"Added: {inst.logged.strftime('%d %b %Y')}",
               fg=SUBTEXT, font=FONT_SMALL).pack(fill="x")

        HealthBar(card, pct).pack(fill="x", pady=(8, 0))

    def _open_add(self):
        AddInstanceDialog(self, self._on_add)

    def _on_add(self, upc):
        try:
            add_item_instance(upc)
            self.refresh()
        except ValueError as e:
            messagebox.showerror("Error", str(e))


class LookupScreen(tk.Frame):
    """Secondary screen: registered item types."""

    def __init__(self, parent, on_switch, **kwargs):
        super().__init__(parent, bg=BG, **kwargs)
        self._on_switch  = on_switch
        self._img_refs   = []   # Keep PhotoImage objects alive
        self._build()

    def _build(self):
        # ── Header ──────────────────────────────────────────────────────────
        header = tk.Frame(self, bg=BG)
        header.pack(fill="x", padx=16, pady=(14, 6))

        tk.Label(header, text="UPC Lookup", bg=BG, fg=TEXT,
                 font=FONT_H1).pack(side="left")
        tk.Button(header, text="← Inventory", command=self._on_switch,
                  bg=MUTED, fg=TEXT, font=FONT_BODY, relief="flat",
                  padx=10, pady=4).pack(side="right")
        tk.Button(header, text="+ Add Item Type", command=self._open_add,
                  bg=ACCENT, fg=BG, font=FONT_BODY, relief="flat",
                  padx=10, pady=4).pack(side="right", padx=8)

        # ── List ─────────────────────────────────────────────────────────────
        self._scroll = ScrollableFrame(self, bg=BG)
        self._scroll.pack(fill="both", expand=True, padx=16, pady=(0, 12))

    def refresh(self):
        for w in self._scroll.inner.winfo_children():
            w.destroy()
        self._img_refs.clear()

        item_types = get_all_item_types()

        if not item_types:
            tk.Label(self._scroll.inner, text="No item types defined yet.",
                     bg=BG, fg=MUTED, font=FONT_BODY).pack(pady=40)
            return

        for itype in item_types:
            self._card(itype)

    def _card(self, itype):
        card = tk.Frame(self._scroll.inner, bg=CARD, padx=14, pady=10)
        card.pack(fill="x", pady=4)

        # Text block (left side)
        text_col = tk.Frame(card, bg=CARD)
        text_col.pack(side="left", fill="both", expand=True)

        amount_text = f"{itype.amount.get_quantity()} {itype.amount.get_unit()}"

        _label(text_col, itype.name,                          font=FONT_H1).pack(fill="x")
        _label(text_col, f"UPC: {itype.upc}",                fg=ACCENT).pack(fill="x")
        _label(text_col, f"Amount: {amount_text}",            fg=SUBTEXT, font=FONT_SMALL).pack(fill="x")
        _label(text_col, f"Lifespan: {itype.lifespan} days", fg=SUBTEXT, font=FONT_SMALL).pack(fill="x")

        # Image (right side, only if Pillow is installed and path exists)
        if itype.image and PIL_AVAILABLE:
            img_path = os.path.join(BASE_DIR, itype.image)
            if os.path.isfile(img_path):
                try:
                    img   = Image.open(img_path)
                    img.thumbnail((72, 72), Image.LANCZOS)
                    photo = ImageTk.PhotoImage(img)
                    self._img_refs.append(photo)
                    tk.Label(card, image=photo, bg=CARD).pack(side="right", padx=6)
                except Exception:
                    pass

    def _open_add(self):
        AddItemTypeDialog(self, self._on_add)

    def _on_add(self, upc, name, lifespan, amount, image):
        try:
            add_item_type(upc, name, lifespan, amount, image)
            self.refresh()
        except ValueError as e:
            messagebox.showerror("Error", str(e))


# ---------------------------------------------------------------------------
# Application
# ---------------------------------------------------------------------------

class App(tk.Tk):
    """Root window — manages screen switching."""

    def __init__(self):
        super().__init__()
        self.title("Quarter Master")
        self.configure(bg=BG)
        self.geometry("500x680")
        self.minsize(380, 500)

        self._inventory = InventoryScreen(self, self._show_lookup)
        self._lookup    = LookupScreen(self,    self._show_inventory)

        self._show_inventory()

    def _show_inventory(self):
        self._lookup.pack_forget()
        self._inventory.refresh()
        self._inventory.pack(fill="both", expand=True)

    def _show_lookup(self):
        self._inventory.pack_forget()
        self._lookup.refresh()
        self._lookup.pack(fill="both", expand=True)


if __name__ == "__main__":
    App().mainloop()
