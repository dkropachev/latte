#!/usr/bin/env python3
"""Test UDT binding with ScyllaDB - with proper UDT registration."""

import logging
logging.basicConfig(level=logging.WARNING)

from cassandra.cluster import Cluster

# Connect to ScyllaDB
cluster = Cluster(["127.0.0.1"], port=9042)
session = cluster.connect()

# Create the schema for a simple test
session.execute("DROP KEYSPACE IF EXISTS test_debug")
session.execute("""
    CREATE KEYSPACE test_debug WITH replication = {'class': 'SimpleStrategy', 'replication_factor': 1}
""")
session.set_keyspace("test_debug")

# Create UDT
session.execute("""
    CREATE TYPE address (
        street text,
        city text,
        zip int
    )
""")

# Register the UDT with the cluster
cluster.register_user_type("test_debug", "address", dict)

# Create table with UDT column
session.execute("""
    CREATE TABLE test_udt (
        pk bigint PRIMARY KEY,
        addr frozen<address>
    )
""")

# Prepare an insert statement
prepared = session.prepare("INSERT INTO test_udt (pk, addr) VALUES (?, ?)")

# Try to bind a UDT value as dict
udt_dict = {"street": "123 Main St", "city": "Boston", "zip": 12345}
print(f"Binding UDT dict: {udt_dict}")
print(f"zip type: {type(udt_dict['zip'])}")

try:
    bound = prepared.bind([1, udt_dict])
    session.execute(bound)
    print("SUCCESS: UDT insert worked!")

    # Verify the insert
    row = session.execute("SELECT * FROM test_udt WHERE pk = 1").one()
    print(f"Retrieved row: pk={row.pk}, addr={row.addr}")
except Exception as e:
    print(f"ERROR: {type(e).__name__}: {e}")
    import traceback
    traceback.print_exc()

# Also test with a larger int value (like what Latte sends)
udt_dict2 = {"street": "456 Oak Ave", "city": "NYC", "zip": 9999999999}  # Larger than int32
print(f"\nBinding UDT with large zip: {udt_dict2}")
try:
    bound = prepared.bind([2, udt_dict2])
    session.execute(bound)
    print("SUCCESS: Large zip UDT insert worked!")
except Exception as e:
    print(f"ERROR: {type(e).__name__}: {e}")

# Test coerced int value
udt_dict3 = {"street": "789 Elm", "city": "LA", "zip": 55555}  # Normal int
print(f"\nBinding UDT with normal zip (as int32): {udt_dict3}")
try:
    bound = prepared.bind([3, udt_dict3])
    session.execute(bound)
    print("SUCCESS: Normal zip UDT insert worked!")

    # Verify
    row = session.execute("SELECT * FROM test_udt WHERE pk = 3").one()
    print(f"Retrieved row: pk={row.pk}, addr={row.addr}")
except Exception as e:
    print(f"ERROR: {type(e).__name__}: {e}")

cluster.shutdown()
