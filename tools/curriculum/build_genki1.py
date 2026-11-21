#!/usr/bin/env python3
"""Builds app/src/main/assets/curriculum/ja-genki1.tsv (learning path step 5).

The Genki I path, split into capped units (about 8 to 12 words, 1 or 2 grammar points).
Content is Fluvour's own: word lists with readings and a short English gloss, grammar point
labels, a can-do line. No book text, no page or chapter anchors.

Run from the project root:  python3 tools/curriculum/build_genki1.py
Edit the data below, rerun, and commit the generated TSV.
"""
import os
import sys

OUT = os.path.join("app", "src", "main", "assets", "curriculum", "ja-genki1.tsv")
PATH_ID, PATH_TITLE, LANG, LEVEL = "genki1", "Genki I", "ja", "N5"

# Each unit: (slug, title, minutes, can-do, [prerequisite slugs], [grammar points], words text)
# Words text: one "word;reading;gloss" per line. No ';' or '|' or tab inside a field.
U = []


def unit(slug, title, minutes, can_do, prereqs, grammar, words):
    U.append((slug, title, minutes, can_do, prereqs, grammar, words))


unit("greetings", "Greetings and thanks", 15,
     "I can greet people, say thanks and say sorry.", [],
     ["Set phrases for greeting, thanks and apology"], """
おはようございます;おはようございます;good morning
こんにちは;こんにちは;hello, good afternoon
こんばんは;こんばんは;good evening
さようなら;さようなら;goodbye
ありがとうございます;ありがとうございます;thank you
すみません;すみません;excuse me, sorry
ごめんなさい;ごめんなさい;I am sorry
おやすみなさい;おやすみなさい;good night
はい;はい;yes
いいえ;いいえ;no
どうぞ;どうぞ;please, go ahead
よろしくお願いします;よろしくおねがいします;please treat me well
""")

unit("num10", "Numbers 0 to 10", 15,
     "I can count and say numbers from 0 to 10.", [],
     ["Numbers 0 to 10", "Extra readings: 4 is よん or し, 7 is なな or しち, 9 is きゅう or く"], """
ゼロ;ぜろ;zero
一;いち;one
二;に;two
三;さん;three
四;よん;four
五;ご;five
六;ろく;six
七;なな;seven
八;はち;eight
九;きゅう;nine
十;じゅう;ten
""")

unit("numbig", "Big numbers and yen", 20,
     "I can say numbers up to 10,000 and ask a price.", ["num10"],
     ["Big numbers with 百, 千 and 万 (350, 1200, 25000)", "Asking prices with いくらですか"], """
百;ひゃく;hundred
千;せん;thousand
万;まん;ten thousand
円;えん;yen
いくら;いくら;how much
何;なん;what
お金;おかね;money
全部;ぜんぶ;all, everything
いらっしゃいませ;いらっしゃいませ;welcome (shop greeting)
ください;ください;please give me
お願いします;おねがいします;please (asking for something)
とんかつ;とんかつ;pork cutlet
""")

unit("people", "People and introductions", 20,
     "I can introduce myself and say who someone is.", ["greetings"],
     ["X は Y です (X is Y)", "Adding さん to a name"], """
私;わたし;I, me
あなた;あなた;you
先生;せんせい;teacher
学生;がくせい;student
大学生;だいがくせい;university student
留学生;りゅうがくせい;international student
友達;ともだち;friend
さん;さん;Mr, Ms (polite title)
はじめまして;はじめまして;nice to meet you
人;じん;person of a country
""")

unit("countries", "Countries and languages", 20,
     "I can say where I am from and what language I speak.", ["people"],
     ["Questions with ですか and 何", "Nationality with 人 and language with 語"], """
日本;にほん;Japan
アメリカ;アメリカ;America
イギリス;イギリス;Britain
中国;ちゅうごく;China
韓国;かんこく;South Korea
カナダ;カナダ;Canada
オーストラリア;オーストラリア;Australia
日本語;にほんご;Japanese language
英語;えいご;English language
国;くに;country
""")

unit("university", "University and majors", 20,
     "I can say my school, my year and my major.", ["people", "countries"],
     ["Noun の Noun (X's Y, affiliation)", "School years with ～年生"], """
大学;だいがく;university
専攻;せんこう;major
歴史;れきし;history
経済;けいざい;economics
音楽;おんがく;music
文学;ぶんがく;literature
会社員;かいしゃいん;office worker
高校;こうこう;high school
年生;ねんせい;school year, as in 2nd year
医者;いしゃ;doctor
コンピューター;コンピューター;computer
ビジネス;ビジネス;business
""")

unit("time", "Telling time", 20,
     "I can say and ask what time it is.", ["num10"],
     ["Clock time with ～時 and ～分 (ふん or ぷん)", "半 for half past, 午前 and 午後 for a.m. and p.m."], """
時;じ;o'clock
分;ふん;minute (ぷん after 1, 3, 4, 6, 8, 10)
半;はん;half past
午前;ごぜん;a.m.
午後;ごご;p.m.
今;いま;now
何時;なんじ;what time
何分;なんぷん;how many minutes
時間;じかん;time, hours
一時間;いちじかん;one hour
ぐらい;ぐらい;about (amount)
""")

unit("age", "Age and phone numbers", 20,
     "I can say my age and give my phone number.", ["num10", "time"],
     ["Age with ～歳, and 二十歳 read はたち", "Phone numbers: read each digit, の between groups"], """
歳;さい;years old
何歳;なんさい;how old
二十歳;はたち;twenty years old
電話;でんわ;telephone
番号;ばんごう;number
電話番号;でんわばんごう;phone number
何番;なんばん;which number
番;ばん;number No. (as in 二番)
携帯;けいたい;cell phone
もしもし;もしもし;hello (on the phone)
""")

unit("this", "This, that and which", 20,
     "I can point to things and ask what they are.", ["people"],
     ["これ, それ, あれ, どれ (this, that, that over there, which)", "Asking 何ですか and answering with です"], """
これ;これ;this
それ;それ;that
あれ;あれ;that over there
どれ;どれ;which one
本;ほん;book
辞書;じしょ;dictionary
雑誌;ざっし;magazine
新聞;しんぶん;newspaper
ノート;ノート;notebook
鉛筆;えんぴつ;pencil
黒板;こくばん;blackboard
消しゴム;けしゴム;eraser
""")

unit("thisnoun", "This, that and everyday objects", 20,
     "I can talk about everyday objects near me or far from me.", ["this"],
     ["この, その, あの, どの before a noun", "Asking which one with どの plus a noun"], """
この;この;this (before a noun)
その;その;that (before a noun)
あの;あの;that over there (before a noun)
どの;どの;which (before a noun)
かばん;かばん;bag
傘;かさ;umbrella
靴;くつ;shoes
時計;とけい;watch, clock
財布;さいふ;wallet
鍵;かぎ;key
机;つくえ;desk
椅子;いす;chair
""")

