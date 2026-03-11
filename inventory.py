"""
Quarter Master - Inventory Manager
Manages item types and item instances.

Lookup priority:
  1. MongoDB (Open Food Facts database + our custom fields)
  2. lookup.txt (local fallback when MongoDB is unavailable)

Item instances are always stored in log.txt.

File formats (lookup.txt fallback):
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
    """Represents a product type (from MongoDB or lookup.txt)."""

    def __init__(self, upc: int, name: str, lifespan: int, amount: Amount, image: str = ""):
        self.upc      = int(upc)
        self.name     = str(name)
        self.lifespan = int(lifespan)
        self.amount   = amount
        self.image    = str(image)

    def to_line(self) -> str:
        """Serialize to lookup.txt format."""
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
# MongoDB helpers
# ---------------------------------------------------------------------------

def _item_type_from_mongo(doc: dict) -> ItemType | None:
    """Convert a MongoDB product document into an ItemType, or None if unusable."""
    if not doc:
        return None
    upc  = doc.get("code")
    name = doc.get("product_name") or doc.get("abbreviated_product_name")
    if not upc or not name:
        return None
    lifespan = doc.get("lifespan_days") or 0
    quantity = doc.get("qm_quantity")   or 1.0
    unit     = doc.get("qm_unit")       or "Count"
    image    = doc.get("image_front_url") or doc.get("image_url") or ""
    return ItemType(int(upc), name, lifespan, Amount(quantity, unit), image)


def _mongo_find(upc: int) -> ItemType | None:
    """Try to find an item type in MongoDB. Returns None on failure or miss."""
    try:
        import db
        doc = db.get_product(str(upc))
        return _item_type_from_mongo(doc)
    except Exception:
        return None


def _mongo_available() -> bool:
    try:
        import db
        return db.is_available()
    except Exception:
        return False


# ---------------------------------------------------------------------------
# lookup.txt helpers
# ---------------------------------------------------------------------------

def _ensure_files():
    for path in (LOOKUP_FILE, LOG_FILE):
        if not os.path.exists(path):
            open(path, "w").close()
    if not os.path.exists(IMAGES_DIR):
        os.makedirs(IMAGES_DIR)


def _read_lookup() -> list[ItemType]:
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
    _ensure_files()
    with open(LOOKUP_FILE, "w") as f:
        for item in items:
            f.write(item.to_line() + "\n")


def _lookup_find(upc: int) -> ItemType | None:
    for item in _read_lookup():
        if item.upc == upc:
            return item
    return None


# ---------------------------------------------------------------------------
# log.txt helpers
# ---------------------------------------------------------------------------

def _read_log() -> list[ItemInstance]:
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
    _ensure_files()
    with open(LOG_FILE, "a") as f:
        f.write(instance.to_line() + "\n")


# ---------------------------------------------------------------------------
# Unified lookup (MongoDB → lookup.txt)
# ---------------------------------------------------------------------------

def _find_item_type(upc: int) -> ItemType | None:
    """
    Return an ItemType for the given UPC.
    Checks MongoDB first; falls back to lookup.txt if MongoDB is unavailable
    or the product is not found there.
    """
    item = _mongo_find(upc)
    if item is not None:
        return item
    return _lookup_find(upc)


# ---------------------------------------------------------------------------
# Public API
# ---------------------------------------------------------------------------

def add_item_type(upc: int, name: str, lifespan: int, amount: Amount, image: str = "") -> None:
    """
    Register a new item type.

    Writes to MongoDB when available; otherwise writes to lookup.txt.

    Args:
        upc      : Unique product code (int).
        name     : Human-readable product name.
        lifespan : How many days the item lasts (int).
        amount   : An Amount describing the package quantity and unit.
        image    : Optional image path or URL.

    Raises:
        ValueError: If an item with the same UPC is already fully configured.
    """
    if _mongo_available():
        try:
            import db
            existing = db.get_product(str(upc))
            if existing and existing.get("lifespan_days") is not None:
                raise ValueError(
                    f"Item with UPC {upc} already exists in MongoDB. "
                    "Use edit_item_type() to modify it."
                )
            db.upsert_product(str(upc), name, lifespan,
                              amount.get_quantity(), amount.get_unit(), image)
            print(f"Added item type to MongoDB: UPC {upc} — {name}")
            return
        except ValueError:
            raise
        except Exception as e:
            print(f"MongoDB write failed ({e}), falling back to lookup.txt")

    # Fallback: lookup.txt
    if _lookup_find(int(upc)) is not None:
        raise ValueError(
            f"Item with UPC {upc} already exists in lookup.txt. "
            "Use edit_item_type() to modify it."
        )
    item  = ItemType(upc, name, lifespan, amount, image)
    items = _read_lookup()
    items.append(item)
    _write_lookup(items)
    print(f"Added item type to lookup.txt: {item}")


def edit_item_type(upc: int,
                   name: str      = None,
                   lifespan: int  = None,
                   amount: Amount = None,
                   image: str     = None) -> None:
    """
    Edit an existing item type. Only provided (non-None) fields are changed.

    Updates MongoDB when available; otherwise updates lookup.txt.

    Raises:
        ValueError: If no item with the given UPC is found anywhere.
    """
    qty  = amount.get_quantity() if amount is not None else None
    unit = amount.get_unit()     if amount is not None else None

    if _mongo_available():
        try:
            import db
            matched = db.update_item_type(str(upc), name=name, lifespan=lifespan,
                                          quantity=qty, unit=unit, image=image)
            if matched:
                print(f"Updated item type in MongoDB: UPC {upc}")
                return
        except Exception as e:
            print(f"MongoDB update failed ({e}), falling back to lookup.txt")

    # Fallback: lookup.txt
    items = _read_lookup()
    for item in items:
        if item.upc == int(upc):
            if name     is not None: item.name     = str(name)
            if lifespan is not None: item.lifespan = int(lifespan)
            if amount   is not None: item.amount   = amount
            if image    is not None: item.image    = str(image)
            _write_lookup(items)
            print(f"Updated item type in lookup.txt: {item}")
            return

    raise ValueError(f"No item with UPC {upc} found in MongoDB or lookup.txt.")


def add_item_instance(upc: int) -> None:
    """
    Log a new instance of an item to log.txt.

    Raises:
        ValueError: If the UPC cannot be found in MongoDB or lookup.txt,
                    or if the item type has not been configured with a lifespan.
    """
    item_type = _find_item_type(int(upc))

    if item_type is None:
        raise ValueError(
            f"UPC {upc} not found. Add it as an item type first."
        )

    instance = ItemInstance(
        upc=item_type.upc,
        name=item_type.name,
        logged=date.today(),
    )
    _append_log(instance)
    print(f"Logged item instance: {instance}")


# ---------------------------------------------------------------------------
# Read functions
# ---------------------------------------------------------------------------

def get_all_item_types() -> list[ItemType]:
    """
    Return all configured item types.

    From MongoDB: products that have lifespan_days set (i.e. fully configured).
    Falls back to lookup.txt when MongoDB is unavailable.
    """
    if _mongo_available():
        try:
            import db
            docs  = db.get_configured_products()
            items = [_item_type_from_mongo(d) for d in docs]
            items = [i for i in items if i is not None]
            # Merge in any lookup.txt entries not already in MongoDB
            mongo_upcs = {i.upc for i in items}
            for local in _read_lookup():
                if local.upc not in mongo_upcs:
                    items.append(local)
            return items
        except Exception:
            pass

    return _read_lookup()


def get_all_instances() -> list[ItemInstance]:
    """Return all item instances from log.txt."""
    return _read_log()


def get_instances_by_upc(upc: int) -> list[ItemInstance]:
    """Return all logged instances for a specific UPC."""
    return [i for i in _read_log() if i.upc == int(upc)]


# ---------------------------------------------------------------------------
# Quick demo
# ---------------------------------------------------------------------------

if __name__ == "__main__":
    print("=== Quarter Master Demo ===\n")
    print(f"MongoDB available: {_mongo_available()}\n")

    add_item_type(123456789, "Whole Milk",      7, Amount(64.0, "Oz"), "images/milk.jpg")
    add_item_type(987654321, "Sourdough Bread", 5, Amount(2.0,  "Count"))

    edit_item_type(123456789, lifespan=10)

    add_item_instance(123456789)
    add_item_instance(987654321)

    print("\n--- Item Types ---")
    for t in get_all_item_types():
        print(t)

    print("\n--- Log ---")
    for i in get_all_instances():
        print(i)
