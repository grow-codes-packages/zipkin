import { describe, expect, it } from "vitest";
import {
  ConfigError,
  EnvReader,
  parseBool,
  parseDuration,
  parseInteger,
} from "../../src/core/index.js";

describe("parsing", () => {
  it("parses durations", () => {
    expect(parseDuration("1500")).toBe(1500);
    expect(parseDuration("500ms")).toBe(500);
    expect(parseDuration("10s")).toBe(10_000);
    expect(parseDuration("2m")).toBe(120_000);
    expect(parseDuration("1h")).toBe(3_600_000);
    expect(parseDuration("1m30s")).toBe(90_000);
    expect(parseDuration("1.5s")).toBe(1500);
    expect(parseDuration(" 250us ")).toBe(0);
    expect(parseDuration("2000000ns")).toBe(2);
    expect(parseDuration("")).toBeUndefined();
    expect(parseDuration("10x")).toBeUndefined();
    expect(parseDuration("s")).toBeUndefined();
    expect(parseDuration("-5s")).toBeUndefined();
    expect(parseDuration("5s junk")).toBeUndefined();
  });

  it("parses booleans", () => {
    for (const t of ["true", "TRUE", "1", "yes", "On"]) expect(parseBool(t)).toBe(true);
    for (const f of ["false", "0", "NO", "off"]) expect(parseBool(f)).toBe(false);
    expect(parseBool("maybe")).toBeUndefined();
  });

  it("parses integers", () => {
    expect(parseInteger("42")).toBe(42);
    expect(parseInteger("-3")).toBe(-3);
    expect(parseInteger("4.2")).toBeUndefined();
    expect(parseInteger("99999999999999999999")).toBeUndefined();
  });
});

describe("EnvReader", () => {
  const env = {
    GROW_X_URL: "http://grow",
    VENDOR_URL: "http://vendor",
    GROW_X_EMPTY: "",
    VENDOR_ONLY: "v",
    GROW_X_TIMEOUT: "2s",
    GROW_X_BAD_TIMEOUT: "soon",
    GROW_X_FLAG: "yes",
    GROW_X_BAD_FLAG: "perhaps-secret-value",
    GROW_X_N: "3",
    GROW_X_BAD_N: "three",
    GROW_X_TOKEN: "tok",
  };
  const r = new EnvReader("GROW_X_", env);

  it("applies GROW_* > vendor precedence and treats empty as unset", () => {
    expect(r.prefix).toBe("GROW_X_");
    expect(r.string("URL", ["VENDOR_URL"])).toBe("http://grow");
    expect(r.lookup("URL", ["VENDOR_URL"])).toEqual({ name: "GROW_X_URL", value: "http://grow" });
    expect(r.string("EMPTY", ["VENDOR_ONLY"])).toBe("v");
    expect(r.string("MISSING")).toBeUndefined();
    expect(r.secret("TOKEN")?.reveal()).toBe("tok");
    expect(r.secret("MISSING")).toBeUndefined();
  });

  it("parses typed values", () => {
    expect(r.duration("TIMEOUT")).toBe(2000);
    expect(r.bool("FLAG")).toBe(true);
    expect(r.int("N")).toBe(3);
    expect(r.duration("MISSING")).toBeUndefined();
  });

  it("invalid values raise ConfigError naming the variable but not the value", () => {
    for (const fn of [
      () => r.duration("BAD_TIMEOUT"),
      () => r.bool("BAD_FLAG"),
      () => r.int("BAD_N"),
    ]) {
      let err: unknown;
      try {
        fn();
      } catch (e) {
        err = e;
      }
      expect(err).toBeInstanceOf(ConfigError);
      expect((err as Error).message).toMatch(/GROW_X_BAD_/);
      expect((err as Error).message).not.toContain("perhaps-secret-value");
      expect((err as Error).message).not.toContain("soon");
    }
  });

  it("defaults to process.env", () => {
    process.env.GROW_ENVREADER_TEST = "1";
    try {
      expect(new EnvReader("GROW_ENVREADER_").string("TEST")).toBe("1");
    } finally {
      delete process.env.GROW_ENVREADER_TEST;
    }
  });
});
