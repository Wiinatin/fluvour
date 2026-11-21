-- Arabic + Malay step 2: four optional columns on cards, and allow the SARAWAK facet kind.
-- Safe to run more than once, after 001_card_facets.sql. Run in the Supabase SQL editor (project "fluvour" must be un-paused first).
-- Old cards and facets are not touched.

alter table cards add column if not exists variant_word text;      -- Malay: Sarawak form of the word
alter table cards add column if not exists variant_example text;   -- Malay: Sarawak example sentence
alter table cards add column if not exists root text;              -- Arabic: three-letter root
alter table cards add column if not exists lemma text;             -- Arabic: dictionary form

-- card_facets.kind was limited to WORD / MEANING / CLIP; widen it. The constraint name is looked up, not assumed.
do $$
declare c text;
begin
  for c in select conname from pg_constraint
           where conrelid = 'public.card_facets'::regclass and contype = 'c' and pg_get_constraintdef(oid) ilike '%kind%' loop
    execute format('alter table card_facets drop constraint %I', c);
  end loop;
  alter table card_facets add constraint card_facets_kind_check check (kind in ('WORD','MEANING','CLIP','SARAWAK'));
end $$;