unit("places", "Places: here, there, where", 20,
     "I can ask where a place is and say where I am.", ["this"],
     ["ここ, そこ, あそこ, どこ (here, there, over there, where)", "X は ～です to say where X is"], """
ここ;ここ;here
そこ;そこ;there
あそこ;あそこ;over there
どこ;どこ;where
教室;きょうしつ;classroom
図書館;としょかん;library
食堂;しょくどう;cafeteria
駅;えき;station
銀行;ぎんこう;bank
部屋;へや;room
トイレ;トイレ;toilet
学校;がっこう;school
""")

unit("whose", "Whose is it?", 20,
     "I can say whose something is.", ["university", "thisnoun"],
     ["Possession with の (私の本) and 誰の", "X も (also, too)"], """
誰;だれ;who
犬;いぬ;dog
猫;ねこ;cat
車;くるま;car
自転車;じてんしゃ;bicycle
ペン;ペン;pen
写真;しゃしん;photo
名前;なまえ;name
眼鏡;めがね;glasses
ジーンズ;ジーンズ;jeans
スマホ;スマホ;smartphone
Tシャツ;ティーシャツ;T-shirt
""")

unit("negatives", "Negatives and ね or よ", 20,
     "I can say something is not so and check with ね.", ["people", "whose"],
     ["Noun じゃありません (is not)", "Sentence-ending ね (right?) and よ (I tell you)"], """
帽子;ぼうし;hat
名刺;めいし;business card
手帳;てちょう;planner, notebook
地図;ちず;map
切符;きっぷ;ticket
絵;え;picture, painting
花;はな;flower
木;き;tree
山;やま;mountain
川;かわ;river
じゃあ;じゃあ;then, well then
どうも;どうも;thanks (casual)
""")

unit("counting", "Counting things and people", 20,
     "I can count things and people.", ["num10"],
     ["Counting things from ひとつ to とお", "Counting people: ひとり, ふたり, then ～にん"], """
一つ;ひとつ;one thing
二つ;ふたつ;two things
三つ;みっつ;three things
四つ;よっつ;four things
五つ;いつつ;five things
六つ;むっつ;six things
七つ;ななつ;seven things
八つ;やっつ;eight things
九つ;ここのつ;nine things
十;とお;ten things
一人;ひとり;one person
二人;ふたり;two people
""")

unit("family", "Family", 20,
     "I can talk about my family and ask about someone else's.", ["whose", "counting"],
     ["Own family words versus polite words for someone else's family", "Asking family size with 何人家族"], """
家族;かぞく;family
父;ちち;my father
母;はは;my mother
お父さん;おとうさん;father (polite)
お母さん;おかあさん;mother (polite)
兄;あに;my older brother
姉;あね;my older sister
弟;おとうと;my younger brother
妹;いもうと;my younger sister
兄弟;きょうだい;siblings
一人で;ひとりで;alone
""")

unit("verbs1", "Everyday verbs", 25,
     "I can say what I do, such as eat, read and study.", ["this"],
     ["ます form: polite present and future (食べます)", "Object marker を"], """
食べる;たべる;eat
飲む;のむ;drink
見る;みる;see, watch
聞く;きく;listen, ask
読む;よむ;read
書く;かく;write
話す;はなす;speak
する;する;do
勉強する;べんきょうする;study
買う;かう;buy
テニス;テニス;tennis
ええ;ええ;yes (polite)
""")

unit("food", "Food and drink", 20,
     "I can name food and drinks and order them.", ["verbs1"],
     ["～をください and ～をお願いします (ordering)", "A か B (A or B)"], """
ご飯;ごはん;rice, meal
パン;パン;bread
肉;にく;meat
魚;さかな;fish
野菜;やさい;vegetables
果物;くだもの;fruit
卵;たまご;egg
水;みず;water
お茶;おちゃ;tea
牛乳;ぎゅうにゅう;milk
コーヒー;コーヒー;coffee
メニュー;メニュー;menu
""")

unit("going", "Going and coming", 25,
     "I can say where and when I go, come and wake up.", ["verbs1", "places", "time"],
     ["Direction with へ or に (学校へ行きます)", "Time with に (7時に起きます)"], """
行く;いく;go
来る;くる;come
帰る;かえる;return home
起きる;おきる;wake up, get up
寝る;ねる;sleep, go to bed
会う;あう;meet
毎日;まいにち;every day
今日;きょう;today
明日;あした;tomorrow
家;いえ;house, home
ごろ;ごろ;at about (a time)
でも;でも;but
""")

unit("timeofday", "Times of day and で", 20,
     "I can say what I do at which time of day and where.", ["going"],
     ["Place + で for where an action happens", "Times of day and meals"], """
朝;あさ;morning
昼;ひる;noon, daytime
夜;よる;night
朝ご飯;あさごはん;breakfast
昼ご飯;ひるごはん;lunch
晩ご飯;ばんごはん;dinner
毎朝;まいあさ;every morning
今晩;こんばん;tonight
週末;しゅうまつ;weekend
アイスクリーム;アイスクリーム;ice cream
ハンバーガー;ハンバーガー;hamburger
お酒;おさけ;sake, alcohol
""")

unit("frequency", "How often and ません", 20,
     "I can say how often I do things and what I do not do.", ["timeofday"],
     ["ません (polite negative)", "Frequency words with ません (あまり, 全然)"], """
いつも;いつも;always
よく;よく;often
時々;ときどき;sometimes
あまり;あまり;not very (used with a negative)
全然;ぜんぜん;not at all (used with a negative)
たいてい;たいてい;usually
一緒に;いっしょに;together
映画;えいが;movie
テレビ;テレビ;TV
ちょっと;ちょっと;a little
毎晩;まいばん;every night
いつ;いつ;when
""")

unit("invitations", "Invitations", 20,
     "I can invite someone to do something and answer an invitation.", ["frequency"],
     ["Inviting with ～ませんか", "Suggesting with ～ましょう"], """
今度;こんど;next time
勉強;べんきょう;study
宿題;しゅくだい;homework
喫茶店;きっさてん;cafe
散歩;さんぽ;walk
買い物;かいもの;shopping
旅行;りょこう;trip
約束;やくそく;promise, appointment
デート;デート;date (romantic)
カフェ;カフェ;cafe
そうですね;そうですね;that's right, let me see
どうですか;どうですか;how about?
""")

unit("existence", "There is: あります and います", 20,
     "I can say what there is and who is there.", ["places", "verbs1"],
     ["あります for things and います for people and animals", "Place に Noun が あります"], """
ある;ある;exist (things)
いる;いる;exist (people, animals)
人;ひと;person
物;もの;thing
誰か;だれか;someone
何か;なにか;something
動物;どうぶつ;animal
公園;こうえん;park
建物;たてもの;building
店;みせ;shop
町;まち;town, city
ホテル;ホテル;hotel
""")

