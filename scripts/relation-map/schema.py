import re
import sys
from pathlib import Path

BASE_DIRECTORY = Path("server/src/main/resources/db/migrations/postgres")


def find_base(root):
    directory = root / BASE_DIRECTORY
    found = sorted(directory.glob("B*__Base.sql"))
    if not found:
        sys.exit("no B*__Base.sql found in %s" % directory)
    if len(found) > 1:
        sys.exit("expected exactly one B*__Base.sql in %s, found: %s" % (directory, ", ".join(p.name for p in found)))
    return found[0]


def split_columns(text):
    return [c.strip().strip('"') for c in text.split(",")]


def parse_schema(path):
    source = path.read_text()
    tables = {}
    for match in re.finditer(r'CREATE TABLE ("?[\w]+"?) \((.*?)\n\);', source, re.S):
        name = match.group(1).strip('"')
        columns = []
        for line in match.group(2).split("\n"):
            line = line.strip().rstrip(",")
            if not line or line.upper().startswith("CONSTRAINT"):
                continue
            column = re.match(r'("[^"]+"|\w+)\s+(.*)', line)
            if not column:
                continue
            rest = column.group(2)
            kind = re.split(r"\s+(?:DEFAULT|NOT NULL|GENERATED)\b", rest)[0].strip()
            kind = kind.replace("character varying", "varchar").replace("timestamp without time zone", "timestamp")
            columns.append({"name": column.group(1).strip('"'), "type": kind, "nullable": "NOT NULL" not in rest})
        tables[name] = {"name": name, "columns": columns, "pk": [], "unique": []}

    foreign_keys = []
    for match in re.finditer(r'ALTER TABLE ONLY ("?\w+"?)\s+ADD CONSTRAINT ("?\w+"?) (.*?);', source, re.S):
        table = match.group(1).strip('"')
        body = match.group(3)
        primary = re.match(r"PRIMARY KEY \((.*?)\)", body)
        unique = re.match(r"UNIQUE \((.*?)\)", body)
        foreign = re.match(r'FOREIGN KEY \((.*?)\) REFERENCES ("?\w+"?)\((.*?)\)(.*)', body, re.S)
        if primary:
            tables[table]["pk"] = split_columns(primary.group(1))
        elif unique:
            tables[table]["unique"].append(split_columns(unique.group(1)))
        elif foreign:
            on_delete = re.search(r"ON DELETE (CASCADE|SET NULL|RESTRICT|NO ACTION|SET DEFAULT)", foreign.group(4))
            foreign_keys.append({
                "from": table,
                "cols": split_columns(foreign.group(1)),
                "to": foreign.group(2).strip('"'),
                "toCols": split_columns(foreign.group(3)),
                "onDelete": on_delete.group(1) if on_delete else "NO ACTION",
            })
        else:
            print("unparsed constraint", table, body[:80], file=sys.stderr)

    for match in re.finditer(r'CREATE UNIQUE INDEX \S+ ON ("?\w+"?) USING \w+ \((.*?)\)', source):
        table = match.group(1).strip('"')
        if table in tables:
            tables[table]["unique"].append(split_columns(match.group(2)))

    return {"tables": list(tables.values()), "fks": foreign_keys}
