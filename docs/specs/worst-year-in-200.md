# The worst year in two hundred, in full, on the whole book

Status: planned, not started. Written 2026-11-14. Someone from the industry is interested; the first step is theirs.

## What is wanted

The capital an insurer must hold is what it loses in the worst year in two hundred. Worked out directly that is a
run inside a run: many ways the next year could go, and the whole book valued over many market paths from each.
A Milliman paper says of it, "This brute force approach is too time-consuming to be used in a real insurance
business case" (Boumezoued, Cherchali and Truc, 2022), and firms use the standard formula or a fitted curve
instead.

We want to show, with measurements an actuary would accept, how long the direct calculation takes on the full
book, how much its answer moves with the number of scenarios, and how far the usual shortcuts are from it.

## What is there today

The engine's `capital` example (`crates/watershed-gpu/examples/capital.rs`), on one RTX 3090:

- 4,000 model points, a fifth of the book the other benchmarks use;
- 1,000 outer scenarios, each a fall or rise in the funds (5% growth, 15% swing over the year) and a move in
  interest rates (0.7 of a point, typically), drawn independently;
- 1,000 inner paths from each, the same draws every time, growing at the discount rate;
- a million valuations in 3 minutes, and a worst year in two hundred of 6.1 billion against 9.8 billion from two
  prescribed stresses added by formula.

What it leaves out, and says it does:

- **The year itself.** Each outer scenario is a shock to today's book. Nobody ages, pays a premium, dies or
  cashes in during the year.
- **Other risks.** Only the funds and the level of rates move. Mortality, lapses, expenses and volatility do not.
- **Error bars.** The worst year in two hundred of 1,000 is the fifth worst: five scenarios decide the answer.

And the cluster cannot run it. An outer scenario changes the plan's shared inputs, and a job carries one set.

## What to do, in order

### 0. Ask first

Before any code, put these to the person from the industry. Their answers decide which of the later steps matter.

1. Is a shock to today's book acceptable as a first result, or is nothing credible until the year is projected?
2. Which risks must move in the outer scenarios for the result to mean anything to them?
3. What do they use today (standard formula, least-squares Monte Carlo, replicating portfolios, curve fitting),
   with how many outer and inner scenarios, and how long does it take?
4. How big is a real book in model points, and how many products?
5. What would they want to see compared: the capital figure, its error, the time, the cost?
6. Is this model (lifelib's `CashValue_ME_EX2`) a fair stand-in, or is there a public model nearer their work?

### 1. The full book on one GPU, with error bars (the desktop; nothing to rent)

- Run `capital` on all 20,000 model points, on the plain plan and the clustered one. Record both.
- Say how sure the answer is: for 1,000, 5,000 and 10,000 outer scenarios, the worst year in two hundred with a
  range from resampling the outer scenarios.
- Say what the inner paths cost: the same for 100, 1,000 and 5,000 inner paths. Too few inner paths are known to
  push the figure up; show by how much here.

This alone answers "how far can one desktop GPU go", and tells us how many scenarios are worth running at all.

### 2. The nested run on the cluster

Two ways; decide after step 0.

- **Sets of shared inputs.** A job carries a file of shared-input rows, one for each outer scenario, and a lot is
  "these inner paths under that row". Small change to `Job`, `Run` and the bench; the engine already has
  `set_shared`. Keeps the shock as it is.
- **The outer scenario as inputs that vary.** The first twelve monthly returns of a row are the year, the rest
  are the inner path, and the move in rates is one more input that the discount factors are worked out from in
  the graph. A nested run is then a flat batch and the cluster runs it as it stands. This is also the way to the
  year being projected (step 4).

Either way the scenarios must not be a file: 100,000 by 1,000 rows is 190 GB. A lot is given seeds and makes its
own rows, the same on every worker.

Then, on eight GPUs: 1,000 by 1,000 on the full book, checked to the last bit against one GPU; then 10,000 and
100,000 outer scenarios.

From the flat batch's rates (2,000,000 scenarios in 70 s, 1.8 s a lot of 25,000 once going), a hundred million
valuations of the full book would be about a quarter of an hour on eight A100s with the clustered plan. That is
an estimate. Changing shared inputs a thousand times, or a hundred thousand, has a cost not measured at this size.

### 3. Against the shortcuts

What makes the result worth an actuary's time: with the direct answer in hand, measure what the usual methods
get wrong on the same book.

- The standard-formula style sum of stresses (the example has it).
- A fitted curve: value the book at a few hundred outer scenarios with few inner paths, fit a polynomial in the
  risk drivers, read the bad year off the fit. This is what least-squares Monte Carlo does in outline. Compare
  its capital figure, and its error scenario by scenario, with the direct run.

Ask the person from the industry to say whether our version of their method is a fair one before publishing it.

### 4. The year itself, and more risks

Only if step 0 says it is needed, and it probably will be.

- Project the year: the outer scenario is twelve months of returns and decrements, and the value is taken at the
  end of it, with the year's cash flows counted. Needs the model's present values split at month twelve.
- More drivers in the outer scenarios, each a shared or varied input the model already has a knob for:
  mortality, lapses, expenses. Correlations between them are an assumption to take from the standard formula or
  from the person asked.

### 5. Write it up

A study on the site only when steps 1 to 3 are measured, with the simplifications that remain stated at the top.
Until then the site says what it says now.

## How to tell it is done

- The nested run on the cluster gives the same answers, to the last bit, as `capital` on one GPU for the same
  seeds.
- The worst year in two hundred is given with a range, for at least three sizes of run.
- At least one shortcut is compared with the direct run on the same book.
- Someone who does this for a living has read the method and the caveats before anything is published.

## What it will not be

A regulatory capital calculation. The book is made up, there is one product, there are no assets, no management
actions and no risk margin, and the model is a reference model. It shows what the direct method costs and what
it changes, on a model of the right kind.