unit("positions", "Positions", 20,
     "I can say where things are in relation to each other.", ["existence"],
     ["Position words with の (机の上)", "Noun は Place に あります or います"], """
上;うえ;above, on
下;した;below, under
中;なか;inside
前;まえ;front, before
後ろ;うしろ;behind
隣;となり;next to
近く;ちかく;near
外;そと;outside
右;みぎ;right
左;ひだり;left
バス停;バスてい;bus stop
レストラン;レストラン;restaurant
""")

unit("past", "Past: でした and ました", 25,
     "I can say what I did and what something was last week or last year.", ["frequency", "negatives"],
     ["Past of です: でした and じゃありませんでした", "Past of ます: ました and ませんでした"], """
昨日;きのう;yesterday
先週;せんしゅう;last week
先月;せんげつ;last month
去年;きょねん;last year
今週;こんしゅう;this week
今月;こんげつ;this month
今年;ことし;this year
来週;らいしゅう;next week
来月;らいげつ;next month
来年;らいねん;next year
ゲーム;ゲーム;game
""")

unit("weekdays", "Days of the week", 15,
     "I can say the day of the week and a birthday.", ["past"],
     ["Days of the week (月曜日 to 日曜日)", "Asking 何曜日ですか"], """
月曜日;げつようび;Monday
火曜日;かようび;Tuesday
水曜日;すいようび;Wednesday
木曜日;もくようび;Thursday
金曜日;きんようび;Friday
土曜日;どようび;Saturday
日曜日;にちようび;Sunday
何曜日;なんようび;what day of the week
誕生日;たんじょうび;birthday
休み;やすみ;day off, holiday
""")

unit("months", "Months", 15,
     "I can say and ask the month.", ["num10", "weekdays"],
     ["Months: number + 月 (四月 しがつ, 七月 しちがつ, 九月 くがつ)", "Asking 何月ですか"], """
一月;いちがつ;January
二月;にがつ;February
三月;さんがつ;March
四月;しがつ;April
五月;ごがつ;May
六月;ろくがつ;June
七月;しちがつ;July
八月;はちがつ;August
九月;くがつ;September
十月;じゅうがつ;October
十一月;じゅういちがつ;November
十二月;じゅうにがつ;December
""")

unit("monthdays", "Days of the month", 20,
     "I can say and ask the date.", ["months"],
     ["Days 1 to 10 and 20 have special readings (一日 ついたち, 二十日 はつか)", "Dates go month first, then day (三月五日)"], """
一日;ついたち;1st
二日;ふつか;2nd
三日;みっか;3rd
四日;よっか;4th
五日;いつか;5th
六日;むいか;6th
七日;なのか;7th
八日;ようか;8th
九日;ここのか;9th
十日;とおか;10th
二十日;はつか;20th
何日;なんにち;what day of the month
""")

unit("fromuntil", "From and until", 20,
     "I can say from when to when something lasts.", ["time", "monthdays"],
     ["から and まで (from, until) with times and days", "Every week, month and year with 毎～"], """
授業;じゅぎょう;class, lesson
夏休み;なつやすみ;summer vacation
冬休み;ふゆやすみ;winter vacation
春休み;はるやすみ;spring vacation
昼休み;ひるやすみ;lunch break
試験;しけん;exam
毎週;まいしゅう;every week
毎月;まいつき;every month
毎年;まいとし;every year
アルバイト;アルバイト;part-time job
クラス;クラス;class
レポート;レポート;report, term paper
""")

unit("iadj1", "い-adjectives 1", 20,
     "I can describe things as big, small, new, old, cheap or expensive.", ["thisnoun"],
     ["い-adjectives as ～いです and before nouns (大きい家)", "Asking what something is like with どうですか"], """
大きい;おおきい;big
小さい;ちいさい;small
新しい;あたらしい;new
古い;ふるい;old (not for people)
高い;たかい;expensive, tall
安い;やすい;cheap
いい;いい;good
悪い;わるい;bad
面白い;おもしろい;interesting, funny
つまらない;つまらない;boring
Lサイズ;エルサイズ;size L
""")

unit("iadj2", "い-adjectives 2", 25,
     "I can describe weather, food and how I feel, and say what is not so.", ["iadj1"],
     ["Negative い-adjectives: ～くないです", "いい becomes よくない in the negative"], """
暑い;あつい;hot (weather)
寒い;さむい;cold (weather)
冷たい;つめたい;cold (to the touch)
暖かい;あたたかい;warm
難しい;むずかしい;difficult
忙しい;いそがしい;busy
楽しい;たのしい;fun
おいしい;おいしい;delicious
天気;てんき;weather
優しい;やさしい;kind, gentle
怖い;こわい;frightening
""")

unit("naadj", "な-adjectives", 25,
     "I can describe people and places with な-adjectives.", ["iadj1", "negatives"],
     ["な-adjectives with です and before nouns (きれいな花)", "Negative な-adjectives: じゃありません"], """
きれい;きれい;pretty, clean
元気;げんき;healthy, energetic
静か;しずか;quiet
賑やか;にぎやか;lively
有名;ゆうめい;famous
親切;しんせつ;kind
暇;ひま;free (time)
便利;べんり;convenient
大変;たいへん;hard, tough
簡単;かんたん;easy, simple
大嫌い;だいきらい;to hate
""")

unit("likes", "Likes and skills", 20,
     "I can say what I like, dislike and am good at.", ["naadj", "verbs1"],
     ["X は Y が 好きです (likes)", "Skill with 上手 and 下手 plus が"], """
好き;すき;like
嫌い;きらい;dislike
上手;じょうず;good at
下手;へた;bad at
大好き;だいすき;love, like very much
料理;りょうり;cooking
スポーツ;スポーツ;sports
歌;うた;song
野球;やきゅう;baseball
趣味;しゅみ;hobby
食べ物;たべもの;food
飲み物;のみもの;drink
""")

unit("adjpast", "Adjectives in the past", 25,
     "I can say how something was, such as a trip or a season.", ["iadj2", "naadj", "past"],
     ["Past い-adjectives: ～かったです and ～くなかったです", "Past な-adjectives: でした and じゃありませんでした"], """
パーティー;パーティー;party
夏;なつ;summer
冬;ふゆ;winter
春;はる;spring
秋;あき;autumn
雨;あめ;rain
雪;ゆき;snow
海;うみ;sea
季節;きせつ;season
すごく;すごく;extremely
とても;とても;very
どんな;どんな;what kind of
""")

unit("te1", "て-form 1", 25,
     "I can ask someone to do something politely.", ["verbs1", "going"],
     ["て-form: う, つ, る verbs end in って; る-verbs drop る and add て", "～てください (please do)"], """
待つ;まつ;wait
持つ;もつ;hold, have
立つ;たつ;stand
座る;すわる;sit
入る;はいる;enter
出る;でる;go out, leave
開ける;あける;open (something)
閉める;しめる;close (something)
使う;つかう;use
教える;おしえる;teach, tell
ゆっくり;ゆっくり;slowly
もう一度;もういちど;once more
""")

