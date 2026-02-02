#!/usr/bin/env python3
"""
Generate a PNG chart comparing latencies across driver adapters.

This script reads JSON benchmark reports from latte and produces bar charts
showing latency comparisons for:
- Pure latte (native driver, no adapter) - single green bar
- Adapter latencies as a stacked bar:
  - Driver-side latency (blue, bottom) - latency measured by the driver
  - IPC overhead (red, top) - difference between latte-side and driver-side

Supports multiple workloads, generating a subplot for each workload.

Usage:
    # Single workload:
    python3 plot_benchmark_latencies.py --output chart.png \
        --pure-latte reports/pure-latte.json \
        --adapter scylla-rust-driver:reports/scylla.json \
        --adapter gocql:reports/gocql.json

    # Multiple workloads:
    python3 plot_benchmark_latencies.py --output chart.png \
        --workload basic:reports/pure-latte-basic.json:scylla-rust-driver=reports/scylla-basic.json,gocql=reports/gocql-basic.json \
        --workload primitives:reports/pure-latte-primitives.json:scylla-rust-driver=reports/scylla-primitives.json
"""

import argparse
import json
import sys
from pathlib import Path

try:
    import matplotlib.pyplot as plt
    from matplotlib.patches import Patch
    import numpy as np
except ImportError:
    print("Error: matplotlib and numpy are required. Install with:")
    print("  pip install matplotlib numpy")
    sys.exit(1)


def load_report(path: Path) -> dict:
    """Load a latte JSON report file."""
    with open(path) as f:
        return json.load(f)


def get_latency_mean(report: dict, latency_type: str) -> float | None:
    """Extract mean latency (in ms) from a report.

    latency_type can be:
    - 'request_latency': end-to-end latency seen by latte
    - 'driver_latency': latency as reported by the driver adapter
    - 'cycle_latency': full cycle latency
    """
    result = report.get("result", {})
    latency = result.get(latency_type)
    if latency is None:
        return None
    mean = latency.get("mean", {})
    return mean.get("value")


def get_latency_percentile(report: dict, latency_type: str, percentile: str) -> float | None:
    """Extract a specific percentile latency (in ms) from a report.

    percentile should be a key like "p50", "p99", "p99.9", etc.
    The report has a top-level 'percentiles' list (e.g., [0, 1, 2, 5, ..., 99, 99.9, ...])
    and each latency type has a 'percentiles' list of objects at corresponding indices.
    """
    # Parse percentile string (e.g., "p99" -> 99.0, "p99.9" -> 99.9)
    p_str = percentile.lstrip("pP")
    try:
        p_value = float(p_str)
    except ValueError:
        return None

    # Get the top-level percentiles list to find the index
    percentile_keys = report.get("percentiles", [])
    if not percentile_keys:
        return None

    # Find the index of the requested percentile
    try:
        p_index = percentile_keys.index(p_value)
    except ValueError:
        # Try to find closest match for integer percentiles stored as float
        for i, pk in enumerate(percentile_keys):
            if abs(pk - p_value) < 0.001:
                p_index = i
                break
        else:
            return None

    # Get the latency data
    result = report.get("result", {})
    latency = result.get(latency_type)
    if latency is None:
        return None

    percentiles_list = latency.get("percentiles", [])
    if p_index >= len(percentiles_list):
        return None

    p_data = percentiles_list[p_index]
    if isinstance(p_data, dict):
        return p_data.get("value")
    return None


def parse_adapter_arg(arg: str) -> tuple[str, Path]:
    """Parse adapter:path argument."""
    if ":" not in arg:
        raise ValueError(f"Invalid adapter argument '{arg}': expected 'name:path'")
    name, path = arg.split(":", 1)
    return name, Path(path)


def parse_workload_arg(arg: str) -> tuple[str, Path | None, list[tuple[str, Path]]]:
    """Parse workload argument: name:pure_latte_path:adapter1=path1,adapter2=path2

    Returns: (workload_name, pure_latte_path, [(adapter_name, path), ...])
    """
    parts = arg.split(":")
    if len(parts) < 2:
        raise ValueError(f"Invalid workload argument '{arg}': expected 'name:pure_latte_path[:adapters]'")

    workload_name = parts[0]
    pure_latte_path = Path(parts[1]) if parts[1] else None

    adapters = []
    if len(parts) >= 3 and parts[2]:
        for adapter_spec in parts[2].split(","):
            if "=" not in adapter_spec:
                raise ValueError(f"Invalid adapter spec '{adapter_spec}': expected 'name=path'")
            adapter_name, adapter_path = adapter_spec.split("=", 1)
            adapters.append((adapter_name, Path(adapter_path)))

    return workload_name, pure_latte_path, adapters


