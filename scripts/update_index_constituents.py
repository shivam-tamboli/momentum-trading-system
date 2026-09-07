#!/usr/bin/env python3
"""
Fetches current index constituent lists from authoritative sources and writes them to
backend/src/main/resources/index-constituents/*.txt — one ticker per line, no header.

This script is the only place in the project that talks to any external index-data
source. The Spring Boot app never fetches this data at runtime; it only reads whatever
is committed here. Run this script, review the diff, commit if it looks right.

Sources:
  S&P 500    -> State Street SPDR SPY official daily holdings disclosure (ssga.com)
  S&P 400    -> State Street SPDR MDY official daily holdings disclosure (ssga.com)
  S&P 600    -> State Street SPDR SPSM official daily holdings disclosure (ssga.com)
  Nasdaq 100 -> SlickCharts' Nasdaq-100 constituents table (slickcharts.com)
               (Wikipedia's "Nasdaq-100" page no longer carries a components table as
               of this writing — confirmed by inspecting the live page directly, which
               is almost certainly why community scrapers targeting it had gone stale.)

Exits non-zero, and writes nothing, if ANY source fails to fetch or parse, or produces a
suspiciously small result — a partial/bad run must never silently overwrite good data.
"""

import io
import re
import sys
from pathlib import Path

import openpyxl
import requests
from bs4 import BeautifulSoup

OUTPUT_DIR = Path(__file__).resolve().parent.parent / "backend" / "src" / "main" / "resources" / "index-constituents"

USER_AGENT = "momentum-trading-system-index-updater/1.0"
TICKER_PATTERN = re.compile(r"^[A-Z]{1,5}([.\-][A-Z]{1,2})?$")

# A sanity floor per index, well below the real count, just to catch "the source returned
# something but it's obviously broken" (e.g. an error page mistaken for data).
MIN_EXPECTED = {
    "sp500": 450,
    "sp400": 350,
    "sp600": 550,
    "nasdaq100": 90,
}

SSGA_HOLDINGS = {
    "sp500": "https://www.ssga.com/library-content/products/fund-data/etfs/us/holdings-daily-us-en-spy.xlsx",
    "sp400": "https://www.ssga.com/library-content/products/fund-data/etfs/us/holdings-daily-us-en-mdy.xlsx",
    "sp600": "https://www.ssga.com/library-content/products/fund-data/etfs/us/holdings-daily-us-en-spsm.xlsx",
}

SLICKCHARTS_NASDAQ100_URL = "https://www.slickcharts.com/nasdaq100"


class SourceError(Exception):
    pass


def fetch(url: str) -> bytes:
    resp = requests.get(url, headers={"User-Agent": USER_AGENT}, timeout=30)
    resp.raise_for_status()
    return resp.content


def parse_ssga_holdings(xlsx_bytes: bytes) -> list[str]:
    """SSGA's daily holdings file: 4 metadata rows, a header row (Name, Ticker, Identifier,
    SEDOL, Weight, Sector, Shares Held, Local Currency), then one row per holding. The file
    always includes a few non-equity settlement/cash rows (e.g. a CUSIP-looking placeholder) —
    those get dropped by the ticker-format check below, same as any other malformed entry."""
    wb = openpyxl.load_workbook(io.BytesIO(xlsx_bytes), data_only=True)
    ws = wb.active
    rows = list(ws.iter_rows(values_only=True))
    if len(rows) < 6 or rows[4][1] != "Ticker":
        raise SourceError(f"unexpected SSGA file layout — header row was {rows[4] if len(rows) > 4 else rows}")

    tickers = []
    for row in rows[5:]:
        if not row or not row[1]:
            continue
        symbol = str(row[1]).strip()
        if TICKER_PATTERN.match(symbol):
            tickers.append(symbol)
    return tickers


def parse_slickcharts_nasdaq100(html_bytes: bytes) -> list[str]:
    soup = BeautifulSoup(html_bytes, "lxml")
    tables = soup.find_all("table")
    if not tables:
        raise SourceError("no tables found on SlickCharts Nasdaq-100 page")

    table = tables[0]
    header_cells = [th.get_text(strip=True) for th in table.find_all("th")]
    if "Symbol" not in header_cells:
        raise SourceError(f"SlickCharts table header changed — expected a 'Symbol' column, got {header_cells}")
    symbol_col = header_cells.index("Symbol")

    tickers = []
    for row in table.find_all("tr")[1:]:
        cells = row.find_all("td")
        if len(cells) <= symbol_col:
            continue
        symbol = cells[symbol_col].get_text(strip=True)
        if TICKER_PATTERN.match(symbol):
            tickers.append(symbol)
    return tickers


def main() -> int:
    OUTPUT_DIR.mkdir(parents=True, exist_ok=True)

    # Two passes on purpose: fetch + validate everything first, write nothing until every
    # single index has passed its sanity check. One bad source must never leave the other
    # 3 files updated and this one stale/missing — it's all 4 or none.
    validated: dict[str, list[str]] = {}
    try:
        for name, url in SSGA_HOLDINGS.items():
            tickers = sorted(set(parse_ssga_holdings(fetch(url))))
            if len(tickers) < MIN_EXPECTED[name]:
                raise SourceError(
                    f"{name}: only {len(tickers)} valid tickers parsed, expected at least "
                    f"{MIN_EXPECTED[name]} — this looks like a broken fetch/parse, not a real index"
                )
            validated[name] = tickers

        nasdaq100 = sorted(set(parse_slickcharts_nasdaq100(fetch(SLICKCHARTS_NASDAQ100_URL))))
        if len(nasdaq100) < MIN_EXPECTED["nasdaq100"]:
            raise SourceError(
                f"nasdaq100: only {len(nasdaq100)} valid tickers parsed, expected at least "
                f"{MIN_EXPECTED['nasdaq100']} — this looks like a broken fetch/parse, not a real index"
            )
        validated["nasdaq100"] = nasdaq100
    except (SourceError, requests.RequestException) as e:
        print(f"FAILED — no files written: {e}", file=sys.stderr)
        return 1

    for name, tickers in validated.items():
        path = OUTPUT_DIR / f"{name}.txt"
        path.write_text("\n".join(tickers) + "\n")
        print(f"{name}: wrote {len(tickers)} tickers to {path}")

    return 0


if __name__ == "__main__":
    sys.exit(main())
