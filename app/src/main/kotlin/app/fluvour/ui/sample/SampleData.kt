package app.fluvour.ui.sample

/**
 * PLACEHOLDER CONTENT for screens that exist in the finalized UI but whose features are not built yet
 * (quiz, speaking, watch, journal, handwriting, progress). Copied or adapted from the HTML prototype.
 * When a feature gets real data, replace the matching list here with a repository call and delete the sample.
 * Nothing in this file is saved or synced.
 */
data class SampleWord(val word: String, val reading: String, val meaning: String, val note: String)
data class SampleLine(val text: String, val translation: String)
data class SampleTopic(val title: String, val english: String, val level: String, val seconds: Int,
                       val cues: List<Pair<String, String>>, val words: List<String>, val model: Pair<String, String>)
data class SampleCall(val title: String, val level: String)
data class SampleFix(val wrong: String, val right: String, val why: String)
data class SampleQuestion(val kind: Kind, val prompt: String, val options: List<String>, val correct: Int, val hint: String = "", val explanation: String = "",
                          val word: String = "", val meaning: String = "") {
    enum class Kind { Context, Fill, Listen }
}

object Sample {
    val words: Map<String, List<SampleWord>> = mapOf(
        "th" to listOf(
            SampleWord("ข้าวมันไก่", "khâao man gài", "Hainanese chicken rice", "Here it is the dish itself, the breakfast the writer likes."),
            SampleWord("ตลาด", "dtà-làat", "market", "Where the shop is. Often paired with ใกล้ (near)."),
            SampleWord("ร้าน", "ráan", "shop, stall", "The food stall the writer visits."),
            SampleWord("ตื่น", "dtùuen", "to wake up", "Used before เช้า to say waking up early."),
            SampleWord("ใกล้", "glâi", "near, close", "Follows อยู่: อยู่ใกล้ means is located near.")),
        "ja" to listOf(
            SampleWord("親子丼", "おやこどん", "chicken and egg rice bowl", "Here it is the dish itself, the breakfast the writer likes."),
            SampleWord("市場", "いちば", "market", "Where the shop is. Often followed by の: 市場の近く."),
            SampleWord("店", "みせ", "shop, stall", "The food stall the writer visits."),
            SampleWord("起きる", "おきる", "to wake up", "Used with 早く to say waking up early."),
            SampleWord("近く", "ちかく", "near, nearby", "Follows の and takes に: 近くにある means is located nearby.")),
        "en" to listOf(
            SampleWord("breakfast", "BREK-fuhst", "the first meal of the day", "The meal the writer likes at the market."),
            SampleWord("market", "MAR-kit", "a place where people buy and sell food", "Where the shop is."),
            SampleWord("stall", "stawl", "a small open shop", "The food stall the writer visits."),
            SampleWord("wake up", "wayk uhp", "to stop sleeping", "Used with early to say waking up early."),
            SampleWord("nearby", "NEER-by", "close to where you are", "Often follows a place: a stall nearby."))
    )

    val lines: Map<String, List<SampleLine>> = mapOf(
        "th" to listOf(SampleLine("เช้านี้ฉันตื่นเช้ามาก", "I woke up very early this morning."), SampleLine("ร้านนี้อยู่ใกล้ตลาด", "This shop is near the market."),
            SampleLine("ฉันชอบกินข้าวมันไก่", "I like eating chicken rice."), SampleLine("ตอนเช้าอากาศดี", "The air is nice in the morning.")),
        "ja" to listOf(SampleLine("今朝はとても早く起きました", "I woke up very early this morning."), SampleLine("この店は市場の近くにあります", "This shop is near the market."),
            SampleLine("私は親子丼が好きです", "I like chicken and egg rice."), SampleLine("朝は空気がきれいです", "The air is clean in the morning.")),
        "en" to listOf(SampleLine("I woke up very early this morning.", ""), SampleLine("This shop is near the market.", ""),
            SampleLine("I like chicken rice for breakfast.", ""), SampleLine("The air is fresh in the morning.", ""))
    )

    val calls = listOf(SampleCall("Ordering food", "Easy"), SampleCall("Asking directions", "Easy"),
        SampleCall("Meeting someone new", "Medium"), SampleCall("At the market", "Medium"))

