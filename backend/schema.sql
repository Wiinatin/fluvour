-- Fluvour backend schema (Postgres / Supabase). Mirrors the Room entities in app/data/Entities.kt.
-- Rules: uuid ids generated on the device, user_id owner column on every table, text-only storage,
-- row level security so each user sees only their own rows. NOT yet applied to any project.
-- A project that already has these tables takes backend/migrations/*.sql instead (idempotent, in order).

create table if not exists decks (
  id uuid primary key, user_id uuid not null default auth.uid(), language_code text not null, name text not null,
  created_at bigint not null, updated_at bigint not null, deleted boolean not null default false);

create table if not exists sources (
  id uuid primary key, user_id uuid not null default auth.uid(),
  kind text not null check (kind in ('article','video','comic','speaking','manual')),
  title text not null, url text, meta text,
  created_at bigint not null, updated_at bigint not null, deleted boolean not null default false);

create table if not exists sentences (
  id uuid primary key, user_id uuid not null default auth.uid(), language_code text not null, text text not null,
  translation text, source_id uuid references sources(id), position text,
  created_at bigint not null, updated_at bigint not null, deleted boolean not null default false);

create table if not exists cards (
  id uuid primary key, user_id uuid not null default auth.uid(), deck_id uuid not null references decks(id),
  language_code text not null, word text not null, meaning text not null, example text, notes text,
  variant_word text, variant_example text, root text, lemma text,   -- Malay Sarawak form / Arabic root + lemma (migration 002)
  variant_example_meaning text,   -- Malay: meaning of the Sarawak example (migration 003)
  image_prompt text, image_url text, sentence_id uuid references sentences(id),
  phase text not null default 'NEW', stability double precision not null default 0, difficulty double precision not null default 0,
  step int not null default 0, last_review_at bigint, due_at bigint, scheduled_days int not null default 0,
  reps int not null default 0, lapses int not null default 0,
  created_at bigint not null, updated_at bigint not null, deleted boolean not null default false);
create index if not exists cards_due on cards (user_id, language_code, due_at);

-- One row per way of studying a card (learning path): WORD, MEANING, CLIP, each with its own memory state. Same fields as cards' state.
-- Ids are derived on the device from card id + kind, so two devices never make different rows for the same facet.
create table if not exists card_facets (
  id uuid primary key, user_id uuid not null default auth.uid(), card_id uuid not null references cards(id),
  language_code text not null, kind text not null check (kind in ('WORD','MEANING','CLIP','SARAWAK')),
  phase text not null default 'NEW', stability double precision not null default 0, difficulty double precision not null default 0,
  step int not null default 0, last_review_at bigint, due_at bigint, scheduled_days int not null default 0,
  reps int not null default 0, lapses int not null default 0,
  unlocked_at bigint,   -- null = locked
  updated_at bigint not null, deleted boolean not null default false,
  unique (card_id, kind));
create index if not exists card_facets_due on card_facets (user_id, language_code, kind, due_at);

create table if not exists review_log (
  id uuid primary key, user_id uuid not null default auth.uid(), card_id uuid not null references cards(id),
  rating smallint not null check (rating between 1 and 4), reviewed_at bigint not null,
  elapsed_days double precision not null, stability_after double precision not null, difficulty_after double precision not null,
  facet_id uuid references card_facets(id));   -- null on reviews made before facets existed
create index if not exists review_log_facet on review_log (facet_id);

create table if not exists speaking_topics (
  id uuid primary key, user_id uuid not null default auth.uid(), language_code text not null, title text not null,
  created_at bigint not null, updated_at bigint not null, deleted boolean not null default false);

create table if not exists speaking_versions (
  id uuid primary key, user_id uuid not null default auth.uid(), topic_id uuid not null references speaking_topics(id),
  version_no int not null, transcript text not null, improved text, new_words text,   -- no audio column: audio stays on device
  created_at bigint not null, updated_at bigint not null, deleted boolean not null default false);

-- Per-user daily AI usage, used by the ai-proxy function to protect free-tier quotas.
create table if not exists ai_usage (
  user_id uuid not null, day date not null default current_date, calls int not null default 0,
  primary key (user_id, day));

-- Row level security: owner only.
do $$
declare t text;
begin
  foreach t in array array['decks','sources','sentences','cards','card_facets','review_log','speaking_topics','speaking_versions'] loop
    execute format('alter table %I enable row level security', t);
    execute format('drop policy if exists owner_all on %I', t);
    execute format('create policy owner_all on %I for all using (user_id = auth.uid()) with check (user_id = auth.uid())', t);
  end loop;
end $$;
alter table ai_usage enable row level security;   -- no policy: only the service role (edge function) touches it
