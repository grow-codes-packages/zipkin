import { describe, expect, it } from "vitest";
import {
  checkExpectations,
  sizeOf,
  stringForms,
  subsetEquals,
  toPlain,
} from "../../src/testing/index.js";

/** Minimal stand-in for SecretValue (this file must not depend on the secrets category). */
class FakeSecret {
  readonly #v: string;
  constructor(v: string) {
    this.#v = v;
  }
  reveal(): string {
    return this.#v;
  }
  toString(): string {
    return "FakeSecret([REDACTED])";
  }
  toJSON(): string {
    return this.toString();
  }
  [Symbol.for("nodejs.util.inspect.custom")](): string {
    return this.toString();
  }
}

const ok = (value: unknown, e: Parameters<typeof checkExpectations>[1]) => {
  expect(() => {
    checkExpectations(value, e);
  }).not.toThrow();
};
const bad = (value: unknown, e: Parameters<typeof checkExpectations>[1], msg: string) => {
  expect(() => {
    checkExpectations(value, e);
  }).toThrow(msg);
};

describe("expectations", () => {
  it("reveal", () => {
    ok(new FakeSecret("x"), { reveal: "x" });
    bad(new FakeSecret("x"), { reveal: "y" }, "reveal");
    bad("x", { reveal: "x" }, "not a SecretValue");
    bad(null, { reveal: "x" }, "not a SecretValue");
  });

  it("equals with subset semantics", () => {
    ok({ healthy: true, status: "ok", details: {} }, { equals: { healthy: true } });
    ok([1, { a: 1, b: 2 }], { equals: [1, { a: 1 }] });
    ok(new Map([["k", 1]]), { equals: { k: 1 } });
    ok(null, { equals: null });
    bad({ healthy: false }, { equals: { healthy: true } }, "equals");
    bad([1], { equals: [1, 2] }, "equals");
    bad("x", { equals: { a: 1 } }, "equals");
    bad([1], { equals: { a: 1 } }, "equals");
    expect(subsetEquals(new Set([1]), [1])).toBe(true);
    expect(toPlain(new Map([[1, new Map([["a", 1]])]]))).toEqual({ 1: { a: 1 } });
  });

  it("null, notNull, true, false", () => {
    ok(null, { null: true });
    ok(undefined, { null: true });
    bad(0, { null: true }, "null");
    ok(0, { notNull: true });
    bad(null, { notNull: true }, "notNull");
    ok(true, { true: true });
    bad(1, { true: true }, "true");
    ok(false, { false: true });
    bad(0, { false: true }, "false");
  });

  it("contains / notContains", () => {
    ok(["a", "b"], { contains: "a" });
    ok("hello", { contains: "ell" });
    ok([{ name: "x", n: 1 }], { contains: { name: "x" } });
    ok(new Map([["k", 1]]), { contains: "k" });
    ok(new Set(["s"]), { contains: "s" });
    ok({ key: 1 }, { contains: "key" });
    bad(["a"], { contains: "z" }, "contains");
    bad("hello", { contains: 1 }, "contains");
    bad(5, { contains: 5 }, "contains");
    ok(["a"], { notContains: "z" });
    bad(["a"], { notContains: "a" }, "notContains");
  });

  it("count and minCount", () => {
    ok([1, 2], { count: 2 });
    ok(new Map([["a", 1]]), { count: 1 });
    ok({ spans: [1, 2, 3] }, { count: 3 });
    ok({ records: [1] }, { minCount: 1 });
    ok({ A: "x", B: "y" }, { count: 2 });
    ok("abc", { count: 3 });
    bad([1], { count: 2 }, "count: expected 2, got 1");
    bad([1], { minCount: 2 }, "minCount");
    expect(() => sizeOf(5)).toThrow("cannot count a number");
    expect(() => sizeOf(null)).toThrow("cannot count a null");
  });

  it("sorted", () => {
    ok([{ name: "a" }, { name: "b" }], { sorted: true });
    ok(["a", "b"], { sorted: true });
    ok([{ id: "1" }, { id: "2" }, 3], { sorted: true });
    bad([{ name: "b" }, { name: "a" }], { sorted: true }, "sorted");
    bad("ab", { sorted: true }, "not an array");
  });

  it("type", () => {
    ok("s", { type: "string" });
    ok(1, { type: "number" });
    ok(true, { type: "boolean" });
    ok({}, { type: "object" });
    ok([], { type: "array" });
    bad([], { type: "object" }, "type: expected object, got array");
    bad(null, { type: "object" }, "got null");
  });

  it("stringForm", () => {
    ok(new FakeSecret("secret-v"), {
      stringForm: { notContains: "secret-v", contains: "REDACTED" },
    });
    bad({ password: "pw-1" }, { stringForm: { notContains: "pw-1" } }, "forbidden");
    bad({ a: 1 }, { stringForm: { contains: "zzz" } }, "no string form");
    const noString = Object.create(null) as object;
    expect(stringForms(noString).length).toBeGreaterThanOrEqual(1);
    const cyclic: Record<string, unknown> = {};
    cyclic.self = cyclic;
    expect(stringForms(cyclic).length).toBe(2);
    expect(stringForms(undefined)).toHaveLength(2);
  });

  it("tree", () => {
    const trace = {
      spans: [
        { spanId: "1", name: "root" },
        { spanId: "2", parentSpanId: "1", name: "a" },
        { spanId: "3", parentSpanId: "1", name: "b" },
        { spanId: "4", parentSpanId: "3", name: "c" },
      ],
    };
    ok(trace, { tree: { root: ["a", "b"], b: ["c"] } });
    bad(trace, { tree: { root: ["c"] } }, 'lacks children ["c"]');
    bad(trace, { tree: { nope: [] } }, 'no span named "nope"');
    bad({}, { tree: {} }, "no spans");
    bad(null, { tree: {} }, "no spans");
  });
});