unit("te2", "て-form 2", 25,
     "I can make the て-form of more verbs and ask politely.", ["te1"],
     ["て-form: く becomes いて, ぐ becomes いで, む and ぶ become んで, す becomes して", "Irregular: 行って, して, 来て"], """
泳ぐ;およぐ;swim
遊ぶ;あそぶ;play, hang out
急ぐ;いそぐ;hurry
歩く;あるく;walk
走る;はしる;run
歌う;うたう;sing
踊る;おどる;dance
休む;やすむ;rest, be absent
働く;はたらく;work
呼ぶ;よぶ;call
わかる;わかる;to understand
やる;やる;to do, to perform
""")

unit("permission", "Permission", 20,
     "I can ask for permission and say what is not allowed.", ["te1", "te2"],
     ["～てもいいですか (may I?)", "～てはいけません (must not)"], """
窓;まど;window
ドア;ドア;door
電気;でんき;light, electricity
音;おと;sound
手;て;hand
声;こえ;voice
席;せき;seat
荷物;にもつ;luggage
時;とき;when, time of
出かける;でかける;to go out
""")

unit("teiru", "Doing and being: ～ている", 25,
     "I can say what someone is doing and where they live.", ["te2"],
     ["～ている for an action in progress (食べている)", "～ている for a state (住んでいる, 知っている)"], """
住む;すむ;live
知る;しる;know
着る;きる;wear (on the body)
履く;はく;wear (on the feet or legs)
結婚する;けっこんする;get married
髪;かみ;hair
服;ふく;clothes
子供;こども;child
背;せ;height, back
目;め;eye
""")

unit("describing", "Describing people", 25,
     "I can describe a person's looks and join sentences.", ["teiru", "iadj1", "naadj"],
     ["て-form to join sentences (起きて、食べました)", "Joining adjectives: い becomes くて, な becomes で"], """
長い;ながい;long
短い;みじかい;short
若い;わかい;young
かわいい;かわいい;cute
かっこいい;かっこいい;cool, good-looking
顔;かお;face
頭;あたま;head
足;あし;foot, leg
体;からだ;body
""")

unit("plainpresent", "Casual verbs", 25,
     "I can use casual verbs, as with friends.", ["te2"],
     ["Plain present: dictionary form and ～ない", "Sorting verbs into る-verbs and う-verbs"], """
作る;つくる;make
取る;とる;take
売る;うる;sell
貸す;かす;lend
借りる;かりる;borrow
送る;おくる;send
習う;ならう;learn
乗る;のる;ride
降りる;おりる;get off
着く;つく;arrive
""")

unit("plain2", "Thoughts and quotes", 25,
     "I can say what I think and quote what someone said.", ["plainpresent"],
     ["Plain forms of です and adjectives (だ, 高い, 静かだ)", "Quoting with ～と思います and ～と言いました"], """
思う;おもう;think
言う;いう;say
考える;かんがえる;think about
分かる;わかる;understand
彼;かれ;he, boyfriend
彼女;かのじょ;she, girlfriend
意見;いけん;opinion
本当;ほんとう;true, really
多分;たぶん;probably
僕;ぼく;I (used by men)
""")

unit("plainpast", "Casual past", 25,
     "I can use casual past forms.", ["plain2", "adjpast"],
     ["Plain past: ～た (from the て-form) and ～なかった", "Plain past of です and adjectives (だった, かった)"], """
昔;むかし;long ago
さっき;さっき;a little while ago
もう;もう;already, anymore
まだ;まだ;still, not yet
最近;さいきん;recently
最初;さいしょ;first, beginning
最後;さいご;last, end
この間;このあいだ;the other day
""")

unit("experiences", "Experiences", 25,
     "I can say what I have done before and list things I did.", ["plainpast"],
     ["～たことがあります (have done before)", "Listing actions with ～たり～たりする"], """
外国;がいこく;foreign country
世界;せかい;world
東京;とうきょう;Tokyo
京都;きょうと;Kyoto
大阪;おおさか;Osaka
富士山;ふじさん;Mt. Fuji
北海道;ほっかいどう;Hokkaido
温泉;おんせん;hot spring
お寺;おてら;temple
神社;じんじゃ;shrine
""")

unit("compare", "Comparisons", 25,
     "I can compare two things and say which is best.", ["iadj2", "naadj", "likes"],
     ["X より Y のほうが ～ (comparing two things)", "X の中で Y が一番 ～ (the most)"], """
一番;いちばん;number one, the most
もっと;もっと;more
ずっと;ずっと;much more, all along
同じ;おなじ;same
違う;ちがう;different, wrong
方;ほう;side, the one
どちら;どちら;which of two
両方;りょうほう;both
他;ほか;other
サッカー;サッカー;soccer
手袋;てぶくろ;gloves
""")

unit("want", "Wanting", 20,
     "I can say what I want and what I want to do.", ["verbs1", "likes"],
     ["～たい (want to do)", "Noun が 欲しい (want a thing)"], """
欲しい;ほしい;want (a thing)
仕事;しごと;work, job
将来;しょうらい;future
夢;ゆめ;dream
家;うち;home, house
生活;せいかつ;daily life
計画;けいかく;plan
予定;よてい;schedule, plan
バス;バス;bus
""")

unit("plans", "Plans and errands", 25,
     "I can say where I am going and what I plan to do.", ["want", "going", "invitations"],
     ["～に行く (go in order to do)", "～つもりです (intend to)"], """
空港;くうこう;airport
病院;びょういん;hospital
郵便局;ゆうびんきょく;post office
スーパー;スーパー;supermarket
コンビニ;コンビニ;convenience store
本屋;ほんや;bookstore
映画館;えいがかん;movie theater
遊園地;ゆうえんち;amusement park
サーフィン;サーフィン;surfing
お土産;おみやげ;souvenir
枚;まい;counter for flat things
""")

unit("reasons", "Reasons", 25,
     "I can give a reason and explain how I feel.", ["plain2", "iadj2"],
     ["Giving reasons with ～から", "Explaining with ～んです"], """
理由;りゆう;reason
風邪;かぜ;cold (illness)
熱;ねつ;fever
病気;びょうき;illness
痛い;いたい;painful
薬;くすり;medicine
疲れる;つかれる;get tired
眠い;ねむい;sleepy
具合;ぐあい;condition, state
だから;だから;so, therefore
どうして;どうして;why
それから;それから;and then
""")

unit("advice", "Advice and too much", 25,
     "I can give advice and say something is too much.", ["reasons", "plainpresent"],
     ["～ほうがいいです (had better)", "～すぎる (too much)"], """
無理;むり;impossible, too much
心配;しんぱい;worry
大丈夫;だいじょうぶ;all right, okay
気をつける;きをつける;be careful
早い;はやい;early, fast
遅い;おそい;late, slow
多い;おおい;many
少ない;すくない;few
少し;すこし;a little
たくさん;たくさん;a lot
テスト;テスト;test
""")


# --- Supplement units (g1-48 and up): words and grammar the book teaches that the first 47 units did not cover. ---
# Appended so existing unit ids, cached lessons and graduations stay valid.

