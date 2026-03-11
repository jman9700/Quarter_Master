"""
Quarter Master - MongoDB Interface
Wraps the Open Food Facts database and our custom fields added on top of it.

Setup:
  1. Install the driver:  pip install pymongo
  2. Run setup_db.py once to import the dump and build indexes.

OFF dump restores to:
  database   : off
  collection : products
  primary key: code  (barcode string, e.g. "0012000161155")

Custom fields we add to existing documents:
  lifespan_days : int   -- how many days this product stays fresh (null if unset)
  qm_quantity   : float -- package quantity (null if unset)
  qm_unit       : str   -- unit of measurement (null if unset)
"""

from pymongo import MongoClient
from pymongo.errors import PyMongoError

# ---------------------------------------------------------------------------
# Connection config  (edit if your MongoDB setup differs)
# ---------------------------------------------------------------------------

MONGO_URI = "mongodb://localhost:27017/"
DB_NAME   = "off"
COLL_NAME = "products"

_client = None
_coll   = None


def _get_collection():
    global _client, _coll
    if _coll is None:
        _client = MongoClient(MONGO_URI, serverSelectionTimeoutMS=3000)
        _coll   = _client[DB_NAME][COLL_NAME]
    return _coll


def is_available() -> bool:
    """Return True if MongoDB is reachable."""
    try:
        _get_collection().database.client.admin.command("ping")
        return True
    except Exception:
        return False


# ---------------------------------------------------------------------------
# Read
# ---------------------------------------------------------------------------

def get_product(upc: str) -> dict | None:
    """
    Return the MongoDB document for a given UPC/barcode, or None if not found.
    The 'code' field is always stored as a string in the OFF collection.
    """
    try:
        return _get_collection().find_one({"code": str(upc)})
    except PyMongoError:
        return None


def get_configured_products() -> list[dict]:
    """
    Return all products that have been configured with our custom fields
    (lifespan_days, qm_quantity, qm_unit).  These are the products the user
    has set up for Quarter Master use.
    """
    try:
        return list(_get_collection().find(
            {"lifespan_days": {"$exists": True, "$ne": None}},
            {"code": 1, "product_name": 1, "lifespan_days": 1,
             "qm_quantity": 1, "qm_unit": 1, "image_front_url": 1}
        ))
    except PyMongoError:
        return []


# ---------------------------------------------------------------------------
# Write
# ---------------------------------------------------------------------------

def set_lifespan(upc: str, days: int) -> None:
    """Set the lifespan_days custom field on a product document."""
    _get_collection().update_one(
        {"code": str(upc)},
        {"$set": {"lifespan_days": int(days)}},
        upsert=True,
    )


def set_amount(upc: str, quantity: float, unit: str) -> None:
    """Set the qm_quantity and qm_unit custom fields on a product document."""
    _get_collection().update_one(
        {"code": str(upc)},
        {"$set": {"qm_quantity": float(quantity), "qm_unit": str(unit)}},
        upsert=True,
    )


def upsert_product(upc: str, name: str, lifespan: int,
                   quantity: float, unit: str, image: str = "") -> None:
    """
    Insert or update a product with all Quarter Master fields.
    Used when adding an item type that may not be in the OFF database.
    """
    _get_collection().update_one(
        {"code": str(upc)},
        {"$set": {
            "code":            str(upc),
            "product_name":    name,
            "lifespan_days":   int(lifespan),
            "qm_quantity":     float(quantity),
            "qm_unit":         str(unit),
            "image_front_url": image,
        }},
        upsert=True,
    )


def update_item_type(upc: str,
                     name: str     = None,
                     lifespan: int = None,
                     quantity: float = None,
                     unit: str     = None,
                     image: str    = None) -> bool:
    """
    Update only the provided fields on an existing product document.
    Returns True if a document was matched, False otherwise.
    """
    updates = {}
    if name     is not None: updates["product_name"]    = str(name)
    if lifespan is not None: updates["lifespan_days"]   = int(lifespan)
    if quantity is not None: updates["qm_quantity"]     = float(quantity)
    if unit     is not None: updates["qm_unit"]         = str(unit)
    if image    is not None: updates["image_front_url"] = str(image)

    if not updates:
        return True

    result = _get_collection().update_one({"code": str(upc)}, {"$set": updates})
    return result.matched_count > 0
