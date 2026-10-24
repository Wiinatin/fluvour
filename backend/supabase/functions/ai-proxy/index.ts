// Thin AI proxy (Supabase Edge Function). API keys live here, never in the app.
// Checks the caller, applies a per-user daily limit, forwards to one provider.
// Providers: gemini (text); image (one picture per word: Hugging Face first, Gemini as the fallback);
// hf-image and gemini-image (each on its own).
//
// Key rotation and model fallback follow the Momori app's rules (ai/GeminiKeyPool.kt, GeminiProvider.kt):
//  - Keys: fresh (never limited) keys first, the key added LAST in the list first; then limited keys, oldest
//    limit first. A quota error stamps the key (table gemini_key_state) and tries the next one. An invalid key
//    is skipped without a stamp. There is no timed cooldown.
//  - Models: tried in order. A busy or missing model moves on to the next model; a model that is busy or out
//    of quota on every key rests for 90 seconds in this instance and is tried last.
// Secrets (set in the dashboard, never in code):
//   GEMINI_API_KEYS      one or more keys, separated by commas or new lines (GEMINI_API_KEY also works for one key)
//   GEMINI_MODELS        optional, strongest first, default "gemini-3.6-flash,gemini-3.5-flash,gemini-3.5-flash-lite"
//   GEMINI_IMAGE_MODELS  optional, default "gemini-2.5-flash-image"
//   HF_API_KEYS          optional Hugging Face tokens (commas or new lines), needs "Make calls to Inference Providers"
//   HF_IMAGE_MODEL       optional, default "black-forest-labs/FLUX.1-schnell"
// Hugging Face follows the Fylosphere desktop app (main.py, generate_image_with_key_pool): FLUX.1-schnell through
// Inference Providers with provider "auto"; a 401, 402, 403 or 429 stamps the key and moves to the next one;
// any other failure would fail on every key, so it stops. Fresh keys go first, OLDEST listed first, then
// stamped keys, oldest stamp first.
import { createClient, type SupabaseClient } from "jsr:@supabase/supabase-js@2";
import {
  InferenceClient,
  InferenceClientHubApiError,
  InferenceClientProviderApiError,
} from "npm:@huggingface/inference@4";

// Calls per user per day. Default 200 (Phase 9 replaces it with the dynamic limit). Set the secret AI_DAILY_LIMIT to change it; 0 = no limit.
const DAILY_LIMIT = Number(Deno.env.get("AI_DAILY_LIMIT") ?? "200");
const REST_MS = 90_000;

function csv(name: string, fallback: string[]): string[] {
  const items = (Deno.env.get(name) ?? "").split(/[\n,]/).map((s) => s.trim()).filter(Boolean);
  return items.length > 0 ? items : fallback;
}
const TEXT_MODELS = csv("GEMINI_MODELS", ["gemini-3.6-flash", "gemini-3.5-flash", "gemini-3.5-flash-lite"]);
const IMAGE_MODELS = csv("GEMINI_IMAGE_MODELS", ["gemini-2.5-flash-image"]);
const HF_IMAGE_MODEL = Deno.env.get("HF_IMAGE_MODEL")?.trim() || "black-forest-labs/FLUX.1-schnell";
const HF_ATTEMPT_MS = 40_000; // per key
const HF_BUDGET_MS = 75_000;  // for all keys together, so the fallback still has time

type Key = { id: string; key: string; index: number };

async function keyId(key: string): Promise<string> {
  const digest = await crypto.subtle.digest("SHA-256", new TextEncoder().encode(key));
  return [...new Uint8Array(digest).slice(0, 4)].map((b) => b.toString(16).padStart(2, "0")).join("");
}

async function loadKeys(): Promise<Key[]> {
  const raw = csv("GEMINI_API_KEYS", []);
  if (raw.length === 0) {
    const single = Deno.env.get("GEMINI_API_KEY")?.trim();
    if (single) raw.push(single);
  }
  return await Promise.all(raw.map(async (key, index) => ({ id: await keyId(key), key, index })));
}

function orderKeys(keys: Key[], limited: Map<string, number>): Key[] {
  const fresh = keys.filter((k) => !limited.has(k.id)).sort((a, b) => b.index - a.index);
  const stamped = keys.filter((k) => limited.has(k.id)).sort((a, b) => limited.get(a.id)! - limited.get(b.id)!);
  return [...fresh, ...stamped];
}

type KeyError = "quota" | "auth" | null;

function classify(status: number, body: string): KeyError {
  const text = body.toUpperCase();
  if (status === 429 || text.includes("RESOURCE_EXHAUSTED")) return "quota";
  if (status === 401 || status === 403) return "auth";
  if (["PERMISSION_DENIED", "UNAUTHENTICATED", "API_KEY_INVALID", "API KEY NOT VALID"].some((t) => text.includes(t))) {
    return "auth";
  }
  return null;
}