unit("looks", "Looks and clothes", 25,
     "I can describe how someone looks and what they are wearing.", ["describing", "likes"],
     ["X は Y が ～ (メアリーさんは背が高い)", "Wearing: かぶる, かける, 着る and はく with ～ている"], """
口;くち;mouth
背が高い;せがたかい;tall
背が低い;せがひくい;short (height)
頭がいい;あたまがいい;smart
かぶる;かぶる;to put on (a hat)
かける;かける;to put on (glasses)
太る;ふとる;to gain weight
痩せる;やせる;to lose weight
男の人;おとこのひと;man
女の人;おんなのひと;woman
""")

unit("relatives", "Other people's family and workplaces", 20,
     "I can name someone else's relatives and say where people work or belong.", ["family"],
     ["Polite words for another person's family (お兄さん, お姉さん, おじいさん)"], """
おじいさん;おじいさん;grandfather, old man
おばあさん;おばあさん;grandmother, old woman
お兄さん;おにいさん;older brother (someone else's)
お姉さん;おねえさん;older sister (someone else's)
会社;かいしゃ;company
デパート;デパート;department store
サークル;サークル;club activity
""")

unit("chores", "Chores and doing it well", 25,
     "I can say what chores I do and what I am good at.", ["likes", "plainpresent"],
     ["Verb + のが好きです or 上手です (like or good at doing)", "～ないでください (please do not)"], """
洗う;あらう;to wash
捨てる;すてる;to throw away
始める;はじめる;to begin
運転する;うんてんする;to drive
洗濯する;せんたくする;to do laundry
掃除する;そうじする;to clean
料理する;りょうりする;to cook
持っていく;もっていく;to take (a thing)
遅くなる;おそくなる;to be late
遅く;おそく;(do something) late
""")

unit("weather", "Weather and が", 25,
     "I can talk about today's weather and the forecast.", ["plain2"],
     ["が marks the new subject (雨が降っています)", "Temperature with 度 (今日は二十度です)"], """
晴れ;はれ;sunny weather
曇り;くもり;cloudy weather
天気予報;てんきよほう;weather forecast
気温;きおん;temperature
度;ど;degrees (temperature)
降る;ふる;to fall (rain, snow)
今朝;けさ;this morning
あさって;あさって;the day after tomorrow
""")

unit("party", "Parties, trips and homestay", 25,
     "I can talk about a party or a homestay and ask what something is like.", ["plainpast", "plain2"],
     ["何か (something) in questions (何か食べますか)", "Asking opinions with どう and どうですか"], """
カメラ;カメラ;camera
カラオケ;カラオケ;karaoke
所;ところ;place
トマト;トマト;tomato
箸;はし;chopsticks
バーベキュー;バーベキュー;barbecue
ホームステイ;ホームステイ;homestay
お風呂;おふろ;bath
スペイン;スペイン;Spain
どう;どう;how
""")

unit("replies", "Everyday replies", 20,
     "I can reply naturally: yes and no in casual speech, nothing in particular, of course.", ["plain2"],
     ["何も and 別に with a negative (nothing, nothing in particular)", "Casual replies うん and ううん"], """
何も;なにも;not anything (with a negative)
別に;べつに;nothing in particular (with a negative)
もちろん;もちろん;of course
よかったら;よかったら;if you like
うん;うん;uh-huh, yes
ううん;ううん;uh-uh, no
残念;ざんねん;too bad
乾杯;かんぱい;cheers
みんなで;みんなで;all together
について;について;about, concerning
""")

unit("colors", "Colors and feelings", 20,
     "I can name colors and describe how someone feels or acts.", ["iadj1", "naadj"],
     ["Colors that are い-adjectives (赤い, 青い, 白い, 黒い)", "Describing a person with い-adjectives and な-adjectives"], """
色;いろ;color
青い;あおい;blue
赤い;あかい;red
黒い;くろい;black
白い;しろい;white
寂しい;さびしい;lonely
意地悪;いじわる;mean-spirited
いい子;いいこ;good child
""")

unit("mailtickets", "Music, mail and tickets", 25,
     "I can talk about concerts, letters, tickets and things I did already.", ["plainpast", "plain2"],
     ["Plain verb before a noun (昨日買ったチケット)", "もう～ました and まだ～ていません"], """
単語;たんご;word, vocabulary
作文;さくぶん;essay, composition
手紙;てがみ;letter
メール;メール;e-mail
ギター;ギター;guitar
ピアノ;ピアノ;piano
コンサート;コンサート;concert
チケット;チケット;ticket
歌舞伎;かぶき;Kabuki
スキー;スキー;ski
お弁当;おべんとう;boxed lunch
ピザ;ピザ;pizza
""")

unit("beginend", "Beginning, ending and getting", 25,
     "I can say when something starts or ends, and what I got from someone.", ["plainpast", "mailtickets"],
     ["Something begins or ends: Noun が 始まる, 終わる", "Getting from someone: Person に Thing を もらう"], """
始まる;はじまる;(something) begins
終わる;おわる;(something) ends
弾く;ひく;to play (guitar, piano)
もらう;もらう;to get (from someone)
覚える;おぼえる;to memorize
運動する;うんどうする;to exercise
散歩する;さんぽする;to take a walk
人気;にんき;popularity
是非;ぜひ;by all means
ところで;ところで;by the way
そう;そう;so, that way
""")

unit("fruit", "At the supermarket: fruit", 15,
     "I can name common fruit and ask if a shop has something.", ["food"],
     ["～はありますか (do you have ～?)", "袋をお願いします (can I have a bag?)"], """
アボカド;アボカド;avocado
いちご;いちご;strawberry
すいか;すいか;watermelon
みかん;みかん;mandarin orange
りんご;りんご;apple
もも;もも;peach
ぶどう;ぶどう;grape
袋;ふくろ;bag
""")

unit("vegmeat", "At the supermarket: vegetables and meat", 15,
     "I can name common vegetables and meat.", ["fruit"],
     ["Counting small items with ひとつ, ふたつ, みっつ in a shop"], """
にんじん;にんじん;carrot
玉ねぎ;たまねぎ;onion
じゃがいも;じゃがいも;potato
なす;なす;eggplant
きゅうり;きゅうり;cucumber
キャベツ;キャベツ;cabbage
牛肉;ぎゅうにく;beef
豚肉;ぶたにく;pork
鶏肉;とりにく;chicken
""")


unit("classhome", "Class, home and daily habits", 25,
     "I can talk about school and home things and what I do in a day.", ["permission", "te2"],
     ["Joining actions with the て-form (シャワーを浴びて、寝ます)", "Page numbers and order: ～ページ, 次"], """
漢字;かんじ;kanji
教科書;きょうかしょ;textbook
ページ;ページ;page
次;つぎ;next
パソコン;パソコン;personal computer
エアコン;エアコン;air conditioner
シャワー;シャワー;shower
電車;でんしゃ;train
吸う;すう;to smoke, to inhale
死ぬ;しぬ;to die
浴びる;あびる;to take (a shower)
""")

