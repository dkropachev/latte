#!/usr/bin/env python3
"""Test UDT binding with ScyllaDB - with named tuple."""

import logging
logging.basicConfig(level=logging.WARNING)

from cassandra.cluster import Cluster
from collections import namedtuple

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

# Create a named tuple class for the UDT
Address = namedtuple("address", ["street", "city", "zip"])

# Register the UDT with the cluster using the named tuple
cluster.register_user_type("test_debug", "address", Address)

# Create table with UDT column
session.execute("""
    CREATE TABLE test_udt (
        pk bigint PRIMARY KEY,
        addr frozen<address>
    )
""")

# Prepare an insert statement
prepared = session.prepare("INSERT INTO test_udt (pk, addr) VALUES (?, ?)")

# Try to bind a UDT value as named tuple
addr = Address(street="123 Main St", city="Boston", zip=12345)
print(f"Binding UDT named tuple: {addr}")

try:
    bound = prepared.bind([1, addr])
    session.execute(bound)
    print("SUCCESS: UDT insert with named tuple worked!")

    # Verify the insert
    row = session.execute("SELECT * FROM test_udt WHERE pk = 1").one()
    print(f"Retrieved row: pk={row.pk}, addr={row.addr}")
except Exception as e:
    print(f"ERROR: {type(e).__name__}: {e}")
    import traceback
    traceback.print_exc()

# Try dict with ordered keys
print("\nTrying dict with field order matching schema...")
try:
    from collections import OrderedDict
    udt_dict = OrderedDict([("street", "456 Oak Ave"), ("city", "NYC"), ("zip", 99999)])
    bound = prepared.bind([2, udt_dict])
    session.execute(bound)
    print("SUCCESS: UDT insert with OrderedDict worked!")
except Exception as e:
    print(f"ERROR with OrderedDict: {type(e).__name__}: {e}")

# Try simple tuple (should work since UDT fields are in order)
print("\nTrying simple tuple (street, city, zip)...")
try:
    udt_tuple = ("789 Elm St", "LA", 55555)
    bound = prepared.bind([3, udt_tuple])
    session.execute(bound)
    print("SUCCESS: UDT insert with tuple worked!")

    # Verify
    row = session.execute("SELECT * FROM test_udt WHERE pk = 3").one()
    print(f"Retrieved row: pk={row.pk}, addr={row.addr}")
except Exception as e:
    print(f"ERROR with tuple: {type(e).__name__}: {e}")

cluster.shutdown()
