package com.windowdocent.core

/**
 * 말하는 장치 (Pleos TextToSpeech · 안드로이드 TTS · 소리 없는 시험용). 한 번에 한 문장만.
 * done · failed 는 어느 스레드에서 불러도 됨. stop() 뒤에는 그 문장의 done · failed 를 부르지 않아도 됨.
 */
interface Speaker {
    fun speak(text: String, language: String, done: () -> Unit, failed: (String) -> Unit)

    /** 지금 말을 바로 끊음 */
    fun stop()

    fun release() {}
}

/**
 * 해설 한 편을 문장 단위로 말하고, 해설 방식(NarrationGate)에 따라 바로 멈추고 이어 감.
 * - 멈춤(HOLD: 방향지시등 · 큰 조향 · 내비 안내 음성 · 위험 구간): 말하던 중이라도 바로 끊고, 끊긴 문장을 기억
 * - 멈춤이 풀리면: 「아까 하던 이야기 이어서 할게요」 한마디 뒤 끊긴 문장을 처음부터 다시 말하고 이어 감.
 *   같은 문장이 두 번째로 끊기면 그 문장은 또 말하지 않고 다음 문장으로 넘어가고, 넘어간 문장은 못 한 이야기에 담음
 * - 한 문장(ONE_LINE: 분기점 접근): 지금 문장은 끝까지, 새로 시작하는 것은 한 문장까지
 * - 여행 기록(TRIP_RECORD: 도어 열림): 해설을 끝냄 (멈춰 있던 해설의 남은 문장은 못 한 이야기에)
 * - 못 한 이야기 = 두 번 끊겨 넘어간 문장 · 멈춘 채 다음 해설로 넘어가거나 도어가 열려 남은 문장 (말하던 중 도어가 열린 것은 넣지 않음)
 *   · 시작도 못 하고 지나친 예정 해설 (miss)
 * - 못 한 이야기 모아 듣기: 정차가 5초 넘게 이어지면 한 번, 도착하면 한 번 더 「아까 못 한 이야기가 n개 있어요…」 를 묻고,
 *   응이면 차례로 말함 · 나중에면 넘김. 화면에 따로 다시 듣기 단추는 두지 않음
 * Pleos TextToSpeech 에는 일시정지가 없어(speak · stop 만) 문장마다 따로 보내고 어느 문장에서 끊겼는지 기억함.
 */
