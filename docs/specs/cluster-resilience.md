# A job that survives the loss of any one node

Status: not started. Written 2026-11-14, after the eight-GPU runs.

## What is wanted

A job on the cluster finishes with the right answers when any one node is lost while it runs: a worker, the node
the coordinator is on, or the process that submitted it. This is to hold with the nodes on several machines, and
each case is to have a measured time to recover.

## What is there today

Every lot gives the same numbers whoever runs it, and its answers are written as one whole file. So a lot run
twice, or on two workers at once, cannot give a wrong answer; it costs time and nothing else. Everything below
leans on that.

| Lost | What happens now | Tried |
|---|---|---|
| A worker | The cluster gives up on it after `downing.stableAfter` (10 s) and its lots move. The coordinator tells unanswered lots again every 5 s, which reaches them where they now are. | Yes: a worker leaving (`ClusterSuite`), and `kill -9` on one machine |
| A worker that hangs | A lot has `patience.lot-within`; past it the lot is given out under a new id that lands elsewhere, and the worker is avoided. | Yes |
| A lot that fails | Tried on up to three workers, then the job fails with the last reason. | Yes |
| The coordinator's node | The singleton starts on the next node with the role. It knows nothing, so whoever submitted the job is told the round is unknown and sends it again; lots that ran answer from memory without running again. | Yes, with a standby stopped cleanly. Not with a crash |
| The coordinator's node, with no standby | The job stops. | The eight-GPU runs had no standby |
| Whoever submitted the job | Lots in hand finish and their answers are in the bucket. Nothing starts the rest, and nothing reports the job. | No |
| A network split | Not known. How surefold-cluster's downing chooses a side has not been read. | No |

Other limits that matter here: a worker that joins while a job runs takes lots from the others, and they run
again; what a lot holds for bringing a run up to date is in its worker's memory only.

## What to build, in this order

### 1. Standby coordinators

No code. Give the `coordinator` role to three nodes on different machines; a worker's node can carry it. Say so
in the README's part on running a node, and have `cluster/run-gpus.sh` take how many.

### 2. The bucket as the record of a job

The main piece. A job is then no longer in the memory of whoever submitted it, nor in the coordinator's.

- Submitting writes a manifest at `<out>/job`: the `Job` and what was asked (`only`, `patch`, `within`), with the
  kotlinx codec already used on the wire.
- A lot is done when its answers file is there. Give `Store` a way to list which of a job's lots are, in one call.
- A coordinator that starts, for whatever reason, lists the manifests that have no `<out>/done`, and for each
  reads which lots are answered and gives out the rest.
- When every lot is answered the coordinator writes `<out>/done` with the report.
- `submit` writes the manifest and then waits for `<out>/done`, or does not wait at all if asked not to. A new
  command, `status`, reads the same two files.

What goes: `Start` being sent again by the submitter, and `Standing.Unknown`. What stays: the lots' own memory of
having run, as a quicker answer than listing the bucket.

A worker that joins while a job runs then takes only lots that nobody has answered.

Not in this: what a lot holds for `refresh`. It stays in memory, and a lot whose worker went is reported lost and
run again, as now. Keeping it in the bucket is about 900 MB for 10,000 scenarios of the insurance book.

### 3. Leaving cleanly, and a second runner for stragglers

- On SIGTERM a worker leaves the cluster (`leaveWithin`), so its lots move at once and not after ten seconds.
  This is what makes machines that can be taken back at short notice usable.
- When idle workers outnumber the lots still out, the coordinator gives each such lot to a second worker under a
  new id. The first answer ends it. One slow machine then does not hold up the end of a job.

### 4. Network splits

Read surefold-cluster's downing first (`surefold/surefold-core`, the cluster module and its docs). With the bucket
as the record the worst a split can do is the same lots run on both sides. Decide after reading whether that is
enough, or whether the coordinator should run only on the side that can see most of the nodes.

## How to tell it is done

On three rented single-GPU machines (an A10 each is enough), with the insurance book and a job long enough to
interrupt, each of these ends with answers the same to the last bit as one run in one process. Record, for each,
the time from the loss to the next lot being answered:

1. `kill -9` a worker's process.
2. Turn off a worker's machine.
3. `kill -9` the process the coordinator is on, with two standbys.
4. `kill -9` the submitter, then ask `status` from another machine until it says done.
5. Stop a worker with SIGTERM, and see that its lots move in under two seconds.
6. Cut the network between one machine and the other two for thirty seconds, then mend it.
7. Start a fourth worker half way through, and see that no answered lot runs again.

Each also has a test in `cluster/src/test` at the level it can be given there: the coordinator's part with
`testActors`, the rest with the nodes of `Nodes.kt`.

The numbers go in the README's "What has been tried", and only then on the site.

## Open questions

- Should a manifest that is never finished be given up on after a time, and who clears it?
- Listing a bucket is a charged call on R2 and S3. How often may a coordinator list a job's answers?
- Does a job need an owner, so that two submitters cannot write the same `<out>`?
