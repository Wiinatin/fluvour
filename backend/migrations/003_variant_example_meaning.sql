-- Malay: what the Sarawak example sentence means. One optional column on cards.
-- Safe to run more than once, after 002_arabic_malay.sql. Run in the Supabase SQL editor BEFORE syncing the build that has this field
-- (the app sends this column on every card push; without it a sync fails).
-- Old cards are not touched.

alter table cards add column if not exists variant_example_meaning text;   -- Malay: meaning of the Sarawak example sentence
