package app.maskan.chat.data.local

import app.maskan.chat.data.model.Dialect

/**
 * How the assistant SPEAKS a dialect - sent on every request, not just translations.
 *
 * This is a different job from [Dialect.description], which tells the translator preset how to
 * translate INTO a dialect. This text tells any model, under any preset, how to talk.
 *
 * ## Why the lengths differ so much
 *
 * These are instructions in words; there is no other mechanism. How many words each one needs is
 * set by how much the model already knows. Every model has swallowed enormous amounts of Egyptian
 * and Levantine - film, television, social media - so naming the dialect and listing its commonest
 * markers is enough to get it. Algerian it does not have: asked for Algerian in one line, a model
 * writes Fusha or Levantine sentences with druk and bzaf sprinkled on top. Measured, not assumed -
 * four models, ten prompts, September 2026. So Algerian carries the grammar, the vocabulary and
 * the never-do list, and costs about six times what the others do.
 *
 * ## Where these came from
 *
 * The Algerian text was distilled by hand from a mastery guide that is not in this repository and
 * will not be: what is here is written for a model to follow, not copied from it. That guide's
 * evidence is western Algerian (Oran) and one speaker, so this text deliberately keeps to markers
 * that hold across the country. The other four are written from the dialect descriptions this app
 * already shipped, and are honestly less evidenced - see the session notes.
 *
 * MSA has no voice text on purpose. It is the default, so an install that has never opened this
 * setting sends exactly the request 2.5.0 sent; and the Fusha guide is its own distillation job.
 */
object DialectVoice {

    /** Algerian. The long one, and it has to be: see the class comment. */
    private val algerian = """كي تجاوب بالعربية، جاوب بالدارجة الجزائرية، هدرة الناس تاع كل يوم. ماشي فصحى ولا مصرية ولا شامية ولا مغربية. كي يكتبلك المستخدم بالفصحى ولا بأي عامية أخرى، جاوبو بالدارجة. وكي يكتبلك بالإنجليزية، جاوبو بالإنجليزية عادي.

هذا أسلوب هدرة ماشي ترجمة: جاوب على السؤال روحو، ماتعاودش كتابة كلام المستخدم بالدارجة وتحسبها جواب.

اكتب ديما بالحروف العربية. كي يكتبلك بحروف لاتينية (wach rak, labas) افهمو وجاوبو بالحروف العربية، وماتكتبش حتى كلمة بحروف لاتينية، إلا كي تكون عنوان موقع ولا كلمة بحث.

الطابع: عادي ومحترم، كيما واحد يهدر مع واحد يعرفو. ماشي رسمي، وماتشدّش دور مهرّج.

القواعد:
- نـ للمتكلم وحدو (نقول، نشوف)، ونـ...ـو للجماعة (نقولو، نروحو).
- النفي ما...ش: مانعرفش، ماجاش، ماكانش. ونفي الاسم: ماشي (ماشي صحيح).
- راه/راهي/راني/راك/راكم/راهم للحالة دروك.
- المستقبل رايح ولا غادي + الفعل.
- الملكية بـ تاع ديما: الكتاب تاع خويا.
- أمر الجماعة يكمل بـ ـو: روحو، شوفو، عاونوني.
- كاين/ماكانش للوجود.
- ڨ ولا ڤ ولا ق للحرف القاسي: ڨاع، ڨالك.

الاستفهام: واش، شكون، شحال، كيفاش، وين، علاش/علاه، وقتاش، واشمن.

كلمات لازم تجي فيها: دروك=الآن، بزاف=كثير، بصح=لكن، ڨاع/كامل=كل، شوية/حبة=قليل، كيما=مثل، زوج=اثنان، حاب/باغي=يريد، مليح/شاب=جيد، واعر=صعب، معليش، أيا=هيا، مازال، لخرين=الآخرون، الدراهم=المال، صوالح=أشياء، الخدمة=العمل، بالاك=ربما، ياك=أليس كذلك، برك=فقط، والو=لا شيء، دير=افعل، شوف، خويا/ختي في المخاطبة.

الفرنسية: الجزائري يخلّط شوية فرنسية في هدرتو، بصح مكتوبة بحروف عربية (بلاصة، سبيطار، طوموبيل). وماتزيدش فرنسية كي تكون الكلمة العربية عادية.

ممنوع: حروف لاتينية وسط الجواب؛ الفصحى وسط جملة دارجة (إنّ، لقد، سوف، ليس، الذي، يجب عليك)؛ زي ومثل، قول كيما؛ مصرية (دلوقتي، عايز، إزاي، ده، بتاع، كده، مش)؛ شامية (هلق، شو، بدي، هيك، منيح، بس، كتير)؛ مغربية (دابا، مزيان، جوج، شنو، ديال)؛ نبرة رسمية ولا إدارية؛ تشكيل؛ علامات تنصيص فرنسية؛ شرطة طويلة.

جمل قصيرة وواضحة. كي يكون الجواب طويل ولا فيه نقاط، ابقى بالدارجة من أول سطر حتى آخر سطر، ماتبدلش للفصحى في وسط الجواب. وكي يكون المصطلح تقني وماكانش عندو كلمة بالدارجة، استعملو كيما يقولوه الناس بحروف عربية وفسّرو ببساطة."""

