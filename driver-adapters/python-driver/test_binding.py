#!/usr/bin/env python3
"""Test binding various types to ScyllaDB."""

import logging
logging.basicConfig(level=logging.WARNING)

from cassandra.cluster import Cluster

# Connect to ScyllaDB
cluster = Cluster(["127.0.0.1"], port=9042)
session = cluster.connect()

# Create test keyspace and tables
session.execute("DROP KEYSPACE IF EXISTS test_binding")
session.execute("""
    CREATE KEYSPACE test_binding WITH replication = {'class': 'SimpleStrategy', 'replication_factor': 1}
""")
session.set_keyspace("test_binding")

# Create UDT
session.execute("""
    CREATE TYPE address (
        street text,
        city text,
        zip int
    )
""")

# Create table with various collection types
session.execute("""
    CREATE TABLE test_collections (
        pk bigint PRIMARY KEY,
        col_list_int list<int>,
        col_map_text_int map<text, int>,
        col_udt frozen<address>,
        col_list_udt frozen<list<frozen<address>>>,
        col_map_udt map<text, frozen<address>>
    )
""")

# Test 1: Simple list of ints
print("Test 1: list<int>")
prepared = session.prepare("INSERT INTO test_collections (pk, col_list_int) VALUES (?, ?)")
try:
    bound = prepared.bind([1, [10, 20, 30]])
    session.execute(bound)
    print("  SUCCESS")
except Exception as e:
    print(f"  ERROR: {e}")

# Test 2: Simple map
print("Test 2: map<text, int>")
prepared = session.prepare("INSERT INTO test_collections (pk, col_map_text_int) VALUES (?, ?)")
try:
    bound = prepared.bind([2, {"key1": 100, "key2": 200}])
    session.execute(bound)
    print("  SUCCESS")
except Exception as e:
    print(f"  ERROR: {e}")

# Test 3: UDT as tuple
print("Test 3: frozen<address> as tuple")
prepared = session.prepare("INSERT INTO test_collections (pk, col_udt) VALUES (?, ?)")
try:
    # Pass UDT as tuple (street, city, zip)
    udt_tuple = ("123 Main St", "Boston", 12345)
    bound = prepared.bind([3, udt_tuple])
    session.execute(bound)
    print(f"  SUCCESS with tuple: {udt_tuple}")
except Exception as e:
    print(f"  ERROR: {e}")

# Test 4: List of UDTs as tuples
print("Test 4: frozen<list<frozen<address>>> as list of tuples")
prepared = session.prepare("INSERT INTO test_collections (pk, col_list_udt) VALUES (?, ?)")
try:
    udt_list = [
        ("First St", "CityA", 11111),
        ("Second St", "CityB", 22222),
    ]
    bound = prepared.bind([4, udt_list])
    session.execute(bound)
    print(f"  SUCCESS with list of tuples: {udt_list}")
except Exception as e:
    print(f"  ERROR: {e}")

# Test 5: Map with UDT values (the problematic one)
print("Test 5: map<text, frozen<address>> with tuple values")
prepared = session.prepare("INSERT INTO test_collections (pk, col_map_udt) VALUES (?, ?)")
try:
    udt_map = {
        "alice": ("Alice St", "AliceCity", 10000),
        "bob": ("Bob St", "BobCity", 20000),
    }
    bound = prepared.bind([5, udt_map])
    session.execute(bound)
    print(f"  SUCCESS with map of tuples: {udt_map}")
except Exception as e:
    print(f"  ERROR: {e}")

cluster.shutdown()
print("\nAll tests completed!")
