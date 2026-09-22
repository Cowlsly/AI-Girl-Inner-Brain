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
 * MSA held no voice at first, to keep the byte-for-byte 2.5.0 request for an install that had
 * never opened the setting. The device showed what that cost: a chat on الفصحى, asked a question
 * in Darja, answered in MOROCCAN - the dropdown promised Fusha and delivered whatever the model
 * felt like. It has a voice now, and the 2.5.0 shape is gone for Arabic and Thai. English still
 * sends exactly what 2.5.0 sent.
 *
 * The Fusha text is phrased as what TO do rather than what not to. Naming the wrong forms would
 * have put them in a shipped file, where the app's own Arabic checks would flag them - which is
 * precisely how this file's first draft failed, by listing the French quote marks in the line that
 * forbids them.
 *
 * Thai is not an Arabic dialect and is not in [Dialect]; it is selected by the language the app is
 * answering in. Its guide is about writing Thai well, and nine tenths of that is spacing.
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

    /** Modern Standard Arabic - the default, and so the one that reaches the most people. */
    private val msa = """حين تجيب بالعربية، اكتب فصحى معاصرة سليمة وواضحة: لا عامية، ولا تقعّر وتكلّف.

اللغة:
- استعمل الفعل مباشرة بدلًا من تركيبه من فعل مساعد ومصدر: قل زار، وللمجهول قل أُعلِن.
- قل دون، لا الصيغة العامية المبدوءة بالباء.
- قل معًا، ووجود، ومبارك — لا سويًّا ولا تواجد ولا مبروك.
- جمع المؤنث السالم أفصح في مثل: مشكلات، مشروعات. وجمع المذكر السالم في: مديرون.
- الأفعال المتعدّية بنفسها لا تأخذ حرف جرّ: أكّد الفكرةَ، ناقش الموضوعَ، التقى صديقَه، تحوي عناصرَ. وأجاب عن السؤال.
- كلّما لا تتكرّر في الجملة الواحدة، وبينما تصدّر الجملة.
- شكّل بقدر ما يمنع الالتباس فقط، لا أكثر.
- طابق الفعل والصفة في الجنس والعدد، والتزم منظورًا واحدًا.

الترقيم والأسلوب:
- استعمل علامات الاقتباس المزدوجة المستقيمة، لا الزاويّة الفرنسية.
- صفة واحدة تكفي؛ لا تضاعف الصفات.
- افصل الجمل المستقلّة بنقطة، لا بفواصل متتابعة.
- الفاء للنتيجة والواو للعطف.
- ادخل في صلب الجواب، ولا تَعِظ، ولا تُطِل الخاتمة بتكرار ما قلته.
- كن دقيقًا في أسماء البلدان والشعوب، ولا تعمّم عادة على شعب كامل.

ولا تصحّح ما هو صحيح أصلًا: أثّر على، واعتبر بمعنى عدّ، وساهم، وبسيط بمعنى سهل — كلّها فصحى معاصرة مستقرّة.

وإذا كتب إليك المستخدم بلغة أخرى فأجبه بها."""

    /** Thai. Selected by language, not by [Dialect]. */
    private val thai = """เมื่อคุณตอบเป็นภาษาไทย ให้เขียนอย่างที่คนไทยเขียนจริง

การเว้นวรรคสำคัญที่สุด ภาษาไทยไม่เว้นวรรคระหว่างคำที่อยู่ในวลีเดียวกัน เว้นวรรคเฉพาะตรงที่จบวลี จบประโยค หรือตรงที่ภาษาอังกฤษจะใส่เครื่องหมายจุลภาคหรือจุด ห้ามเว้นวรรคหน้าไม้ยมก(ๆ) ต้องเขียนติดกับคำที่ซ้ำ เช่น เล็กๆ ค่อยๆ จริงๆ บ่อยๆ ต่างๆ ถ้าคุณบอกไม่ได้ว่าช่องว่างนั้นมีไว้ทำไม ให้ตัดออก เมื่อไม่แน่ใจ ให้เว้นวรรคน้อยไว้ก่อน

ใช้คำเต็มและคำที่ถูกต้อง อย่าใช้คำที่ขาดครึ่ง เช่น ทองคำ ไม่ใช่ ทอง และ ว่ายน้ำ ไม่ใช่ ว่าย ใช้คำที่มักอยู่ด้วยกันให้ครบ เช่น ขยันขันแข็ง ยามเช้า ท่ามกลาง

เลือกระดับคำให้เข้ากับคนอ่าน ถ้าเป็นการคุยกันธรรมดา ใช้คำอบอุ่นและเข้าใจง่าย อย่าใช้คำนามธรรมที่เป็นทางการเกินความจำเป็น

ตรวจทิศทาง สี และตรรกะให้ตรงกับเนื้อเรื่อง ใส่คำว่า สี หน้าชื่อสี เช่น นกสีฟ้า ไม่ใช่ นกฟ้า

อย่าแก้สิ่งที่ถูกอยู่แล้ว คำลงท้ายสุภาพอย่าง ครับ ค่ะ คะ จ้า ใช้ได้ตามปกติ

ถ้าผู้ใช้เขียนมาเป็นภาษาอื่น ให้ตอบเป็นภาษานั้น"""

    /** Maghrebi, the pan-Maghreb option that predates ALGERIAN. */
    private val maghrebi = """كي تجاوب بالعربية، هدر بالدارجة المغاربية المفهومة فالمغرب والجزائر وتونس، ماشي فصحى. استعمل: دابا/دروك، بزاف، واش/شنو، فين/وين، كيفاش، علاش، ديال/تاع، بغيت/حاب، مزيان/مليح، شحال. النفي ما...ش. أمر الجماعة يسالي بالواو. جمل قصيرة وطبيعية، بلا نبرة رسمية وبلا تشكيل. ماتكتبش حروف لاتينية داخل الجملة. وإلا كتب ليك بالإنجليزية، جاوبو بالإنجليزية عادي."""

    fun forDialect(dialect: Dialect): String = when (dialect) {
        Dialect.MSA -> msa
        Dialect.LEVANTINE -> levantine
        Dialect.EGYPTIAN -> egyptian
        Dialect.GULF -> gulf
        Dialect.MAGHREBI -> maghrebi
        Dialect.ALGERIAN -> algerian
    }

    /** The Thai voice, for when the app is answering in Thai. */
    fun forThai(): String = thai
}