def collect_workload_data(
    workload_name: str,
    pure_latte_path: Path | None,
    adapters: list[tuple[str, Path]],
    percentile: str,
    skipped_pairs: set[str] | None = None
) -> dict:
    """Collect data for a single workload.

    Args:
        workload_name: Name of the workload
        pure_latte_path: Path to pure latte report (no adapter)
        adapters: List of (adapter_name, report_path) tuples
        percentile: Percentile to extract (e.g., "p99")
        skipped_pairs: Set of "adapter:workload" pairs that are known to be skipped

    Returns dict with:
        - workload_name: str
        - pure_latte_value: float | None
        - labels: list[str]
        - adapter_latencies: list[float]
        - latte_side_latencies: list[float]
        - ipc_overhead: list[float]
        - bar_labels: list[str]
        - bar_driver_values: list[float]
        - bar_overhead_values: list[float]
        - bar_is_pure_latte: list[bool]
        - bar_is_failed: list[bool]
        - bar_is_skipped: list[bool]
    """
    if skipped_pairs is None:
        skipped_pairs = set()

    labels = []
    adapter_latencies = []
    latte_side_latencies = []
    failed_adapters = []
    skipped_adapters = []

    # Load pure latte report if provided
    pure_latte_value = None
    if pure_latte_path and pure_latte_path.exists():
        pure_latte_report = load_report(pure_latte_path)
        pure_latte_value = get_latency_percentile(
            pure_latte_report, "request_latency", percentile
        )
        if pure_latte_value is None:
            pure_latte_value = get_latency_mean(pure_latte_report, "request_latency")
    elif pure_latte_path:
        print(f"Warning: Pure latte report not found for {workload_name}: {pure_latte_path}")

    # Load adapter reports
    for adapter_name, report_path in adapters:
        skip_key = f"{adapter_name}:{workload_name}"
        is_skipped = skip_key in skipped_pairs

        if not report_path.exists():
            if is_skipped:
                skipped_adapters.append(adapter_name)
            else:
                print(f"Warning: Report not found for {adapter_name} ({workload_name}): {report_path}")
                failed_adapters.append(adapter_name)
            continue

        report = load_report(report_path)

        # Get driver-side latency (adapter_latency)
        driver_lat = get_latency_percentile(report, "driver_latency", percentile)
        if driver_lat is None:
            driver_lat = get_latency_mean(report, "driver_latency")

        # Get latte-side latency (request_latency when using adapter)
        latte_lat = get_latency_percentile(report, "request_latency", percentile)
        if latte_lat is None:
            latte_lat = get_latency_mean(report, "request_latency")

        if driver_lat is not None or latte_lat is not None:
            labels.append(adapter_name)
            adapter_latencies.append(driver_lat or 0)
            latte_side_latencies.append(latte_lat or 0)
        else:
            print(f"Warning: No valid latency data for {adapter_name} ({workload_name})")
            if is_skipped:
                skipped_adapters.append(adapter_name)
            else:
                failed_adapters.append(adapter_name)

    # Calculate IPC overhead
    ipc_overhead = [max(0, latte - adapter) for latte, adapter in zip(latte_side_latencies, adapter_latencies)]

    # Build bar data
    bar_labels = []
    bar_driver_values = []
    bar_overhead_values = []
    bar_is_pure_latte = []
    bar_is_failed = []
    bar_is_skipped = []

    if pure_latte_value is not None:
        bar_labels.append("Pure Latte")
        bar_driver_values.append(pure_latte_value)
        bar_overhead_values.append(0)
        bar_is_pure_latte.append(True)
        bar_is_failed.append(False)
        bar_is_skipped.append(False)

    for i, label in enumerate(labels):
        bar_labels.append(label)
        bar_driver_values.append(adapter_latencies[i])
        bar_overhead_values.append(ipc_overhead[i])
        bar_is_pure_latte.append(False)
        bar_is_failed.append(False)
        bar_is_skipped.append(False)

    # Add skipped adapters as gray bars (known limitations)
    for adapter_name in skipped_adapters:
        bar_labels.append(adapter_name)
        bar_driver_values.append(0)
        bar_overhead_values.append(0)
        bar_is_pure_latte.append(False)
        bar_is_failed.append(False)
        bar_is_skipped.append(True)

    # Add failed adapters as gray bars (actual failures)
    for adapter_name in failed_adapters:
        bar_labels.append(adapter_name)
        bar_driver_values.append(0)
        bar_overhead_values.append(0)
        bar_is_pure_latte.append(False)
        bar_is_failed.append(True)
        bar_is_skipped.append(False)

    return {
        "workload_name": workload_name,
        "pure_latte_value": pure_latte_value,
        "labels": labels,
        "adapter_latencies": adapter_latencies,
        "latte_side_latencies": latte_side_latencies,
        "ipc_overhead": ipc_overhead,
        "bar_labels": bar_labels,
        "bar_driver_values": bar_driver_values,
        "bar_overhead_values": bar_overhead_values,
        "bar_is_pure_latte": bar_is_pure_latte,
        "bar_is_failed": bar_is_failed,
        "bar_is_skipped": bar_is_skipped,
        "failed_adapters": failed_adapters,
        "skipped_adapters": skipped_adapters,
    }


