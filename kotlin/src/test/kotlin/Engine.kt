import dev.surefold.assert.shouldBeLeft
import dev.surefold.assert.shouldBeRight
import dev.surefold.fail.Fail
import dev.surefold.fail.attempt
import dev.surefold.watershed.Refused

/** Runs [test], which calls the engine, and fails it if the engine refuses anything it was not expected to. */
fun engine(test: Fail<Refused>.() -> Unit) = attempt(test).shouldBeRight()

/** Why the engine refused [block]; the test fails if it did not. */
fun refusal(block: Fail<Refused>.() -> Any?): Refused = attempt(block).shouldBeLeft()
