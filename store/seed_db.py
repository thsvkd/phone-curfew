#!/usr/bin/env python3
"""Fill a pulled copy of curfew.db with a realistic week (curfew 02:00-07:00, KST).

usage: seed_db.py curfew.db   (the file is modified in place; -wal/-shm must be merged or absent)
"""
import random, sqlite3, sys, time
from datetime import datetime, timedelta
from zoneinfo import ZoneInfo

KST = ZoneInfo("Asia/Seoul")
SLOT = 600
now = datetime.now(KST)
today = now.replace(hour=0, minute=0, second=0, microsecond=0)
rnd = random.Random(7)

def hm(s):
    h, m = s.split(":")
    return int(h) * 60 + int(m)

# (start, end, min seconds, max seconds) per 10-minute slot, minutes of the day
DAYTIME = [("07:20", "08:00", 90, 330), ("08:10", "08:50", 250, 520),
           ("12:10", "12:50", 150, 420), ("15:20", "15:40", 100, 300),
           ("18:10", "18:40", 120, 380), ("20:00", "23:50", 220, 580)]

def sessions_for(back):
    """Sessions for the calendar day `back` days before today (day-of-week as the label)."""
    s = []
    # tail of the evening before, ends between 00:20 and 01:20 after midnight
    tail = {6: "01:10", 5: "01:50", 4: "00:40", 3: "01:20", 2: "00:30", 1: "00:50", 0: "00:40"}[back]
    s.append(("00:00", tail, 150, 520))
    # curfew window 02:00-07:00 of this day
    window = {
        6: [],                                             # success
        5: [("02:10", "03:30", 380, 600)],                 # FAIL: long scroll
        4: [("06:50", "07:00", 120, 170)],                 # success: alarm only
        3: [("02:40", "02:50", 420, 420), ("03:00", "03:10", 200, 200)],  # FAIL: short
        2: [],                                             # success
        1: [("06:40", "06:50", 60, 90)],                   # success
        0: [],                                             # success
    }[back]
    s += window
    s += DAYTIME if back else [d for d in DAYTIME if hm(d[0]) < 14 * 60]
    return s

rows = {}
for back in range(6, -1, -1):
    day = today - timedelta(days=back)
    base = int(day.timestamp())
    for a, b, lo, hi in sessions_for(back):
        for m in range(hm(a), hm(b), 10):
            sec = rnd.randint(lo, hi) if lo != hi else lo
            if lo != hi and rnd.random() < 0.12 and not (hm("02:00") <= m < hm("07:00")):
                sec = 0
            ts = base + m * 60
            if ts * 1000 < now.timestamp() * 1000 - SLOT * 1000:
                if sec > 0:
                    rows[ts * 1000] = min(sec, 600)

con = sqlite3.connect(sys.argv[1])
cur = con.cursor()
cur.execute("DELETE FROM usage_bucket")
cur.execute("DELETE FROM coverage_gap")
cur.execute("DELETE FROM collector_state")
cur.executemany("INSERT INTO usage_bucket VALUES (?,?)", sorted(rows.items()))
# cursor = now, so the collector only adds the few real seconds since seeding
cur.execute("INSERT INTO collector_state VALUES (0,?,1,1)", (int(time.time() * 1000),))
con.commit()
con.execute("PRAGMA wal_checkpoint(TRUNCATE)")
print(len(rows), "buckets")
con.close()