def plot_workload(ax, data: dict, percentile: str, show_legend: bool = True, log_scale: bool = False):
    """Plot a single workload's data on the given axes."""
    bar_labels = data["bar_labels"]
    bar_driver_values = data["bar_driver_values"]
    bar_overhead_values = data["bar_overhead_values"]
    bar_is_pure_latte = data["bar_is_pure_latte"]
    bar_is_failed = data.get("bar_is_failed", [False] * len(bar_labels))
    bar_is_skipped = data.get("bar_is_skipped", [False] * len(bar_labels))
    workload_name = data["workload_name"]

    if not bar_labels:
        ax.text(0.5, 0.5, "No data available", ha="center", va="center", fontsize=12)
        ax.set_title(workload_name, fontsize=12, fontweight="bold")
        return

    x = np.arange(len(bar_labels))
    width = 0.6

    # Colors
    color_pure_latte = "#2ecc71"
    color_driver = "#3498db"
    color_overhead = "#e74c3c"
    color_failed = "#e57373"   # Light red for failed adapters
    color_skipped = "#bdbdbd"  # Gray for skipped adapters (known limitations)

    # Determine colors for each bar
    driver_colors = []
    for i in range(len(bar_labels)):
        if bar_is_skipped[i]:
            driver_colors.append(color_skipped)
        elif bar_is_failed[i]:
            driver_colors.append(color_failed)
        elif bar_is_pure_latte[i]:
            driver_colors.append(color_pure_latte)
        else:
            driver_colors.append(color_driver)

    # Calculate a reasonable height for failed/skipped bars (use max of successful bars or a default)
    successful_totals = [
        bar_driver_values[i] + bar_overhead_values[i]
        for i in range(len(bar_labels))
        if not bar_is_failed[i] and not bar_is_skipped[i]
    ]
    placeholder_bar_height = max(successful_totals) * 0.1 if successful_totals else 1.0

    # Adjust bar values for failed/skipped adapters to show a visible bar
    display_driver_values = []
    for i in range(len(bar_labels)):
        if bar_is_failed[i] or bar_is_skipped[i]:
            display_driver_values.append(placeholder_bar_height)
        else:
            display_driver_values.append(bar_driver_values[i])

    # Create stacked bars
    ax.bar(x, display_driver_values, width, color=driver_colors, edgecolor="black")
    # Only add overhead bars for non-failed/non-skipped adapters
    overhead_values = [
        0 if (bar_is_failed[i] or bar_is_skipped[i]) else bar_overhead_values[i]
        for i in range(len(bar_labels))
    ]
    overhead_colors = [
        color_skipped if bar_is_skipped[i] else (color_failed if bar_is_failed[i] else color_overhead)
        for i in range(len(bar_labels))
    ]
    ax.bar(x, overhead_values, width, bottom=display_driver_values,
           color=overhead_colors, edgecolor="black")

    # Add value labels on top of bars
    for i in range(len(bar_labels)):
        if bar_is_skipped[i]:
            # Show "SKIPPED" label for skipped adapters (known limitations)
            ax.annotate('SKIPPED',
                       xy=(x[i], display_driver_values[i]),
                       xytext=(0, 3),
                       textcoords="offset points",
                       ha='center', va='bottom', fontsize=8, color='#757575', fontweight='bold')
        elif bar_is_failed[i]:
            # Show "FAILED" label for failed adapters (actual failures)
            ax.annotate('FAILED',
                       xy=(x[i], display_driver_values[i]),
                       xytext=(0, 3),
                       textcoords="offset points",
                       ha='center', va='bottom', fontsize=8, color='#c62828', fontweight='bold')
        else:
            total = bar_driver_values[i] + bar_overhead_values[i]
            if total > 0:
                ax.annotate(f'{total:.2f}',
                           xy=(x[i], total),
                           xytext=(0, 3),
                           textcoords="offset points",
                           ha='center', va='bottom', fontsize=8)

    # Create legend
    if show_legend:
        legend_elements = [
            Patch(facecolor=color_pure_latte, edgecolor='black', label='Pure Latte (native)'),
            Patch(facecolor=color_driver, edgecolor='black', label='Driver-side (driver_latency)'),
            Patch(facecolor=color_overhead, edgecolor='black', label='IPC overhead'),
        ]
        # Add skipped legend entry if there are any skipped adapters
        if any(bar_is_skipped):
            legend_elements.append(Patch(facecolor=color_skipped, edgecolor='black', label='Skipped (not supported)'))
        # Add failed legend entry if there are any failed adapters
        if any(bar_is_failed):
            legend_elements.append(Patch(facecolor=color_failed, edgecolor='black', label='Failed'))
        ax.legend(handles=legend_elements, loc="upper right", fontsize=8)

    # Configure chart
    ax.set_title(workload_name, fontsize=12, fontweight="bold")
    ax.set_ylabel(f"Latency ({percentile}) [ms]", fontsize=10)
    ax.set_xticks(x)
    ax.set_xticklabels(bar_labels, fontsize=9, rotation=45, ha="right")
    ax.yaxis.grid(True, linestyle="--", alpha=0.7)
    ax.set_axisbelow(True)
    if log_scale:
        ax.set_yscale('log')
        # Find minimum positive value for y-axis lower bound
        all_values = [v for v in bar_driver_values + bar_overhead_values if v > 0]
        if all_values:
            min_val = min(all_values)
            ax.set_ylim(bottom=min_val * 0.5)
    else:
        ax.set_ylim(bottom=0)


