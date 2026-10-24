# Level and frequency data (Phase 2A step 7)

Built by `tools/levels/build_levels.py` into `app/src/main/assets/levels/<lang>.tsv`. Check each licence again before anything is distributed beyond personal use.

| Data | Used for | Source | Licence |
|---|---|---|---|
| Frequency rank, th en zh ko es fr de vi id | `Freq #n` chip | [hermitdave/FrequencyWords](https://github.com/hermitdave/FrequencyWords), 2018 list, top 30,000 (from OpenSubtitles) | repository MIT; the word lists are derived from OpenSubtitles and are published under CC BY-SA 4.0 (verify before sharing) |
| Frequency rank, ja | `Freq #n` chip | [wordfreq](https://github.com/rspeer/wordfreq) (Python package) | code Apache 2.0; data mixes several sources, CC BY-SA 4.0 overall (attribution required) |
| JLPT N1 to N5 | `JLPT Nx` chip | Jonathan Waller's [JLPT Resources](http://www.tanos.co.uk/jlpt/), through [stephenmk/yomitan-jlpt-vocab](https://github.com/stephenmk/yomitan-jlpt-vocab) `original_data` | CC BY (attribution required) |
| HSK 3.0 and 2.0 | `HSK n` chip | [drkameleon/complete-hsk-vocabulary](https://github.com/drkameleon/complete-hsk-vocabulary) | MIT |
| CEFR A1 to B2 | `CEFR xx` chip | CEFR-J Wordlist 1.5 (Tono Laboratory, TUFS), via [openlanguageprofiles/olp-en-cefrj](https://github.com/openlanguageprofiles/olp-en-cefrj) | free for research and commercial use with citation |
| CEFR C1 and C2 | `CEFR xx` chip | Octanove Vocabulary Profile C1/C2 1.0 (same repository) | CC BY-SA 4.0 |

Attribution to show in the app (About / licences screen, still to build): Jonathan Waller (JLPT), CEFR-J / Tono Laboratory TUFS and Octanove Labs (CEFR), OpenSubtitles-derived FrequencyWords and wordfreq (frequency).

Not covered: TOPIK (Korean), CEFR for Spanish, French, German and the rest, JLPT-style levels for Thai, Vietnamese and Indonesian.
