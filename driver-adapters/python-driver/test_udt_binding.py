#!/usr/bin/env python3
"""Test UDT binding with ScyllaDB."""

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

# Test with list of UDTs
session.execute("""
    CREATE TABLE test_list_udt (
        pk bigint PRIMARY KEY,
        addrs frozen<list<frozen<address>>>
    )
""")

prepared_list = session.prepare("INSERT INTO test_list_udt (pk, addrs) VALUES (?, ?)")
udt_list = [
    {"street": "First St", "city": "CityA", "zip": 11111},
    {"street": "Second St", "city": "CityB", "zip": 22222},
]
print(f"\nBinding list of UDTs: {udt_list}")
try:
    bound = prepared_list.bind([1, udt_list])
    session.execute(bound)
    print("SUCCESS: List of UDTs insert worked!")
except Exception as e:
    print(f"ERROR: {type(e).__name__}: {e}")

# Test with tuple
session.execute("""
    CREATE TABLE test_tuple (
        pk bigint PRIMARY KEY,
        data tuple<int, text, boolean>
    )
""")

prepared_tuple = session.prepare("INSERT INTO test_tuple (pk, data) VALUES (?, ?)")
tuple_val = (123, "hello", True)
print(f"\nBinding tuple: {tuple_val}")
try:
    # Note: cassandra-driver expects individual tuple elements as separate bind values
    # after expanding the tuple column
    bound = prepared_tuple.bind([1] + list(tuple_val))
    session.execute(bound)
    print("SUCCESS: Tuple insert worked with expanded values!")
except Exception as e:
    print(f"ERROR with expanded: {type(e).__name__}: {e}")

# Also try binding tuple directly
try:
    bound = prepared_tuple.bind([2, tuple_val])
    session.execute(bound)
    print("SUCCESS: Tuple insert worked with tuple value!")
except Exception as e:
    print(f"ERROR with tuple value: {type(e).__name__}: {e}")

cluster.shutdown()
