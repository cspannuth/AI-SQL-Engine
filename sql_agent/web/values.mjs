/** Preserve SQL int64 values while the browser renders ordinary JSON responses. */
export function parseExact(text) {
  const safe = BigInt(Number.MAX_SAFE_INTEGER);
  const protectedText = text.replace(/"(?:\\.|[^"\\])*"|(-?\d+(?:\.\d+)?(?:[eE][+-]?\d+)?)/g, (token, number) => {
    if (number && /^-?\d+$/.test(number)) {
      const integer = BigInt(number);
      if (integer > safe || integer < -safe) return JSON.stringify({$integer: number});
    }
    return token;
  });
  return JSON.parse(protectedText);
}

export function isInteger(value) {
  return value && typeof value === "object" && typeof value.$integer === "string";
}

export function displayValue(value) {
  if (value === null) return "NULL";
  if (isInteger(value)) return value.$integer;
  return String(value);
}

export function csvText(result) {
  const cell = value => {
    let text = value === null ? "" : displayValue(value);
    // Treat formula-like strings as text when opened in a spreadsheet.
    if (typeof value === "string" && /^[=+\-@\t\r]/.test(text)) text = "'" + text;
    return '"' + text.replaceAll('"', '""') + '"';
  };
  return [result.columns.map(column => cell(column.name)).join(","),
    ...result.rows.map(row => row.map(cell).join(","))].join("\r\n") + "\r\n";
}
