"""
Quarter Master - Inventory Manager
Manages item types in lookup.txt and item instances in log.txt.

File formats:
  lookup.txt  ->  UPC|Name|Lifespan|Quantity|Unit|Image   (Image is optional)
  log.txt     ->  UPC|Name|YYYY-MM-DD
"""

import os
from datetime import date

# ---------------------------------------------------------------------------
# Paths
# ---------------------------------------------------------------------------

BASE_DIR    = os.path.dirname(os.path.abspath(__file__))
LOOKUP_FILE = os.path.join(BASE_DIR, "lookup.txt")
LOG_FILE    = os.path.join(BASE_DIR, "log.txt")
IMAGES_DIR  = os.path.join(BASE_DIR, "images")

DELIM = "|"


# ---------------------------------------------------------------------------
# Data classes
# ---------------------------------------------------------------------------

class Amount:
    """A quantity + unit couplet describing how much of an item there is."""

    def __init__(self, quantity: float, unit: str):
        self._quantity = float(quantity)
        self._unit     = str(unit)

    def get_quantity(self) -> float:
        return self._quantity

    def get_unit(self) -> str:
        return self._unit

    def __repr__(self) -> str:
        return f"Amount({self._quantity} {self._unit})"


class ItemType:
    """Represents a product type stored in lookup.txt."""

    def __init__(self, upc: int, name: str, lifespan: int, amount: Amount, image: str = ""):
        self.upc      = int(upc)
        self.name     = str(name)
        self.lifespan = int(lifespan)
        self.amount   = amount
        self.image    = str(image)

    def to_line(self) -> str:
        parts = [
            str(self.upc),
            self.name,
            str(self.lifespan),
            str(self.amount.get_quantity()),
            self.amount.get_unit(),
        ]
        if self.image:
            parts.append(self.image)
        return DELIM.join(parts)

    def __repr__(self) -> str:
        return (f"ItemType(upc={self.upc}, name={self.name!r}, "
                f"lifespan={self.lifespan}, amount={self.amount!r}, image={self.image!r})")


class ItemInstance:
    """Represents a logged instance of an item in log.txt."""

    def __init__(self, upc: int, name: str, logged: date):
        self.upc    = int(upc)
        self.name   = str(name)
        self.logged = logged  # datetime.date object

    def to_line(self) -> str:
        return DELIM.join([
            str(self.upc),
            self.name,
            self.logged.isoformat(),  # "YYYY-MM-DD"
        ])

    def __repr__(self) -> str:
        return (f"ItemInstance(upc={self.upc}, name={self.name!r}, "
                f"logged={self.logged})")


# ---------------------------------------------------------------------------
# Internal helpers
# ---------------------------------------------------------------------------

def _ensure_files():
    """Create lookup.txt and log.txt if they do not exist."""
    for path in (LOOKUP_FILE, LOG_FILE):
        if not os.path.exists(path):
            open(path, "w").close()
    if not os.path.exists(IMAGES_DIR):
        os.makedirs(IMAGES_DIR)


def _read_lookup() -> list[ItemType]:
    """Return all item types from lookup.txt."""
    _ensure_files()
    items = []
    with open(LOOKUP_FILE, "r") as f:
        for raw in f:
            line = raw.strip()
            if not line:
                continue
            parts = line.split(DELIM)
            if len(parts) < 5:
                continue
            upc      = int(parts[0])
            name     = parts[1]
            lifespan = int(parts[2])
            amount   = Amount(float(parts[3]), parts[4])
            image    = parts[5] if len(parts) > 5 else ""
            items.append(ItemType(upc, name, lifespan, amount, image))
    return items


def _write_lookup(items: list[ItemType]):
    """Overwrite lookup.txt with the given list of item types."""
    _ensure_files()
    with open(LOOKUP_FILE, "w") as f:
        for item in items:
            f.write(item.to_line() + "\n")


def _read_log() -> list[ItemInstance]:
    """Return all item instances from log.txt."""
    _ensure_files()
    instances = []
    with open(LOG_FILE, "r") as f:
        for raw in f:
            line = raw.strip()
            if not line:
                continue
            parts = line.split(DELIM)
            if len(parts) < 3:
                continue
            upc    = int(parts[0])
            name   = parts[1]
            logged = date.fromisoformat(parts[2])
            instances.append(ItemInstance(upc, name, logged))
    return instances


