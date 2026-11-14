# The README's figures for a clustered plan's lots

Status: not started. Written 2026-11-14. Small.

## What is wrong

In "What has been tried", the eight-GPU part of the README says of the clustered plan: "lots took 3.9 s on
average, a worker's first up to 24.5 s while its kernels were compiled". The 3.9 s is the mean over all 80 lots,
the eight slow first ones among them, so it understates what the clustered plan does once a worker is going.

## What it should say

From the same run's log (2,000,000 paths, lots of 25,000, eight A100s):

- a lot after a worker's first: a median of 1.8 s, against 12.2 s on the plain plan;
- a worker's first lot: 20.7 to 24.5 s, against 12.5 to 13.6 s;
- all lots added up: 310 s, against 990 s.

And from the 200,000-path run (lots of 5,000): 0.4 s a lot after the first against 2.6 s, with first lots of 17
to 19 s.

Say too what the plan files show, since it is fact and not a guess at the cause: the plain plan is 6,027 kernel
launches a pass and writes every one of its 19.9 million values to the GPU's memory; the clustered one is 1,741
launches and writes 12% of them. Nothing was profiled, so the README should not say which of those the time comes
from.

The site's page (`/bench/watershed-cluster/`) already has these figures; this brings the README level with it.