unit("offerhelp", "Offering help and bringing things", 25,
     "I can offer to help, say I will do it later, and ask someone to bring something.", ["permission", "classhome"],
     ["～ましょうか (shall I do it for you?)", "持ってくる and 連れてくる (bring a thing, bring a person)"], """
手伝う;てつだう;to help
返す;かえす;to return (a thing)
消す;けす;to turn off, to erase
つける;つける;to turn on
忘れる;わすれる;to forget, to leave behind
電話する;でんわする;to call
持ってくる;もってくる;to bring (a thing)
連れてくる;つれてくる;to bring (a person)
後で;あとで;later on
すぐ;すぐ;right away
結構です;けっこうです;that would be fine, no thank you
本当ですか;ほんとうですか;really?
""")

unit("directions", "Asking and giving directions", 20,
     "I can ask the way and understand simple directions.", ["places", "te2"],
     ["まっすぐ行って、右に曲がる (go straight, turn right)", "Order with ～目 (二つ目の角, 一つ目の信号)"], """
曲がる;まがる;to turn
渡る;わたる;to cross
道;みち;road, way
角;かど;corner
信号;しんごう;traffic light
北;きた;north
南;みなみ;south
東;ひがし;east
西;にし;west
右側;みぎがわ;right side
左側;ひだりがわ;left side
""")


unit("foodfest", "Sweets, drinks and festivals", 20,
     "I can name some Japanese foods, sweets and festivals.", ["plainpast"],
     ["A や B (A and B, among others)", "The polite prefix お (お菓子, お祭り, お正月)"], """
ケーキ;ケーキ;cake
すし;すし;sushi
天ぷら;てんぷら;tempura
お菓子;おかし;snack, sweets
ビール;ビール;beer
お祭り;おまつり;festival
お正月;おしょうがつ;New Year
おもちゃ;おもちゃ;toy
""")

unit("transport", "Getting there", 25,
     "I can say how I travel and ask how to get somewhere.", ["going", "compare"],
     ["Noun + で for means (電車で行く, 日本語で話す)", "どうやって (how, by what means)"], """
新幹線;しんかんせん;bullet train
地下鉄;ちかてつ;subway
船;ふね;ship, boat
飛行機;ひこうき;airplane
歩いて;あるいて;on foot
どうやって;どうやって;how, by what means
ツアー;ツアー;tour
予約;よやく;reservation
動物園;どうぶつえん;zoo
美容院;びよういん;beauty parlor
""")

unit("duration", "How long it takes", 25,
     "I can say how many weeks, months or years, and ask how long something takes.", ["compare", "time"],
     ["Duration with ～週間, ～か月 and ～年", "どのぐらいかかりますか (how long does it take?)"], """
週間;しゅうかん;for ... weeks
か月;かげつ;for ... months
年;ねん;... years
どのぐらい;どのぐらい;how much, how long
かかる;かかる;to take (time or money)
後;ご;in ... time, after
このごろ;このごろ;these days
""")

unit("becoming2", "Becoming and deciding", 25,
     "I can say what something becomes, where I decided to stay, and what I want to do at home.", ["compare", "iadj2"],
     ["Adjective + なる (become)", "どこかに and どこにも (somewhere, nowhere)"], """
なる;なる;to become
決める;きめる;to decide
泊まる;とまる;to stay (at a hotel)
ごろごろする;ごろごろする;to chill out at home
旅行する;りょこうする;to travel
練習する;れんしゅうする;to practice
涼しい;すずしい;cool (weather)
早く;はやく;early, fast
どっち;どっち;which one
""")

unit("introducing", "Meeting and introducing people", 25,
     "I can introduce someone and talk about where people come from.", ["plain2"],
     ["Introducing someone: こちらは ～さんです", "出身 (coming from a place)"], """
こちら;こちら;this person (polite)
紹介する;しょうかいする;to introduce
出身;しゅっしん;coming from (a place)
ルームメイト;ルームメイト;roommate
ホストファミリー;ホストファミリー;host family
久しぶり;ひさしぶり;it has been a long time
まあまあ;まあまあ;okay, so-so
お金持ち;おかねもち;rich person
有名人;ゆうめいじん;celebrity
年上;としうえ;someone older
社長;しゃちょう;company president
歌手;かしゅ;singer
""")

unit("student", "Student life", 25,
     "I can say what I did at school and what I have done before.", ["plainpast", "experiences"],
     ["～ことがある (have done before)", "～たり～たりする (do things such as)"], """
今学期;こんがっき;this semester
来学期;らいがっき;next semester
点;てん;points
留学する;りゅうがくする;to study abroad
遅刻する;ちこくする;to be late (for an appointment)
サボる;サボる;to cut (classes)
やめる;やめる;to quit
喧嘩する;けんかする;to have a fight
ダイエットする;ダイエットする;to go on a diet
おなかがすく;おなかがすく;to become hungry
飼う;かう;to own (a pet)
""")

unit("outdoors", "Outdoor trips and museums", 25,
     "I can talk about a trip with fishing, camping, climbing or a museum, and what comes after.", ["want", "experiences"],
     ["Noun だけ (only) and もっと (more)", "Noun のあと (after an event)"], """
釣り;つり;fishing
湖;みずうみ;lake
キャンプ;キャンプ;camp
ドライブ;ドライブ;drive
美術館;びじゅつかん;art museum
登る;のぼる;to climb
撮る;とる;to take (a picture)
後;あと;after (an event)
そして;そして;and then
だけ;だけ;just, only
""")

unit("jobs", "Jobs", 20,
     "I can say what job someone has.", ["university", "plain2"],
     ["Occupations with です (私は教師です)", "～になりたいです (I want to become)"], """
作家;さっか;writer
ジャーナリスト;ジャーナリスト;journalist
警察官;けいさつかん;police officer
消防士;しょうぼうし;firefighter
教師;きょうし;schoolteacher
研究者;けんきゅうしゃ;researcher
宇宙飛行士;うちゅうひこうし;astronaut
""")

unit("jobs2", "More jobs and dreams", 20,
     "I can name more jobs and say what I want to be in the future.", ["jobs"],
     ["将来は ～になりたいです (in the future I want to be)"], """
スポーツ選手;スポーツせんしゅ;athlete
大統領;だいとうりょう;president of a country
俳優;はいゆう;actor, actress
看護師;かんごし;nurse
シェフ;シェフ;chef
漫画家;まんがか;cartoonist
""")


# ---- Step 12: Lesson 12 and the Reading and Writing glossaries (appended; ids g1-71 and up) ----