    val topics: Map<String, List<SampleTopic>> = mapOf(
        "th" to listOf(
            SampleTopic("อาหารที่ฉันชอบ", "My favorite food", "Easy", 30, listOf("ชอบกินอะไร" to "What do you like to eat?", "กินที่ไหน" to "Where do you eat it?", "ทำไมถึงชอบ" to "Why do you like it?"),
                listOf("ข้าวมันไก่", "อร่อย", "เผ็ด"), "ฉันชอบกินข้าวมันไก่ เพราะอร่อยมาก" to "Link your reason with เพราะ (because)."),
            SampleTopic("วันหยุดของฉัน", "My weekend", "Easy", 30, listOf("ไปที่ไหน" to "Where do you go?", "ไปกับใคร" to "Who do you go with?", "ทำอะไร" to "What do you do?"),
                listOf("วันหยุด", "เพื่อน", "เดินเล่น"), "วันหยุดฉันไปเดินเล่นกับเพื่อนที่สวน" to "Add who you are with using กับ (with)."),
            SampleTopic("บ้านของฉัน", "My home", "Medium", 45, listOf("อยู่ที่ไหน" to "Where is it?", "มีอะไรบ้าง" to "What is in it?", "ชอบอะไรที่สุด" to "What do you like most?"),
                listOf("ห้องนอน", "ใกล้", "สบาย"), "บ้านฉันอยู่ใกล้ตลาด ฉันชอบห้องนอนที่สุด" to "Use ที่สุด (the most) to name your favorite."),
            SampleTopic("งานในฝัน", "My dream job", "Medium", 45, listOf("อยากเป็นอะไร" to "What do you want to be?", "ทำไม" to "Why?", "ต้องทำอย่างไร" to "How will you get there?"),
                listOf("อยากเป็น", "เพราะ", "ฝึก"), "ฉันอยากเป็นครู เพราะชอบสอนคน" to "Say อยากเป็น (want to be) plus a job.")),
        "ja" to listOf(
            SampleTopic("好きな食べ物", "My favorite food", "Easy", 30, listOf("何が好きですか" to "What do you like?", "どこで食べますか" to "Where do you eat it?", "なぜ好きですか" to "Why do you like it?"),
                listOf("親子丼", "おいしい", "辛い"), "私は親子丼が好きです。とてもおいしいからです。" to "Give a reason with から."),
            SampleTopic("週末", "My weekend", "Easy", 30, listOf("どこへ行きますか" to "Where do you go?", "誰と行きますか" to "Who with?", "何をしますか" to "What do you do?"),
                listOf("週末", "友達", "散歩"), "週末は友達と公園を散歩します。" to "Add who with と."),
            SampleTopic("私の家", "My home", "Medium", 45, listOf("どこにありますか" to "Where is it?", "何がありますか" to "What is in it?", "何が一番好きですか" to "What do you like most?"),
                listOf("寝室", "近く", "静か"), "家は市場の近くです。寝室が一番好きです。" to "Name a favorite with 一番."),
            SampleTopic("夢の仕事", "My dream job", "Medium", 45, listOf("何になりたいですか" to "What do you want to be?", "なぜですか" to "Why?", "どうやってなりますか" to "How will you get there?"),
                listOf("なりたい", "から", "練習"), "先生になりたいです。教えるのが好きだからです。" to "Say なりたい plus a job.")),
        "en" to listOf(
            SampleTopic("My favorite food", "Talk about food you like", "Easy", 30, listOf("What do you like to eat?" to "", "Where do you eat it?" to "", "Why do you like it?" to ""),
                listOf("breakfast", "tasty", "spicy"), "I like chicken rice because it is very tasty." to "Link your reason with because."),
            SampleTopic("My weekend", "Talk about your days off", "Easy", 30, listOf("Where do you go?" to "", "Who do you go with?" to "", "What do you do?" to ""),
                listOf("weekend", "friends", "walk"), "On weekends I walk in the park with friends." to "Add who with using with."),
            SampleTopic("My home", "Describe where you live", "Medium", 45, listOf("Where is it?" to "", "What is in it?" to "", "What do you like most?" to ""),
                listOf("bedroom", "near", "comfortable"), "My home is near the market. I like the bedroom most." to "Name a favorite with most."),
            SampleTopic("My dream job", "Talk about work you want", "Medium", 45, listOf("What do you want to be?" to "", "Why?" to "", "How will you get there?" to ""),
                listOf("want to be", "because", "practice"), "I want to be a teacher because I like teaching people." to "Say want to be plus a job."))
    )

    /** What the user said on a call, the improved version, and the tip. Mirrors the call result screen. */
    val callResult: Map<String, Triple<String, String, String>> = mapOf(
        "th" to Triple("ขอ ข้าวมันไก่ หนึ่ง", "ขอข้าวมันไก่หนึ่งจานค่ะ", "Add a counter word and a polite ending."),
        "ja" to Triple("親子丼 一つ", "親子丼を一つお願いします", "Add the particle を and a polite ending."),
        "en" to Triple("Chicken rice one", "Could I have one chicken rice, please?", "Use a polite request and add please."))

