package dev.surefold.watershed

/** The engine would not do what it was asked, and why: the one failure every call here declares. */
@JvmRecord
data class Refused(val why: String)

/** What the last update did: which update it was, and how many inputs changed, nodes were recalculated and values changed. */
@JvmRecord
data class Report(val update: Long, val inputsChanged: Long, val recalculated: Long, val changed: Long)