unit("illness1", "Feeling ill", 25,
     "I can say which part of me hurts and what symptoms I have.", ["reasons", "advice"],
     ["Symptoms with が (おなかが痛いです, 熱があります, せきが出ます)", "元気がない (not looking well)"], """
おなか;おなか;stomach
のど;のど;throat
歯;は;tooth
インフルエンザ;インフルエンザ;influenza
アレルギー;アレルギー;allergy
二日酔い;ふつかよい;hangover
ホームシック;ホームシック;homesickness
風邪をひく;かぜをひく;to catch a cold
熱がある;ねつがある;to have a fever
のどが渇く;のどがかわく;to become thirsty
せきが出る;せきがでる;to cough
元気がない;げんきがない;do not look well
""")

unit("illness2", "Aches and allergies", 25,
     "I can name more symptoms and tell a doctor what is wrong.", ["illness1"],
     ["Noun です or があります for symptoms (下痢です, 虫歯があります)"], """
下痢;げり;diarrhea
便秘;べんぴ;constipation
生理;せいり;period (menstruation)
花粉症;かふんしょう;hay fever
虫歯;むしば;bad tooth, cavity
くしゃみ;くしゃみ;sneeze
鼻水;はなみず;runny nose
背中;せなか;back (body)
かゆい;かゆい;itchy
発疹;はっしん;rash
めまい;めまい;dizziness
吐く;はく;to throw up
""")

unit("injuries", "Injuries and the clinic desk", 20,
     "I can say I hurt myself and check in at a clinic for the first time.", ["illness1"],
     ["Injuries with をしました (やけどをしました, 足を骨折しました)", "初めてなんですが (explaining it is my first visit)"], """
気分が悪い;きぶんがわるい;do not feel well
やけど;やけど;burn
骨折;こっせつ;broken bone
けが;けが;injury
お大事に;おだいじに;get well soon
初めて;はじめて;for the first time
保険証;ほけんしょう;health insurance card
""")

unit("doctors", "Kinds of doctors", 20,
     "I can say which kind of doctor I need to see.", ["illness2", "injuries"],
     ["Place に行きます for the clinic (眼科に行きます)"], """
内科;ないか;physician
皮膚科;ひふか;dermatologist
外科;げか;surgeon
産婦人科;さんふじんか;obstetrician and gynecologist
整形外科;せいけいげか;orthopedic surgeon
眼科;がんか;ophthalmologist
歯科;しか;dentist
耳鼻科;じびか;ENT doctor
""")

unit("treatment", "Treatment and medicine", 20,
     "I can understand what a doctor says about treatment and how to take medicine.", ["doctors", "te2"],
     ["Doctor's orders with ～てください (薬を飲んでください)", "食後に (after meals)"], """
抗生物質;こうせいぶっしつ;antibiotic
レントゲン;レントゲン;X-ray
手術;しゅじゅつ;operation
注射;ちゅうしゃ;injection
体温計;たいおんけい;thermometer
点滴;てんてき;intravenous drip
痛み止め;いたみどめ;painkiller
食後;しょくご;after a meal
""")

unit("mustbecause", "Matches, practice and must", 25,
     "I can say I must do something, and give a reason with ので.", ["advice", "illness1"],
     ["～ので (reason, softer than から)", "～なければいけません and ～なきゃいけません (must)"], """
試合;しあい;match, game
練習;れんしゅう;practice
運動;うんどう;exercise
二三日;にさんにち;two or three days
もうすぐ;もうすぐ;very soon
できるだけ;できるだけ;as much as possible
""")

unit("maybe", "Money, gifts and guesses", 25,
     "I can ask a polite tentative question and make a guess.", ["advice", "mustbecause"],
     ["～でしょうか and ～でしょう (tentative question, guess)"], """
プレゼント;プレゼント;present
ジュース;ジュース;juice
代;だい;charge, fee
用事;ようじ;business to take care of
お手洗い;おてあらい;restroom
彼氏;かれし;boyfriend
払う;はらう;to pay
なくす;なくす;to lose
別れる;わかれる;to break up
それに;それに;moreover
""")

unit("interest", "School, politics and nerves", 25,
     "I can say what I am interested in and how I feel about a place or a test.", ["advice", "likes"],
     ["～に興味があります (interested in)", "Adjective stem + すぎる (狭すぎる, 甘すぎる)"], """
政治;せいじ;politics
成績;せいせき;grade (on a test)
意味;いみ;meaning
興味がある;きょうみがある;to be interested
狭い;せまい;narrow, not spacious
広い;ひろい;wide, spacious
甘い;あまい;sweet
素敵;すてき;nice
緊張する;きんちょうする;to get nervous
心配する;しんぱいする;to worry
""")

unit("rwdiary", "Diary and thank-you notes", 25,
     "I can read a short diary and write a thank-you note to a host family.", ["plainpast", "party"],
     ["Diary style in plain form (今日は雨がふっていた)", "Thank-you notes with ありがとうございました and 楽しみにしています"], """
日記;にっき;diary
午前中;ごぜんちゅう;in the morning
ごちそう;ごちそう;excellent food
いろいろ;いろいろ;various
話をする;はなしをする;to have a talk
着物;きもの;kimono
寮;りょう;dormitory
案内する;あんないする;to show someone around
楽しみにする;たのしみにする;to look forward to
体に気をつける;からだにきをつける;to take care of oneself
""")

unit("rwreport", "Writing a report", 20,
     "I can read and write a short report that says first, next and lastly.", ["plainpast"],
     ["Ordering a report with まず, 次に and 最後に", "Reporting answers with ～と言っていました"], """
アンケート;アンケート;questionnaire
ストレス;ストレス;stress
残業;ざんぎょう;overtime work
まず;まず;first of all
次に;つぎに;secondly, next
最後に;さいごに;lastly
答える;こたえる;to answer
話;はなし;story, talk
""")

unit("rwtravel", "Places, restaurants and routes", 20,
     "I can read a short text about a restaurant, a trip or a route.", ["directions"],
     ["Written routes with まっすぐ, 右へ and 左へ", "みんな and 皆さん (everyone)"], """
イタリア;イタリア;Italy
メキシコ;メキシコ;Mexico
ワイン;ワイン;wine
お城;おしろ;castle
おまんじゅう;おまんじゅう;sweet bun
まっすぐ;まっすぐ;straight
皆さん;みなさん;everyone (polite)
皆;みんな;all, everyone
塾;じゅく;cram school
""")

unit("rwfolktale", "Folktale: Kasajizo", 25,
     "I can read a simple folktale and say what the characters did.", ["plainpast", "experiences"],
     ["Story style (むかしむかし、～がいました)", "～つもりでした (planned to)"], """
昔々;むかしむかし;once upon a time
おもち;おもち;rice cake
悲しい;かなしい;sad
お地蔵さん;おじぞうさん;guardian deity of children
かぶせる;かぶせる;to put (a hat) on someone
自分;じぶん;oneself
いいこと;いいこと;good deed
びっくりする;びっくりする;to be surprised
幸せ;しあわせ;happy
山道;やまみち;mountain road
""")