def print_summary_table(workloads_data: list[dict]):
    """Print summary tables for all workloads."""
    for data in workloads_data:
        workload_name = data["workload_name"]
        pure_latte_value = data["pure_latte_value"]
        labels = data["labels"]
        adapter_latencies = data["adapter_latencies"]
        latte_side_latencies = data["latte_side_latencies"]
        ipc_overhead = data["ipc_overhead"]
        failed_adapters = data.get("failed_adapters", [])
        skipped_adapters = data.get("skipped_adapters", [])

        print(f"\n{'=' * 65}")
        print(f"Latency Summary for '{workload_name}' (ms):")
        print("-" * 65)
        header = f"{'Driver':<25} {'Driver-side':>14} {'IPC overhead':>12} {'Total':>10}"
        print(header)
        print("-" * 65)
        if pure_latte_value is not None:
            print(f"{'Pure Latte':<25} {pure_latte_value:>14.2f} {'N/A':>12} {pure_latte_value:>10.2f}")
        for i, label in enumerate(labels):
            driver = f"{adapter_latencies[i]:.2f}" if adapter_latencies[i] > 0 else "N/A"
            overhead = f"{ipc_overhead[i]:.2f}" if ipc_overhead[i] > 0 else "N/A"
            total = f"{latte_side_latencies[i]:.2f}" if latte_side_latencies[i] > 0 else "N/A"
            print(f"{label:<25} {driver:>14} {overhead:>12} {total:>10}")
        for adapter_name in skipped_adapters:
            print(f"{adapter_name:<25} {'SKIPPED':>14} {'SKIPPED':>12} {'SKIPPED':>10}")
        for adapter_name in failed_adapters:
            print(f"{adapter_name:<25} {'FAILED':>14} {'FAILED':>12} {'FAILED':>10}")
        print("-" * 65)