/** The model (not the key) cannot answer right now: busy, down or gone. The next model may still work. */
function modelUnavailable(status: number, body: string): boolean {
  const text = body.toUpperCase();
  return status >= 500 || status === 404 || ["UNAVAILABLE", "OVERLOADED", "HIGH DEMAND", "NOT_FOUND"].some((t) => text.includes(t));
}

const resting = new Map<string, number>();

function fail(status: number, message: string): Response {
  return new Response(message, { status });
}

// deno-lint-ignore no-explicit-any
type Generated = { ok: true; json: any; model: string } | { ok: false; res: Response };

async function generate(admin: SupabaseClient, models: string[], payload: unknown): Promise<Generated> {
  const keys = await loadKeys();
  if (keys.length === 0) return { ok: false, res: fail(500, "No Gemini API key is set") };

  const { data } = await admin.from("gemini_key_state").select("key_id, limited_at");
  const limited = new Map<string, number>((data ?? []).map((r: { key_id: string; limited_at: number | string }) => [r.key_id, Number(r.limited_at)]));
  const ordered = orderKeys(keys, limited);

  const now = Date.now();
  const rested = models.filter((m) => (resting.get(m) ?? 0) <= now);
  const order = [...rested, ...models.filter((m) => !rested.includes(m))];

  const body = JSON.stringify(payload);
  const bad = new Set<string>(); // refused as invalid: not tried again on the next model
  let sawQuota = false;
  let sawBusy = false;

  for (const model of order) {
    const url = `https://generativelanguage.googleapis.com/v1beta/models/${model}:generateContent`;
    let tried = 0;
    let quotaHits = 0;
    for (const k of ordered) {
      if (bad.has(k.id)) continue;
      tried++;
      let status: number;
      let text: string;
      try {
        const res = await fetch(url, {
          method: "POST",
          headers: { "Content-Type": "application/json", "x-goog-api-key": k.key },
          body,
        });
        status = res.status;
        text = await res.text();
      } catch (e) {
        return { ok: false, res: fail(502, `Could not reach Gemini: ${(e as Error).message}`) };
      }
      if (status >= 200 && status < 300) {
        try {
          return { ok: true, json: JSON.parse(text), model };
        } catch {
          return { ok: false, res: fail(502, "Gemini returned an unexpected response") };
        }
      }
      const kind = classify(status, text);
      if (kind === "quota") {
        await admin.from("gemini_key_state").upsert({ key_id: k.id, limited_at: Date.now() });
        sawQuota = true;
        quotaHits++;
      } else if (kind === "auth") {
        bad.add(k.id);
      } else {
        if (!modelUnavailable(status, text)) {
          return { ok: false, res: fail(502, `Gemini request failed: ${text.slice(0, 200)}`) };
        }
        // Every key would see the same, so go straight to the next model.
        sawBusy = true;
        resting.set(model, Date.now() + REST_MS);
        break;
      }
    }
    if (tried > 0 && quotaHits === tried) resting.set(model, Date.now() + REST_MS);
  }
  if (sawBusy) return { ok: false, res: fail(503, "Gemini is busy right now. Try again later.") };
  if (sawQuota) return { ok: false, res: fail(429, "Every Gemini key has hit its limit. Try again later.") };
  return { ok: false, res: fail(502, "Gemini rejected the API keys. Check the secrets.") };
}

const json = (value: unknown) => new Response(JSON.stringify(value), { headers: { "Content-Type": "application/json" } });

type Provider = (admin: SupabaseClient, body: unknown) => Promise<Response>;

type Picture = { ok: true; mimeType: string; data: string } | { ok: false; res: Response };

function toBase64(bytes: Uint8Array): string {
  let s = "";
  for (let i = 0; i < bytes.length; i += 0x8000) s += String.fromCharCode(...bytes.subarray(i, i + 0x8000));
  return btoa(s);
}

async function geminiPicture(admin: SupabaseClient, prompt: string): Promise<Picture> {
  const r = await generate(admin, IMAGE_MODELS, {
    contents: [{ parts: [{ text: prompt }] }],
    generationConfig: { responseModalities: ["IMAGE"] },
  });
  if (!r.ok) return { ok: false, res: r.res };
  // deno-lint-ignore no-explicit-any
  const part = r.json?.candidates?.[0]?.content?.parts?.find((p: any) => p.inlineData?.data || p.inline_data?.data);
  const inline = part?.inlineData ?? part?.inline_data;
  if (!inline) return { ok: false, res: fail(502, "no image returned") };
  return { ok: true, mimeType: inline.mimeType ?? inline.mime_type ?? "image/png", data: inline.data };
}

/** The HTTP status of a Hugging Face client error, if it has one. */
function hfStatus(e: unknown): number | undefined {
  if (e instanceof InferenceClientProviderApiError) return e.httpResponse.status;
  if (e instanceof InferenceClientHubApiError) return e.httpResponse.status;
  return undefined;
}

