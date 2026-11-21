# Level and frequency data (Phase 2A step 7)

Built by `tools/levels/build_levels.py` into `app/src/main/assets/levels/<lang>.tsv`. Check each licence again before anything is distributed beyond personal use.

| Data | Used for | Source | Licence |
|---|---|---|---|
| Frequency rank, th en zh ko es fr de vi id ms | `Freq #n` chip | [hermitdave/FrequencyWords](https://github.com/hermitdave/FrequencyWords), 2018 list, top 30,000 (from OpenSubtitles) | repository MIT; the word lists are derived from OpenSubtitles and are published under CC BY-SA 4.0 (verify before sharing) |
| Frequency rank, ja | `Freq #n` chip | [wordfreq](https://github.com/rspeer/wordfreq) (Python package) | code Apache 2.0; data mixes several sources, CC BY-SA 4.0 overall (attribution required) |
| JLPT N1 to N5 | `JLPT Nx` chip | Jonathan Waller's [JLPT Resources](http://www.tanos.co.uk/jlpt/), through [stephenmk/yomitan-jlpt-vocab](https://github.com/stephenmk/yomitan-jlpt-vocab) `original_data` | CC BY (attribution required) |
| HSK 3.0 and 2.0 | `HSK n` chip | [drkameleon/complete-hsk-vocabulary](https://github.com/drkameleon/complete-hsk-vocabulary) | MIT |
| CEFR A1 to B2 | `CEFR xx` chip | CEFR-J Wordlist 1.5 (Tono Laboratory, TUFS), via [openlanguageprofiles/olp-en-cefrj](https://github.com/openlanguageprofiles/olp-en-cefrj) | free for research and commercial use with citation |
| CEFR C1 and C2 | `CEFR xx` chip | Octanove Vocabulary Profile C1/C2 1.0 (same repository) | CC BY-SA 4.0 |

Attribution to show in the app (About / licences screen, still to build): Jonathan Waller (JLPT), CEFR-J / Tono Laboratory TUFS and Octanove Labs (CEFR), OpenSubtitles-derived FrequencyWords and wordfreq (frequency).

Not covered: TOPIK (Korean), CEFR for Spanish, French, German and the rest, JLPT-style levels for Thai, Vietnamese and Indonesian.

Thai: the FrequencyWords Thai list has about 1,250 garbled (double-encoded) rows, English words, fragments and run-together phrases. `tools/levels/clean_thai.py` repairs or drops them (30,000 rows become about 12,600) and renumbers the ranks. Clip recommendations (roadmap 4.2) give Thai and the other rank-only languages approximate CEFR-style bands from that rank (`ClipLevels.RANK_BANDS`); their numbers are shown with `~`.

Arabic (`ar`, Quranic): rank = frequency rank of the word's lemma in the Quran (1 = most common), shown as `Quran freq #n`; levels `Quran 1-6` are bands of that rank (about 50 / 70 / 80 / 90 / 95 % of the running words). Source: Quranic Arabic Corpus morphology (via github.com/mustafa0x/quran-morphology). Keys are stored without vowel marks; lookups ignore them. Check the corpus licence (GNU GPL / attribution) before sharing the app beyond personal use. Malay (`ms`) uses the same OpenSubtitles frequency list as the other languages (rank only, CEFR-style bands).