unit("rwads", "Looking for friends and members", 25,
     "I can read a short ad for friends or members and write a reply.", ["introducing", "outdoors"],
     ["Invitations in ads with ～ませんか", "～たいと思っています (I am thinking I want to)"], """
募集;ぼしゅう;looking for
ボランティア;ボランティア;volunteer
近所;きんじょ;neighborhood
アウトドア;アウトドア;outdoor activities
ホラー;ホラー;horror
平日;へいじつ;weekday
バンド;バンド;band
明るい;あかるい;cheerful, bright
文化;ぶんか;culture
返事;へんじ;reply
これから;これから;from now on
""")

unit("rwtanabata1", "Tanabata: the story", 25,
     "I can read the first half of the Tanabata story and say who the characters are.", ["rwfolktale"],
     ["～なければいけません in a story (別れなければいけません)", "～てもいい in a story"], """
神様;かみさま;God
娘;むすめ;daughter
まじめ;まじめ;serious, diligent
大人;おとな;adult
結婚;けっこん;marriage
見つける;みつける;to find
向こう;むこう;the other side
牛;うし;cow
畑;はたけ;farm, field
怒る;おこる;to get angry
連れて帰る;つれてかえる;to bring (a person) back
泣く;なく;to cry
""")

unit("rwtanabata2", "Tanabata: wishes", 20,
     "I can read the end of the Tanabata story and write my own wishes.", ["rwtanabata1"],
     ["～までに (by a time)", "～など (and so on)"], """
かわいそう;かわいそう;pitiful
願い;ねがい;wish
かなう;かなう;to be realized
私たち;わたしたち;we
人々;ひとびと;people
天の川;あまのがわ;the Milky Way
短冊;たんざく;strip of fancy paper
織る;おる;to weave
までに;までに;by (a time)
""")


# ---- Greetings and Numbers pages (p.30 to 35), appended as g1-86 and g1-87 ----

unit("homegreet", "Home and meal greetings", 15,
     "I can say the set phrases for leaving home, coming home and eating.", ["greetings"],
     ["Set phrases for leaving, coming home and meals", "Casual and polite pairs (おはよう and おはようございます)"], """
おはよう;おはよう;good morning (casual)
ありがとう;ありがとう;thank you (casual)
いってきます;いってきます;I am leaving, I will be back
いってらっしゃい;いってらっしゃい;please go and come back
ただいま;ただいま;I am home
おかえりなさい;おかえりなさい;welcome home
いただきます;いただきます;thank you for the meal (before eating)
ごちそうさまでした;ごちそうさまでした;thank you for the meal (after eating)
じゃあ、また;じゃあ、また;see you later
失礼します;しつれいします;goodbye (formal), excuse me
""")

unit("num100", "Numbers 11 to 100", 20,
     "I can read and say numbers from 11 to 99 and 100.", ["num10"],
     ["Tens and ones (二十三 is にじゅうさん)", "Readings in tens: よん, なな and きゅう"], """
十一;じゅういち;eleven
十二;じゅうに;twelve
十四;じゅうよん;fourteen
十七;じゅうなな;seventeen
十九;じゅうきゅう;nineteen
二十;にじゅう;twenty
三十;さんじゅう;thirty
四十;よんじゅう;forty
六十;ろくじゅう;sixty
七十;ななじゅう;seventy
八十;はちじゅう;eighty
九十;きゅうじゅう;ninety
""")


# Book order: Greetings and Numbers, then Lessons 1 to 12. Reading and Writing units sit after their lesson.
# Ids are numbered from this order (g1-01 upward), so changing it renumbers every unit.
ORDER = """
greetings homegreet num10 num100
people countries university time age
this thisnoun places whose numbig negatives
verbs1 food going timeofday frequency invitations
counting existence positions past weekdays months monthdays fromuntil
iadj1 iadj2 naadj likes adjpast
te1 te2 permission directions classhome offerhelp rwtravel
family relatives teiru describing looks
plainpresent chores plain2 weather replies fruit vegmeat
plainpast experiences party colors mailtickets beginend rwreport rwdiary
compare foodfest transport duration becoming2 rwfolktale
want plans introducing student outdoors jobs jobs2 rwads
reasons advice illness1 illness2 injuries doctors treatment mustbecause maybe interest rwtanabata1 rwtanabata2
""".split()


def main():
    global U
    by = {u[0]: u for u in U}
    if sorted(ORDER) != sorted(by) or len(set(ORDER)) != len(ORDER):
        sys.exit("ORDER mismatch: missing %s extra %s" % (sorted(set(by) - set(ORDER)), sorted(set(ORDER) - set(by))))
    U = [by[x] for x in ORDER]
    slugs = [u[0] for u in U]
    ids = {s: "g1-%02d" % (i + 1) for i, s in enumerate(slugs)}
    if len(set(slugs)) != len(slugs):
        sys.exit("duplicate slug")
    seen = {}
    lines = [
        "# Fluvour curriculum: Genki I path (learning path step 5). Built by tools/curriculum/build_genki1.py. Do not edit by hand.",
        "# Own content only: no book text, no page anchors. Words are written form, reading, short English gloss.",
        "# @path columns: id, title, languageCode, level",
        "# unit columns: id, order, title, level, minutes, canDo, prerequisite ids (comma), grammar points (|), words (| between words, ; inside a word: word;reading;gloss)",
        "\t".join(["@path", PATH_ID, PATH_TITLE, LANG, LEVEL]),
    ]
    for i, (slug, title, minutes, can_do, prereqs, grammar, words) in enumerate(U):
        for p in prereqs:
            if p not in ids or slugs.index(p) >= i:
                sys.exit("bad prerequisite %s in %s" % (p, slug))
        ws = [w.strip() for w in words.strip().splitlines() if w.strip()]
        for w in ws:
            parts = w.split(";")
            if len(parts) != 3 or not all(x.strip() for x in parts):
                sys.exit("bad word line in %s: %r" % (slug, w))
            if "|" in w or "\t" in w:
                sys.exit("bad character in %s: %r" % (slug, w))
            key = (parts[0], parts[1])
            if key in seen:
                sys.exit("duplicate word %r in %s and %s" % (key, seen[key], slug))
            seen[key] = slug
        if not 6 <= len(ws) <= 12:
            sys.exit("%s has %d words (want 6 to 12)" % (slug, len(ws)))
        if not 1 <= len(grammar) <= 2:
            sys.exit("%s has %d grammar points (want 1 or 2)" % (slug, len(grammar)))
        for field in [title, can_do] + grammar:
            if ";" in field and field is not title and False:
                pass
            if "\t" in field or "|" in field:
                sys.exit("bad character in %s text" % slug)
        lines.append("\t".join([
            ids[slug], str(i + 1), title, LEVEL, str(minutes), can_do,
            ",".join(ids[p] for p in prereqs), "|".join(grammar), "|".join(ws),
        ]))
    os.makedirs(os.path.dirname(OUT), exist_ok=True)
    with open(OUT, "w", encoding="utf-8", newline="\n") as f:
        f.write("\n".join(lines) + "\n")
    print("wrote %s: %d units, %d words" % (OUT, len(U), len(seen)))


if __name__ == "__main__":
    main()