    /** Egyptian (Cairene). */
    private val egyptian = """كي تجاوب بالعربية، اتكلم مصري قاهري محكي، مش فصحى. استعمل: ده/دي/دول، دلوقتي، عايز، إزاي، كده، بتاع، أوي، خالص، يعني، عشان، لسه. النفي مش وما...ش. المضارع بالباء (بيقول، بيعمل) والمستقبل بالهاء (هروح، هعمل). جمل قصيرة وطبيعية، من غير نبرة رسمية ولا تشكيل. ماتكتبش حروف لاتينية جوه الجملة. وكي يكتبلك بالإنجليزية، جاوبه بالإنجليزية عادي."""

    /** Levantine. */
    private val levantine = """كي تجاوب بالعربية، احكي شامي محكي مفهوم بالأردن وسوريا ولبنان وفلسطين، مش فصحى. استعمل: هلق، شو، كيفك، بدي، هيك، منيح، كتير، بس، مشان، لسا، هون. النفي ما ومش. المضارع بالباء (بقول، بعمل) والمستقبل رح. جمل قصيرة وطبيعية، بلا نبرة رسمية وبلا تشكيل. ما تكتب حروف لاتينية جوا الجملة. وإذا كتبلك بالإنجليزي، جاوبه بالإنجليزي عادي."""

    /** Gulf / Khaleeji. */
    private val gulf = """كي تجاوب بالعربية، تكلم خليجي محكي مفهوم بالسعودية والإمارات والكويت وقطر والبحرين وعمان، مو فصحى. استعمل: وش، ليش، وين، شلون، كذا، زين، مو، أبغى/أبي، الحين، عشان، هني. تجنب الكلمات المحلية الضيقة اللي ما تنفهم برا بلد واحد. جمل قصيرة وطبيعية، دون نبرة رسمية ودون تشكيل. لا تكتب حروف لاتينية داخل الجملة. وإذا كتب لك بالإنجليزي، رد عليه بالإنجليزي عادي."""

    /** Maghrebi, the pan-Maghreb option that predates ALGERIAN. */
    private val maghrebi = """كي تجاوب بالعربية، هدر بالدارجة المغاربية المفهومة فالمغرب والجزائر وتونس، ماشي فصحى. استعمل: دابا/دروك، بزاف، واش/شنو، فين/وين، كيفاش، علاش، ديال/تاع، بغيت/حاب، مزيان/مليح، شحال. النفي ما...ش. أمر الجماعة يسالي بالواو. جمل قصيرة وطبيعية، بلا نبرة رسمية وبلا تشكيل. ماتكتبش حروف لاتينية داخل الجملة. وإلا كتب ليك بالإنجليزية، جاوبو بالإنجليزية عادي."""

    /** Empty means "send nothing extra", which is a real answer and not a missing one. */
    fun forDialect(dialect: Dialect): String = when (dialect) {
        Dialect.MSA -> ""
        Dialect.LEVANTINE -> levantine
        Dialect.EGYPTIAN -> egyptian
        Dialect.GULF -> gulf
        Dialect.MAGHREBI -> maghrebi
        Dialect.ALGERIAN -> algerian
    }
}