class Narrator(
    private val speaker: Speaker,
    private val resumeLine: String = RESUME_LINE,
    private val clock: () -> Long = System::currentTimeMillis,
) {
    enum class State { IDLE, SPEAKING, WAITING, PAUSED, DONE }

    /** @param arrival 도착해서 듣는 것(못 한 이야기 제안 · 모아 듣기) · 여행 기록 중에도 말함 */
    data class Story(
        val id: String,
        val title: String,
        val sentences: List<String>,
        val language: String = "ko",
        val arrival: Boolean = false,
    )

    /** 화면에 보일 말 한 줄. cut = 말하다 끊김 */
    data class Line(val text: String, val cut: Boolean = false, val resume: Boolean = false)

    /** 못 한 이야기 한 편: 그 해설에서 듣지 못한 문장들 */
    data class Missed(val storyId: String, val title: String, val sentences: List<String>)

    data class Snapshot(
        val storyId: String?,
        val title: String?,
        val state: State,
        /** 지금(또는 다음에) 말할 문장 번호 */
        val index: Int,
        val total: Int,
        /** 이 해설에서 말을 시작한 줄 (이어 말하기 한마디 포함) */
        val lines: List<Line>,
        /** 지금 말하는 글 (없으면 null) */
        val speaking: String?,
        val error: String?,
        /** 아직 듣지 못한 이야기 (여행 기록에도 남김) */
        val missed: List<Missed>,
        /** 「지금 들려드릴까요?」 를 묻고 대답을 기다리는 중 */
        val awaitingAnswer: Boolean,
    )

    private var story: Story? = null
    private var index = 0
    private var state = State.IDLE
    private var interrupted = false
    private var mode = NarrationMode.NARRATE
    private var oneLineBudget = 0
    private var token = 0
    private var speakingResume = false
    private var speaking: String? = null
    private var error: String? = null
    private val lines = ArrayList<Line>()
    private val cuts = HashMap<Int, Int>()
    private val missed = LinkedHashMap<String, Missed>()
    private var awaitingAnswer = false
    private var wantOffer = false
    private var stopOfferDone = false
    private var arrivalOfferDone = false
    private var stoppedSince: Long? = null

    /** 새 해설을 처음부터 (말하던 것은 끊음 · 멈춰 있던 해설의 남은 문장은 못 한 이야기에) */
    @Synchronized
    fun play(next: Story) {
        keepUnheard()
        cancelSpeech()
        start(next)
        advance()
    }

    /** 해설을 그만둠 */
    @Synchronized
    fun stopAll() {
        cancelSpeech()
        story = null
        lines.clear()
        awaitingAnswer = false
        state = State.IDLE
    }

    /** 해설 방식이 바뀔 때마다 (같은 값이 다시 와도 됨) */
    @Synchronized
    fun onMode(next: NarrationMode) {
        val before = mode
        mode = next
        if (next == NarrationMode.ONE_LINE && before != NarrationMode.ONE_LINE) {
            // 한 문장 구간에 들어설 때 말하던 문장이 있으면 그것이 그 한 문장
            oneLineBudget = if (state == State.SPEAKING) 0 else 1
        }
        if (next != NarrationMode.LONG_STORY) stoppedSince = null
        else if (stoppedSince == null) stoppedSince = clock()
        when (next) {
            NarrationMode.TRIP_RECORD -> if (story != null && story?.arrival != true && state != State.DONE) {
                keepUnheard()
                cancelSpeech(markCut = true)
                state = State.DONE
            }
            NarrationMode.HOLD -> if (state == State.SPEAKING) {
                val sentenceCut = !speakingResume
                cancelSpeech(markCut = true)
                if (sentenceCut) cuts[index] = (cuts[index] ?: 0) + 1
                interrupted = true
                state = State.PAUSED
            }
            else -> advance()
        }
        tick()
    }

    /** 시간이 흐를 때마다 (화면이 상태를 물을 때) · 정차가 5초 넘으면 못 한 이야기를 한 번 제안 */
    @Synchronized
    fun tick() {
        val since = stoppedSince
        if (since != null && !stopOfferDone && missed.isNotEmpty() && clock() - since >= STOP_OFFER_MS) {
            stopOfferDone = true
            wantOffer = true
        }
        offerIfFree()
    }

    /** 목적지 · 경유지에 도착 (NaviHelper onDestinationArrived · onWayPointArrived · 시연은 도착 장면) */
    @Synchronized
    fun arrived() {
        if (arrivalOfferDone || missed.isEmpty()) return
        arrivalOfferDone = true
        wantOffer = true
        offerIfFree()
    }

    /** 시작도 못 한 예정 해설을 못 한 이야기에 (NarrationSchedule 이 지나쳤다고 알려 줄 때) */
    @Synchronized
    fun miss(story: Story) {
        addMissed(story, story.sentences)
        offerIfFree()
    }

    /** 「지금 들려드릴까요?」 에 대한 대답 (응 · 들려줘 / 아니 · 나중에) */
    @Synchronized
    fun answer(yes: Boolean) {
        if (!awaitingAnswer) return
        awaitingAnswer = false
        if (!yes) return
        val all = missed.values.toList()
        missed.clear()
        val sentences = all.flatMap { it.sentences }
        if (sentences.isEmpty()) return
        cancelSpeech()
        start(Story("missed", "못 한 이야기", sentences, story?.language ?: "ko", arrival = true))
        advance()
    }

    @Synchronized
    fun snapshot() = Snapshot(
        storyId = story?.id,
        title = story?.title,
        state = state,
        index = index,
        total = story?.sentences?.size ?: 0,
        lines = lines.toList(),
        speaking = speaking,
        error = error,
        missed = missed.values.toList(),
        awaitingAnswer = awaitingAnswer,
    )

    fun release() {
        stopAll()
        speaker.release()
    }

    private fun start(next: Story) {
        story = next
        index = 0
        interrupted = false
        speakingResume = false
        error = null
        lines.clear()
        cuts.clear()
        if (next.id != OFFER_ID) awaitingAnswer = false
        state = if (next.sentences.isEmpty()) State.DONE else State.WAITING
    }

    /** 멈춰 있던 해설의 남은 문장을 못 한 이야기에 */
    private fun keepUnheard() {
        val s = story ?: return
        if (s.arrival) return
        if (state == State.PAUSED) {
            addMissed(s, s.sentences.drop(index))
        }
    }

    private fun addMissed(s: Story, sentences: List<String>) {
        if (sentences.isEmpty()) return
        val before = missed[s.id]
        missed[s.id] = Missed(s.id, s.title, (before?.sentences.orEmpty() + sentences).distinct())
    }

    private fun offerIfFree() {
        if (!wantOffer || missed.isEmpty()) return
        if (state == State.SPEAKING || state == State.PAUSED || state == State.WAITING) return
        if (mode == NarrationMode.HOLD) return
        wantOffer = false
        awaitingAnswer = true
        start(Story(OFFER_ID, "못 한 이야기", listOf(offerLine(missed.size)), story?.language ?: "ko", arrival = true))
        advance()
    }

    private fun advance() {
        val s = story ?: return
        if (state != State.WAITING && state != State.PAUSED) return
        if (mode == NarrationMode.HOLD) return
        if (mode == NarrationMode.TRIP_RECORD && !s.arrival) return
        if (state == State.PAUSED && interrupted) {
            interrupted = false
            if ((cuts[index] ?: 0) >= 2) {
                // 같은 문장이 두 번 끊김: 또 말하지 않고 다음 문장으로 · 그 문장은 못 한 이야기에
                addMissed(s, listOf(s.sentences[index]))
                index++
                if (index >= s.sentences.size) {
                    state = State.DONE
                    offerIfFree()
                    return
                }
            }
            // 이어 말하기 한마디는 한 문장 수에 넣지 않음
            say(resumeLine, s.language, resume = true)
            return
        }
        if (mode == NarrationMode.ONE_LINE) {
            if (oneLineBudget <= 0) {
                state = State.WAITING
                return
            }
            oneLineBudget--
        }
        say(s.sentences[index], s.language, resume = false)
    }

    private fun say(text: String, language: String, resume: Boolean) {
        val my = ++token
        state = State.SPEAKING
        speakingResume = resume
        speaking = text
        lines += Line(text, resume = resume)
        if (lines.size > MAX_LINES) lines.removeAt(0)
        speaker.speak(text, language, { finished(my, null) }, { why -> finished(my, why) })
    }

    @Synchronized
    private fun finished(my: Int, failure: String?) {
        if (my != token || state != State.SPEAKING) return
        if (failure != null) error = failure
        speaking = null
        val s = story ?: return
        if (speakingResume) {
            // 이어 말하기 한마디 뒤 끊긴 문장을 처음부터
            speakingResume = false
            state = State.WAITING
            if (mode != NarrationMode.ONE_LINE) {
                say(s.sentences[index], s.language, resume = false)
            } else {
                advance()
            }
            return
        }
        index++
        if (index >= s.sentences.size) {
            state = State.DONE
            offerIfFree()
            return
        }
        state = State.WAITING
        advance()
    }

    private fun cancelSpeech(markCut: Boolean = false) {
        if (state == State.SPEAKING) {
            token++
            speaker.stop()
            if (markCut && lines.isNotEmpty()) lines[lines.size - 1] = lines.last().copy(cut = true)
        }
        speaking = null
        speakingResume = false
    }

    companion object {
        const val RESUME_LINE = "아까 하던 이야기 이어서 할게요."
        const val OFFER_ID = "offer"
        const val STOP_OFFER_MS = 5_000L
        private const val MAX_LINES = 40

        fun offerLine(count: Int) = "아까 못 한 이야기가 ${count}개 있어요. 지금 들려드릴까요?"

        /** 해설 글을 문장으로 나눔 (. ! ? 。 뒤 띄어쓰기 기준 · 문장부호는 남김) */
        fun split(text: String): List<String> =
            text.trim().split(Regex("(?<=[.!?。])\\s+")).map { it.trim() }.filter { it.isNotEmpty() }
    }
}