def _append_log(instance: ItemInstance):
    """Append a single item instance to log.txt."""
    _ensure_files()
    with open(LOG_FILE, "a") as f:
        f.write(instance.to_line() + "\n")


def _find_item_type(upc: int) -> ItemType | None:
    """Return the ItemType with the given UPC, or None if not found."""
    for item in _read_lookup():
        if item.upc == upc:
            return item
    return None


# ---------------------------------------------------------------------------
# Public API
# ---------------------------------------------------------------------------

def add_item_type(upc: int, name: str, lifespan: int, amount: Amount, image: str = "") -> None:
    """
    Add a new item type to lookup.txt.

    Args:
        upc      : Unique product code (int).
        name     : Human-readable product name.
        lifespan : How many days the item lasts (int).
        amount   : An Amount object describing the quantity and unit.
        image    : Optional path to an image file (relative to BASE_DIR).

    Raises:
        ValueError: If an item with the same UPC already exists.
    """
    if _find_item_type(int(upc)) is not None:
        raise ValueError(f"Item with UPC {upc} already exists. Use edit_item_type() to modify it.")

    item = ItemType(upc, name, lifespan, amount, image)
    items = _read_lookup()
    items.append(item)
    _write_lookup(items)
    print(f"Added item type: {item}")


def edit_item_type(upc: int,
                   name: str     = None,
                   lifespan: int = None,
                   amount: Amount = None,
                   image: str    = None) -> None:
    """
    Edit an existing item type in lookup.txt.

    Only the fields whose arguments are provided (not None) are updated.

    Args:
        upc      : UPC of the item to edit (int).
        name     : New name, or None to leave unchanged.
        lifespan : New lifespan in days, or None to leave unchanged.
        amount   : New Amount object, or None to leave unchanged.
        image    : New image path, or None to leave unchanged.

    Raises:
        ValueError: If no item with the given UPC exists.
    """
    items = _read_lookup()
    for item in items:
        if item.upc == int(upc):
            if name     is not None: item.name     = str(name)
            if lifespan is not None: item.lifespan = int(lifespan)
            if amount   is not None: item.amount   = amount
            if image    is not None: item.image    = str(image)
            _write_lookup(items)
            print(f"Updated item type: {item}")
            return

    raise ValueError(f"No item with UPC {upc} found in lookup.txt.")


def add_item_instance(upc: int) -> None:
    """
    Log a new instance of an item in log.txt.

    The amount is defined by the item type in lookup.txt.

    Args:
        upc : UPC of the item type (must exist in lookup.txt).

    Raises:
        ValueError: If the UPC does not exist in lookup.txt.
    """
    item_type = _find_item_type(int(upc))

    if item_type is None:
        raise ValueError(f"No item with UPC {upc} found in lookup.txt. Add it first.")

    instance = ItemInstance(
        upc=item_type.upc,
        name=item_type.name,
        logged=date.today(),
    )
    _append_log(instance)
    print(f"Logged item instance: {instance}")


# ---------------------------------------------------------------------------
# Convenience read functions
# ---------------------------------------------------------------------------

def get_all_item_types() -> list[ItemType]:
    """Return all item types currently in lookup.txt."""
    return _read_lookup()


def get_all_instances() -> list[ItemInstance]:
    """Return all item instances currently in log.txt."""
    return _read_log()


def get_instances_by_upc(upc: int) -> list[ItemInstance]:
    """Return all logged instances for a specific UPC."""
    return [i for i in _read_log() if i.upc == int(upc)]


# ---------------------------------------------------------------------------
# Quick demo (runs only when executed directly)
# ---------------------------------------------------------------------------

if __name__ == "__main__":
    print("=== Quarter Master Demo ===\n")

    add_item_type(123456789, "Whole Milk",      7,  Amount(64.0, "Oz"),    "images/milk.jpg")
    add_item_type(987654321, "Sourdough Bread", 5,  Amount(2.0,  "Count"))

    edit_item_type(123456789, lifespan=10)

    add_item_instance(123456789)
    add_item_instance(987654321)

    print("\n--- Lookup ---")
    for t in get_all_item_types():
        print(t)

    print("\n--- Log ---")
    for i in get_all_instances():
        print(i)
