package dev.surefold.watershed.cluster

import kotlin.time.Duration

/** Why a job, or bringing one up to date, could not be finished. */
sealed interface JobFailure {
    /** The job's own description is wrong: its scenarios are not a whole number of them, or a lot holds none. */
    data class NotAJob(val why: String) : JobFailure

    /** A file in the bucket, or its copy on this machine, could not be read or written. */
    data class File(val key: String, val why: String) : JobFailure

    /** One job runs at a time, and [running] has not ended. */
    data class Busy(val running: String) : JobFailure

    /** [asked] names lots the job does not have: it has [lots], numbered from 0. */
    data class NoSuchLots(val asked: List<Int>, val lots: Int) : JobFailure

    /** No run of [job] is held, so there is nothing to bring up to date. */
    data class NotHeld(val job: String) : JobFailure

    /** [lot] failed on every worker that tried it, [times] of them; [why] is the last one's reason. */
    data class LotKeptFailing(val lot: Int, val times: Int, val worker: String, val why: String) : JobFailure

    /** Only [answered] of [lots] lots were answered [within] the time allowed. */
    data class OutOfTime(val answered: Int, val lots: Int, val within: Duration) : JobFailure

    /** The coordinator's own actor gave no answer: it has stopped, or took longer than the job was allowed. */
    data class NoAnswer(val why: String) : JobFailure
}

/** A worker asked to use a GPU that could not be opened. */
data class NoGpu(val why: String)
