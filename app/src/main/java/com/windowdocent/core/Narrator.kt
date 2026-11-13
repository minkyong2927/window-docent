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
 * - 멈춤(HOLD: 방향지시등 · 큰 조향 · 내비 안내 음성): 말하던 중이라도 바로 끊고, 끊긴 문장을 기억
 * - 멈춤이 풀리면: 「아까 하던 이야기 이어서 할게요」 한마디 뒤 끊긴 문장을 처음부터 다시 말하고 이어 감
 * - 한 문장(ONE_LINE: 분기점 접근): 지금 문장은 끝까지, 새로 시작하는 것은 한 문장까지
 * - 여행 기록(TRIP_RECORD: 도어 열림): 해설을 끝냄
 * Pleos TextToSpeech 에는 일시정지가 없어(speak · stop 만) 문장마다 따로 보내고 어느 문장에서 끊겼는지 기억함.
 */
class Narrator(
    private val speaker: Speaker,
    private val resumeLine: String = RESUME_LINE,
) {
    enum class State { IDLE, SPEAKING, WAITING, PAUSED, DONE }

    data class Story(val id: String, val title: String, val sentences: List<String>, val language: String = "ko")

    /** 화면에 보일 말 한 줄. cut = 말하다 끊김 */
    data class Line(val text: String, val cut: Boolean = false, val resume: Boolean = false)

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

    /** 새 해설을 처음부터 (말하던 것은 끊음) */
    @Synchronized
    fun play(next: Story) {
        cancelSpeech()
        story = next
        index = 0
        interrupted = false
        speakingResume = false
        error = null
        lines.clear()
        state = if (next.sentences.isEmpty()) State.DONE else State.WAITING
        advance()
    }

    /** 해설을 그만둠 */
    @Synchronized
    fun stopAll() {
        cancelSpeech()
        story = null
        lines.clear()
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
        when (next) {
            NarrationMode.TRIP_RECORD -> if (story != null && state != State.DONE) {
                cancelSpeech(markCut = true)
                state = State.DONE
            }
            NarrationMode.HOLD -> if (state == State.SPEAKING) {
                cancelSpeech(markCut = true)
                interrupted = true
                state = State.PAUSED
            }
            else -> advance()
        }
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
    )

    fun release() {
        stopAll()
        speaker.release()
    }

    private fun advance() {
        val s = story ?: return
        if (state != State.WAITING && state != State.PAUSED) return
        if (mode == NarrationMode.HOLD || mode == NarrationMode.TRIP_RECORD) return
        if (state == State.PAUSED && interrupted) {
            // 이어 말하기 한마디는 한 문장 수에 넣지 않음
            interrupted = false
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
        private const val MAX_LINES = 40

        /** 해설 글을 문장으로 나눔 (. ! ? 。 뒤 띄어쓰기 기준 · 문장부호는 남김) */
        fun split(text: String): List<String> =
            text.trim().split(Regex("(?<=[.!?。])\\s+")).map { it.trim() }.filter { it.isNotEmpty() }
    }
}
