import type { ClientLogger, LogFields } from "../../src/core/index.js";

export interface CapturedLine {
  level: string;
  message: string;
  fields?: LogFields;
}

/** Logger that records every line. */
export function captureLogger(): ClientLogger & { lines: CapturedLine[]; text(): string } {
  const lines: CapturedLine[] = [];
  const rec =
    (level: string) =>
    (message: string, fields?: LogFields): void => {
      lines.push({ level, message, ...(fields === undefined ? {} : { fields }) });
    };
  return {
    lines,
    debug: rec("debug"),
    info: rec("info"),
    warn: rec("warn"),
    error: rec("error"),
    text: () => JSON.stringify(lines),
  };
}