    val journalText: Map<String, String> = mapOf(
        "th" to "วันนี้ฉันไปตลาด ฉันซื้อข้าวมันไก่จานสอง แล้วฉันกินที่ร้านอยู่ใกล้ที่ตลาด",
        "ja" to "今朝、私は市場を行きました。それから親子丼が食べました。",
        "en" to "This morning I go to the market. I buyed two apples and eated breakfast nearby.")
    val journalFixes: Map<String, List<SampleFix>> = mapOf(
        "th" to listOf(SampleFix("จานสอง", "สองจาน", "Put the number before the counter word."), SampleFix("ใกล้ที่ตลาด", "ใกล้ตลาด", "Drop ที่ after ใกล้.")),
        "ja" to listOf(SampleFix("市場を行きました", "市場に行きました", "Use に for where you go."), SampleFix("親子丼が食べました", "親子丼を食べました", "Use を for what you eat.")),
        "en" to listOf(SampleFix("I go to", "I went to", "Use the past tense for finished actions."), SampleFix("buyed", "bought", "Buy is irregular: bought."), SampleFix("eated", "ate", "Eat is irregular: ate.")))

    /** Stroke-order glyph: SVG-style path data on a 100 x 100 grid (the same data the prototype uses). */
    val glyph: Map<String, Pair<String, List<String>>> = mapOf(
        "th" to ("ก" to listOf("M44 40C30 40 28 58 40 60C50 62 58 54 58 40", "M58 26V82")),
        "ja" to ("日" to listOf("M30 18V82", "M30 18H70V82", "M30 50H70", "M30 82H70")),
        "en" to ("b" to listOf("M36 16V84", "M36 58C48 44 68 50 68 66C68 82 48 86 36 74")))

    val skills: Map<String, List<Int>> = mapOf("th" to listOf(62, 48, 35, 41), "ja" to listOf(28, 22, 15, 12), "en" to listOf(74, 66, 52, 58))
    val minutesBySource: Map<String, List<Int>> = mapOf("th" to listOf(24, 11, 7, 3), "ja" to listOf(14, 6, 4, 2), "en" to listOf(19, 9, 5, 4))
    val weekNumbers: Map<String, List<Int>> = mapOf("th" to listOf(112, 18, 74), "ja" to listOf(64, 12, 41), "en" to listOf(96, 15, 58))

    val quiz: Map<String, List<SampleQuestion>> = mapOf(
        "th" to listOf(
            SampleQuestion(SampleQuestion.Kind.Context, "เช้านี้ฉัน<ตื่น>เช้ามาก", listOf("to wake up", "to eat", "to sleep", "to walk"), 0, explanation = "ตื่น means to wake up. It sits before เช้า here.", word = "ตื่น", meaning = "to wake up"),
            SampleQuestion(SampleQuestion.Kind.Fill, "ร้านนี้อยู่___ตลาด", listOf("ใกล้", "ตื่น", "ข้าว"), 0, hint = "near, close", explanation = "ใกล้ follows อยู่ to say located near.", word = "ใกล้", meaning = "near, close"),
            SampleQuestion(SampleQuestion.Kind.Listen, "", listOf("ตลาด", "ร้าน", "ใกล้", "ตื่น"), 0, explanation = "You heard ตลาด, market.", word = "ตลาด", meaning = "market")),
        "ja" to listOf(
            SampleQuestion(SampleQuestion.Kind.Context, "今朝はとても早く<起き>ました", listOf("to wake up", "to eat", "to sleep", "to walk"), 0, explanation = "起きる means to wake up.", word = "起きる", meaning = "to wake up"),
            SampleQuestion(SampleQuestion.Kind.Fill, "この店は市場の___にあります", listOf("近く", "起き", "親子"), 0, hint = "nearby", explanation = "近く takes に: 近くにある.", word = "近く", meaning = "near, nearby"),
            SampleQuestion(SampleQuestion.Kind.Listen, "", listOf("市場", "店", "近く", "起きる"), 0, explanation = "You heard 市場, market.", word = "市場", meaning = "market")),
        "en" to listOf(
            SampleQuestion(SampleQuestion.Kind.Context, "I <woke up> very early this morning.", listOf("stopped sleeping", "went to bed", "ate food", "went outside"), 0, explanation = "Wake up means to stop sleeping.", word = "wake up", meaning = "to stop sleeping"),
            SampleQuestion(SampleQuestion.Kind.Fill, "This shop is ___ the market.", listOf("near", "wake", "stall"), 0, hint = "close to", explanation = "Near is close to a place.", word = "near", meaning = "close to"),
            SampleQuestion(SampleQuestion.Kind.Listen, "", listOf("market", "stall", "nearby", "breakfast"), 0, explanation = "You heard market.", word = "market", meaning = "a place to buy food"))
    )

    val quizSets = listOf(Triple("Mixed quiz", "8 questions · about 3 min", "mix"), Triple("In context", "5 words inside sentences", "ctx"),
        Triple("Fill in the blank", "5 sentences", "fil"), Triple("Listening", "Words and sound pairs", "lis"))

    val articles = listOf(Triple("A morning at the market", "4 min", 82), Triple("The rice farmer's year", "7 min", 64), Triple("Night train north", "3 min", 77))
}

/** Pick the sample for the language being learned, falling back to English. */
fun <T> Map<String, T>.forLang(code: String): T = this[code] ?: this.getValue("en")