async function hfPicture(admin: SupabaseClient, prompt: string): Promise<Picture> {
  const raw = csv("HF_API_KEYS", []);
  if (raw.length === 0) return { ok: false, res: fail(500, "No Hugging Face API key is set") };
  const keys: Key[] = await Promise.all(raw.map(async (key, index) => ({ id: await keyId(key), key, index })));

  const { data } = await admin.from("hf_key_state").select("key_id, limited_at");
  const limited = new Map<string, number>((data ?? []).map((r: { key_id: string; limited_at: number | string }) => [r.key_id, Number(r.limited_at)]));
  const fresh = keys.filter((k) => !limited.has(k.id)).sort((a, b) => a.index - b.index); // oldest listed first
  const stamped = keys.filter((k) => limited.has(k.id)).sort((a, b) => limited.get(a.id)! - limited.get(b.id)!);

  const started = Date.now();
  let last: Response = fail(502, "Hugging Face did not return an image");
  for (const k of [...fresh, ...stamped]) {
    if (Date.now() - started > HF_BUDGET_MS) break;
    try {
      const blob = await new InferenceClient(k.key).textToImage(
        { provider: "auto", model: HF_IMAGE_MODEL, inputs: prompt },
        { signal: AbortSignal.timeout(HF_ATTEMPT_MS) },
      );
      const bytes = new Uint8Array(await blob.arrayBuffer());
      return { ok: true, mimeType: blob.type || "image/png", data: toBase64(bytes) };
    } catch (e) {
      const status = hfStatus(e);
      if (status === 401 || status === 402 || status === 403 || status === 429) {
        await admin.from("hf_key_state").upsert({ key_id: k.id, limited_at: Date.now() });
        last = fail(status === 402 || status === 429 ? 429 : 502, `Hugging Face key problem (${status})`);
        continue; // the key is the problem: try the next one
      }
      // A bad prompt, a timeout or a loading model would fail the same way on every key.
      return { ok: false, res: fail(502, `Hugging Face failed${status ? ` (${status})` : ""}`) };
    }
  }
  return { ok: false, res: last };
}

function pictureResponse(p: Picture): Response {
  return p.ok ? json({ mimeType: p.mimeType, data: p.data }) : p.res;
}

function promptOf(body: unknown): string | null {
  const prompt = (body as { prompt?: string } | undefined)?.prompt;
  return prompt && prompt.length <= 1000 ? prompt : null;
}

const PROVIDERS: Record<string, Provider> = {
  gemini: async (admin, body) => {
    const r = await generate(admin, TEXT_MODELS, body);
    return r.ok ? json(r.json) : r.res;
  },
  "gemini-image": async (admin, body) => {
    const prompt = promptOf(body);
    return prompt ? pictureResponse(await geminiPicture(admin, prompt)) : fail(400, "bad prompt");
  },
  "hf-image": async (admin, body) => {
    const prompt = promptOf(body);
    return prompt ? pictureResponse(await hfPicture(admin, prompt)) : fail(400, "bad prompt");
  },
  // Hugging Face first when it has keys, Gemini as the fallback (and the only source when HF has no keys).
  image: async (admin, body) => {
    const prompt = promptOf(body);
    if (!prompt) return fail(400, "bad prompt");
    if (csv("HF_API_KEYS", []).length > 0) {
      const hf = await hfPicture(admin, prompt);
      if (hf.ok) return pictureResponse(hf);
    }
    return pictureResponse(await geminiPicture(admin, prompt));
  },
};

Deno.serve(async (req) => {
  if (req.method !== "POST") return fail(405, "method not allowed");

  const auth = req.headers.get("Authorization") ?? "";
  const url = Deno.env.get("SUPABASE_URL")!;
  const asUser = createClient(url, Deno.env.get("SUPABASE_ANON_KEY")!, {
    global: { headers: { Authorization: auth } },
  });
  const { data: { user } } = await asUser.auth.getUser();
  if (!user) return fail(401, "unauthorized");

  let payload: { provider?: string; body?: unknown };
  try {
    payload = await req.json();
  } catch {
    return fail(400, "invalid json");
  }
  const run = payload.provider ? PROVIDERS[payload.provider] : undefined;
  if (!run) return fail(400, "unknown provider");

  const admin = createClient(url, Deno.env.get("SUPABASE_SERVICE_ROLE_KEY")!);
  const day = new Date().toISOString().slice(0, 10);
  const { data: row } = await admin.from("ai_usage").select("calls")
    .eq("user_id", user.id).eq("day", day).maybeSingle();
  const calls = row?.calls ?? 0;
  if (DAILY_LIMIT > 0 && calls >= DAILY_LIMIT) return fail(429, "daily limit reached");
  await admin.from("ai_usage").upsert({ user_id: user.id, day, calls: calls + 1 });

  return run(admin, payload.body);
});
