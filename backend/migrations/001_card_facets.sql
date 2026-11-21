-- Learning path step 3: card_facets + review_log.facet_id. Safe to run more than once, on a project that already has the
-- 2026 schema (decks, sources, sentences, cards, review_log). Run in the Supabase SQL editor.

create table if not exists card_facets (
  id uuid primary key, user_id uuid not null default auth.uid(), card_id uuid not null references cards(id),
  language_code text not null, kind text not null check (kind in ('WORD','MEANING','CLIP')),
  phase text not null default 'NEW', stability double precision not null default 0, difficulty double precision not null default 0,
  step int not null default 0, last_review_at bigint, due_at bigint, scheduled_days int not null default 0,
  reps int not null default 0, lapses int not null default 0,
  unlocked_at bigint,   -- null = locked
  updated_at bigint not null, deleted boolean not null default false,
  unique (card_id, kind));
create index if not exists card_facets_due on card_facets (user_id, language_code, kind, due_at);

alter table review_log add column if not exists facet_id uuid references card_facets(id);
create index if not exists review_log_facet on review_log (facet_id);

alter table card_facets enable row level security;
drop policy if exists owner_all on card_facets;
create policy owner_all on card_facets for all using (user_id = auth.uid()) with check (user_id = auth.uid());

-- Optional, run AFTER the app has synced once (so every card's WORD facet is on the server):
-- links reviews made before facets existed to the card's WORD facet.
update review_log set facet_id = (select f.id from card_facets f where f.card_id = review_log.card_id and f.kind = 'WORD')
  where facet_id is null;
