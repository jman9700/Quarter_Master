"""
Quarter Master - One-time MongoDB Setup Script

Run this script ONCE after importing the Open Food Facts dump.

─────────────────────────────────────────────────────────────────────────────
STEP 1 — Install dependencies
─────────────────────────────────────────────────────────────────────────────

  pip install pymongo

  Install MongoDB Community Edition on your Raspberry Pi:
    https://www.mongodb.com/docs/manual/administration/install-on-linux/

  On Raspberry Pi OS (64-bit), the recommended install method is:

    curl -fsSL https://www.mongodb.org/static/pgp/server-7.0.asc | \\
        sudo gpg -o /usr/share/keyrings/mongodb-server-7.0.gpg --dearmor

    echo "deb [ signed-by=/usr/share/keyrings/mongodb-server-7.0.gpg ] \\
        https://repo.mongodb.org/apt/debian bookworm/mongodb-org/7.0 main" | \\
        sudo tee /etc/apt/sources.list.d/mongodb-org-7.0.list

    sudo apt update && sudo apt install -y mongodb-org
    sudo systemctl enable --now mongod

  NOTE: The full OFF dump is ~30 GB uncompressed. Make sure you have
  sufficient storage. A partial/filtered import is recommended for Pi.

─────────────────────────────────────────────────────────────────────────────
STEP 2 — Download the MongoDB dump
─────────────────────────────────────────────────────────────────────────────

  wget https://static.openfoodfacts.org/data/openfoodfacts-mongodbdump.gz

─────────────────────────────────────────────────────────────────────────────
STEP 3 — Import the dump
─────────────────────────────────────────────────────────────────────────────

  mongorestore --gzip \\
               --archive=openfoodfacts-mongodbdump.gz \\
               --nsFrom="off.*" \\
               --nsTo="off.*"

  This restores into the 'off' database.  If your mongorestore places it
  into a different database, update DB_NAME in db.py accordingly.

  To verify after import:
    mongosh --eval "use off; db.products.countDocuments()"

─────────────────────────────────────────────────────────────────────────────
STEP 4 — Run this script
─────────────────────────────────────────────────────────────────────────────

  python setup_db.py

  This creates the necessary index and confirms the collection is reachable.
"""

import sys
from pymongo import MongoClient, ASCENDING
from pymongo.errors import PyMongoError

MONGO_URI = "mongodb://localhost:27017/"
DB_NAME   = "off"
COLL_NAME = "products"


def main():
    print("Quarter Master — MongoDB Setup\n")

    # ── Connect ──────────────────────────────────────────────────────────────
    try:
        client = MongoClient(MONGO_URI, serverSelectionTimeoutMS=5000)
        client.admin.command("ping")
        print("✓ Connected to MongoDB")
    except PyMongoError as e:
        print(f"✗ Cannot connect to MongoDB at {MONGO_URI}")
        print(f"  {e}")
        print("  Make sure mongod is running: sudo systemctl start mongod")
        sys.exit(1)

    coll = client[DB_NAME][COLL_NAME]

    # ── Check the collection has data ────────────────────────────────────────
    count = coll.estimated_document_count()
    if count == 0:
        print(f"✗ Collection '{DB_NAME}.{COLL_NAME}' is empty.")
        print("  Complete STEP 2 and STEP 3 above before running this script.")
        sys.exit(1)

    print(f"✓ Collection '{DB_NAME}.{COLL_NAME}' has {count:,} documents")

    # ── Index on 'code' (barcode) ────────────────────────────────────────────
    existing = [idx["name"] for idx in coll.list_indexes()]
    if "code_1" not in existing:
        print("  Creating index on 'code' field …", end=" ", flush=True)
        coll.create_index([("code", ASCENDING)], name="code_1", unique=False)
        print("done")
    else:
        print("✓ Index on 'code' already exists")

    # ── Index on our custom lifespan field ───────────────────────────────────
    if "lifespan_days_1" not in existing:
        print("  Creating index on 'lifespan_days' …", end=" ", flush=True)
        coll.create_index([("lifespan_days", ASCENDING)],
                          name="lifespan_days_1",
                          sparse=True)
        print("done")
    else:
        print("✓ Index on 'lifespan_days' already exists")

    # ── Spot-check a known product ───────────────────────────────────────────
    sample = coll.find_one({"code": {"$exists": True}}, {"code": 1, "product_name": 1})
    if sample:
        print(f"\nSample document:")
        print(f"  code         : {sample.get('code')}")
        print(f"  product_name : {sample.get('product_name', '(none)')}")

    print("\n✓ Setup complete. Quarter Master is ready to use MongoDB.")


if __name__ == "__main__":
    main()