def main():
    parser = argparse.ArgumentParser(
        description="Generate latency comparison chart from latte benchmark reports"
    )
    parser.add_argument(
        "--output", "-o",
        type=Path,
        default=Path("benchmark_latencies.png"),
        help="Output PNG file path (default: benchmark_latencies.png)"
    )
    parser.add_argument(
        "--pure-latte",
        type=Path,
        help="Path to pure latte (no adapter) JSON report (single workload mode)"
    )
    parser.add_argument(
        "--adapter", "-a",
        action="append",
        dest="adapters",
        default=[],
        help="Adapter report in format 'name:path'. Can be specified multiple times. (single workload mode)"
    )
    parser.add_argument(
        "--workload", "-w",
        action="append",
        dest="workloads",
        default=[],
        help="Workload spec: 'name:pure_latte_path:adapter1=path1,adapter2=path2'. Can be specified multiple times for multi-workload mode."
    )
    parser.add_argument(
        "--percentile", "-p",
        default="p99",
        help="Percentile to plot (default: p99). Options: p50, p75, p90, p95, p99, p99.9"
    )
    parser.add_argument(
        "--title", "-t",
        default="Driver Adapter Latency Comparison",
        help="Chart title"
    )
    parser.add_argument(
        "--single-workload-name",
        help="Workload name for single workload mode (used with --pure-latte and --adapter)"
    )
    parser.add_argument(
        "--log-scale",
        action="store_true",
        help="Use logarithmic (exponential) scale for Y axis"
    )
    parser.add_argument(
        "--skipped", "-s",
        action="append",
        dest="skipped",
        default=[],
        help="Mark adapter:workload pairs as skipped (known limitations). Can be specified multiple times. Format: 'adapter:workload'"
    )

    args = parser.parse_args()

    # Parse skipped pairs into a set for fast lookup
    skipped_pairs = set()
    for skip_spec in args.skipped:
        if ":" in skip_spec:
            skipped_pairs.add(skip_spec)
        else:
            print(f"Warning: Invalid skipped spec '{skip_spec}', expected 'adapter:workload'", file=sys.stderr)

    # Determine mode: multi-workload or single-workload
    workloads_data = []

    if args.workloads:
        # Multi-workload mode
        for workload_arg in args.workloads:
            try:
                workload_name, pure_latte_path, adapters = parse_workload_arg(workload_arg)
                data = collect_workload_data(workload_name, pure_latte_path, adapters, args.percentile, skipped_pairs)
                if data["bar_labels"]:  # Only add if we have data
                    workloads_data.append(data)
            except ValueError as e:
                parser.error(str(e))
    else:
        # Single workload mode (backward compatible)
        if not args.pure_latte and not args.adapters:
            parser.error("At least one of --pure-latte, --adapter, or --workload is required")

        adapters = []
        for arg in args.adapters:
            try:
                name, path = parse_adapter_arg(arg)
                adapters.append((name, path))
            except ValueError as e:
                parser.error(str(e))

        workload_name = args.single_workload_name or "benchmark"
        data = collect_workload_data(workload_name, args.pure_latte, adapters, args.percentile, skipped_pairs)
        if data["bar_labels"]:
            workloads_data.append(data)

    if not workloads_data:
        print("Error: No valid data found for any workload")
        sys.exit(1)

    # Create the chart with subplots
    n_workloads = len(workloads_data)

    if n_workloads == 1:
        # Single chart
        fig, ax = plt.subplots(figsize=(12, 7))
        plot_workload(ax, workloads_data[0], args.percentile, show_legend=True, log_scale=args.log_scale)
        ax.set_xlabel("Driver", fontsize=12)
    else:
        # Multiple subplots in a grid
        n_cols = min(2, n_workloads)
        n_rows = (n_workloads + n_cols - 1) // n_cols

        fig, axes = plt.subplots(n_rows, n_cols, figsize=(7 * n_cols, 6 * n_rows))

        # Flatten axes for easy iteration
        if n_workloads == 1:
            axes_flat = [axes]
        else:
            axes_flat = axes.flatten() if hasattr(axes, 'flatten') else [axes]

        # Plot each workload
        for i, data in enumerate(workloads_data):
            # Only show legend on first subplot
            plot_workload(axes_flat[i], data, args.percentile, show_legend=(i == 0), log_scale=args.log_scale)
            if i >= n_cols * (n_rows - 1):  # Bottom row
                axes_flat[i].set_xlabel("Driver", fontsize=10)

        # Hide unused subplots
        for i in range(n_workloads, len(axes_flat)):
            axes_flat[i].set_visible(False)

    # Add main title
    fig.suptitle(args.title, fontsize=14, fontweight="bold", y=1.02)

    plt.tight_layout()

    # Save the chart
    plt.savefig(args.output, dpi=150, bbox_inches="tight")
    print(f"Chart saved to: {args.output}")

    # Print summary tables
    print_summary_table(workloads_data)


if __name__ == "__main__":
    main()
