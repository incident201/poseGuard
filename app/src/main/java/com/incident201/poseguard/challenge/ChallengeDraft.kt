package com.incident201.poseguard.challenge

/**
 * The challenge being edited. Every rule of the future challenge is here and visible in the
 * editor; nothing is taken from the author's settings unless they copy them in explicitly.
 */
data class ChallengeDraft(
    val title: String = "",
    val description: String = "",
    val authorName: String = "",
    val rules: ChallengeRules = ChallengeRules(),
    val visibility: ChallengeVisibility = ChallengeVisibility(),
    /** 0 = unlimited. */
    val attemptsAllowed: Int = 0
) {
    val audioClipBytes: Int get() = rules.audioClips.values.sumOf { it.data.size }
}

/** Outcome of attaching an audio file to a draft cue. */
enum class AudioAttachResult { Attached, TooLarge, TotalTooLarge, Unreadable }
