//! Plans: a graph prepared to run for many sets of inputs at once, on the
//! CPU or an NVIDIA GPU, and written to a file for another process to run.
//!
//! EXPERIMENTAL, as it is in the engine. This is the safe part; `ffi.rs`
//! turns addresses into the slices these take.

use std::fs::File;
use std::io::{BufReader, BufWriter};
use std::sync::{Mutex, MutexGuard};

use watershed_gpu::{Kept as EngineKept, Patch as EnginePatch, Scenarios};

use crate::wire::Result;
use crate::{Graph, refused};

/// A plan, as the JVM holds it.
pub(crate) struct Plan {
    inner: Mutex<Scenarios>,
}

impl Plan {
    fn of(plan: Scenarios) -> Plan {
        Plan {
            inner: Mutex::new(plan),
        }
    }

    fn lock(&self) -> MutexGuard<'_, Scenarios> {
        self.inner.lock().unwrap_or_else(|e| e.into_inner())
    }

    /// A plan of the graph as it stands: `vary` differ in every scenario,
    /// `shared` are the same in all of a run, and `outputs` are read.
    pub(crate) fn new(
        graph: &Graph,
        vary: &[i32],
        shared: &[i32],
        outputs: &[i32],
    ) -> Result<Plan> {
        let (vary, shared, outputs) = (
            graph.nodes(vary)?,
            graph.nodes(shared)?,
            graph.nodes(outputs)?,
        );
        graph
            .read(|g| Scenarios::with_shared(g, &vary, &shared, &outputs))
            .map(Plan::of)
            .or_else(refused)
    }

    pub(crate) fn save(&self, path: &str) -> Result<()> {
        let file = File::create(path).or_else(|e| refused(format!("{path}: {e}")))?;
        self.lock()
            .save(BufWriter::new(file))
            .or_else(|e| refused(format!("{path}: {e}")))
    }

    pub(crate) fn load(path: &str) -> Result<Plan> {
        let file = File::open(path).or_else(|e| refused(format!("{path}: {e}")))?;
        Scenarios::load(BufReader::new(file))
            .map(Plan::of)
            .or_else(|e| refused(format!("{path}: {e}")))
    }

    /// The plan as the bytes `save` writes, held here for the JVM to read.
    pub(crate) fn saved(&self) -> Result<Saved> {
        let mut bytes = Vec::new();
        self.lock().save(&mut bytes).or_else(refused)?;
        Ok(Saved(bytes))
    }

    /// A plan from the bytes `saved` gave, wherever they have been since.
    pub(crate) fn of_saved(saved: &Saved) -> Result<Plan> {
        Scenarios::load(&saved.0[..]).map(Plan::of).or_else(refused)
    }

    /// How many varied inputs, shared inputs, outputs, and nodes worked
    /// out for each scenario.
    pub(crate) fn sizes(&self) -> [i64; 4] {
        let plan = self.lock();
        [
            plan.inputs(),
            plan.shared_inputs(),
            plan.outputs(),
            plan.nodes(),
        ]
        .map(|n| n as i64)
    }

    pub(crate) fn set_shared(&self, values: &[f64]) -> Result<()> {
        let mut plan = self.lock();
        if values.len() != plan.shared_inputs() {
            return refused(format!(
                "the plan has {} shared inputs and was given {} values",
                plan.shared_inputs(),
                values.len()
            ));
        }
        plan.set_shared(values);
        Ok(())
    }

    /// How many scenarios `inputs` holds, if `out` has room for their answers.
    fn scenarios(plan: &Scenarios, inputs: &[f64], out: &[f64]) -> Result<usize> {
        if inputs.len() % plan.inputs() != 0 {
            return refused(format!(
                "{} input values is not a whole number of scenarios of {} inputs each",
                inputs.len(),
                plan.inputs()
            ));
        }
        let scenarios = inputs.len() / plan.inputs();
        if out.len() != scenarios * plan.outputs() {
            return refused(format!(
                "{scenarios} scenarios of {} outputs each need room for {} values, not {}",
                plan.outputs(),
                scenarios * plan.outputs(),
                out.len()
            ));
        }
        Ok(scenarios)
    }

    /// Runs every scenario on the CPU, on all cores.
    pub(crate) fn run_cpu(&self, inputs: &[f64], out: &mut [f64]) -> Result<()> {
        let plan = self.lock();
        Plan::scenarios(&plan, inputs, out)?;
        out.copy_from_slice(&plan.run_cpu(inputs));
        Ok(())
    }
}

/// An NVIDIA GPU, as the JVM holds it.
pub(crate) struct Gpu {
    #[cfg(feature = "gpu")]
    inner: Mutex<watershed_gpu::Gpu>,
}

impl Gpu {
    #[cfg(feature = "gpu")]
    pub(crate) fn new() -> Result<Gpu> {
        watershed_gpu::Gpu::new()
            .map(|gpu| Gpu {
                inner: Mutex::new(gpu),
            })
            .or_else(refused)
    }

    #[cfg(not(feature = "gpu"))]
    pub(crate) fn new() -> Result<Gpu> {
        refused(
            "this build of the library has no GPU support: build it with the cuda or cuda-12 feature",
        )
    }

    /// The bytes of the GPU's memory that are free.
    pub(crate) fn free_memory(&self) -> Result<i64> {
        #[cfg(feature = "gpu")]
        {
            let gpu = self.inner.lock().unwrap_or_else(|e| e.into_inner());
            gpu.free_memory().map(|n| n as i64).or_else(refused)
        }
        #[cfg(not(feature = "gpu"))]
        refused("no GPU support")
    }

    /// Runs an engine's plan on the GPU, within `allowed` bytes of its memory.
    #[cfg(feature = "gpu")]
    fn run_within(&self, plan: &Scenarios, inputs: &[f64], allowed: i64) -> Result<Vec<f64>> {
        let allowed = usize::try_from(allowed).map_err(|_| "the memory allowed is negative")?;
        let gpu = self.inner.lock().unwrap_or_else(|e| e.into_inner());
        gpu.run_within(plan, inputs, allowed)
            .map(|(results, _)| results)
            .or_else(refused)
    }

    /// Runs every scenario on the GPU, in as many parts as fit in `allowed`
    /// bytes of its memory.
    pub(crate) fn run(
        &self,
        plan: &Plan,
        inputs: &[f64],
        out: &mut [f64],
        allowed: i64,
    ) -> Result<()> {
        #[cfg(feature = "gpu")]
        {
            let plan = plan.lock();
            Plan::scenarios(&plan, inputs, out)?;
            let allowed = usize::try_from(allowed).map_err(|_| "the memory allowed is negative")?;
            let gpu = self.inner.lock().unwrap_or_else(|e| e.into_inner());
            let (results, _) = gpu.run_within(&plan, inputs, allowed).or_else(refused)?;
            out.copy_from_slice(&results);
            Ok(())
        }
        #[cfg(not(feature = "gpu"))]
        {
            let _ = (plan, inputs, out, allowed);
            refused("no GPU support")
        }
    }
}

/// What is kept of a graph's plan so that a run can be brought up to date
/// after a change: which values, and what uses what. It holds no values.
pub(crate) struct Kept {
    inner: EngineKept,
}

impl Kept {
    pub(crate) fn new(
        graph: &Graph,
        vary: &[i32],
        shared: &[i32],
        outputs: &[i32],
        keep: &[i32],
    ) -> Result<Kept> {
        let (vary, shared) = (graph.nodes(vary)?, graph.nodes(shared)?);
        let (outputs, keep) = (graph.nodes(outputs)?, graph.nodes(keep)?);
        graph
            .read(|g| EngineKept::new(g, &vary, &shared, &outputs, &keep))
            .map(|inner| Kept { inner })
            .or_else(refused)
    }

    /// The plan whose outputs are every kept value of a scenario.
    pub(crate) fn plan(&self) -> Plan {
        Plan::of(self.inner.plan().clone())
    }

    /// Where each output is among a scenario's kept values.
    pub(crate) fn columns(&self) -> &[u32] {
        self.inner.output_columns()
    }

    /// What to run now that the inputs `changed` have new values on the
    /// graph; `None` if nothing kept uses them.
    pub(crate) fn patch(&self, graph: &Graph, changed: &[i32]) -> Result<Option<Patch>> {
        let changed = graph.nodes(changed)?;
        graph
            .read(|g| self.inner.patch(g, &changed))
            .map(|patch| patch.map(|inner| Patch { inner }))
            .or_else(refused)
    }
}

/// A small plan and which kept values it reads and replaces.
pub(crate) struct Patch {
    inner: EnginePatch,
}

impl Patch {
    pub(crate) fn save(&self, path: &str) -> Result<()> {
        let file = File::create(path).or_else(|e| refused(format!("{path}: {e}")))?;
        self.inner
            .save(BufWriter::new(file))
            .or_else(|e| refused(format!("{path}: {e}")))
    }

    pub(crate) fn load(path: &str) -> Result<Patch> {
        let file = File::open(path).or_else(|e| refused(format!("{path}: {e}")))?;
        EnginePatch::load(BufReader::new(file))
            .map(|inner| Patch { inner })
            .or_else(|e| refused(format!("{path}: {e}")))
    }

    /// The patch as the bytes `save` writes, held here for the JVM to read.
    pub(crate) fn saved(&self) -> Result<Saved> {
        let mut bytes = Vec::new();
        self.inner.save(&mut bytes).or_else(refused)?;
        Ok(Saved(bytes))
    }

    /// A patch from the bytes `saved` gave.
    pub(crate) fn of_saved(saved: &Saved) -> Result<Patch> {
        EnginePatch::load(&saved.0[..])
            .map(|inner| Patch { inner })
            .or_else(refused)
    }

    /// How many values are kept for a scenario, how many of them the patch
    /// works out again, and on how many nodes.
    pub(crate) fn sizes(&self) -> [i64; 3] {
        [
            self.inner.width(),
            self.inner.stale(),
            self.inner.plan.nodes(),
        ]
        .map(|n| n as i64)
    }
}

/// Some scenarios' inputs and kept values, held to be brought up to date.
pub(crate) struct Held {
    inner: Mutex<(Vec<f64>, Vec<f64>)>,
    vary: usize,
    width: usize,
}

impl Held {
    /// Holds `inputs` and the `values` a kept plan gave for them.
    pub(crate) fn new(vary: i64, inputs: &[f64], values: &[f64]) -> Result<Held> {
        let vary = usize::try_from(vary)
            .ok()
            .filter(|&v| v > 0)
            .ok_or("how many inputs a scenario has must be positive")?;
        if inputs.len() % vary != 0 || inputs.is_empty() {
            return refused(format!(
                "{} input values is not a whole number of scenarios of {vary} inputs each",
                inputs.len()
            ));
        }
        let scenarios = inputs.len() / vary;
        if values.len() % scenarios != 0 || values.is_empty() {
            return refused(format!(
                "{} values is not the same number for each of {scenarios} scenarios",
                values.len()
            ));
        }
        Ok(Held {
            inner: Mutex::new((inputs.to_vec(), values.to_vec())),
            vary,
            width: values.len() / scenarios,
        })
    }

    /// The values in the places `columns`, for each scenario in turn.
    pub(crate) fn columns(&self, columns: &[i32], out: &mut [f64]) -> Result<()> {
        let held = self.inner.lock().unwrap_or_else(|e| e.into_inner());
        if columns
            .iter()
            .any(|&c| usize::try_from(c).map_or(true, |c| c >= self.width))
        {
            return refused(format!(
                "a scenario has {} kept values, and one asked for is not among them",
                self.width
            ));
        }
        if columns.is_empty() || out.len() != held.1.len() / self.width * columns.len() {
            return refused("the room given is not that of the values asked for");
        }
        for (row, out) in held.1.chunks(self.width).zip(out.chunks_mut(columns.len())) {
            for (x, &c) in out.iter_mut().zip(columns) {
                *x = row[c as usize];
            }
        }
        Ok(())
    }

    /// Brings the values up to date with `patch`, on `gpu` or the CPU.
    pub(crate) fn refresh(&self, patch: &Patch, gpu: Option<(&Gpu, i64)>) -> Result<()> {
        let mut held = self.inner.lock().unwrap_or_else(|e| e.into_inner());
        let patch = &patch.inner;
        if patch.width() != self.width || patch.plan.inputs() < self.vary {
            return refused(format!(
                "the patch is for scenarios of {} kept values, and these have {}",
                patch.width(),
                self.width
            ));
        }
        let inputs = patch.inputs(&held.0, &held.1);
        let results = match gpu {
            #[cfg(feature = "gpu")]
            Some((gpu, allowed)) => gpu.run_within(&patch.plan, &inputs, allowed)?,
            #[cfg(not(feature = "gpu"))]
            Some(_) => return refused("no GPU support"),
            None => patch.plan.run_cpu(&inputs),
        };
        patch.apply(&mut held.1, &results);
        Ok(())
    }
}

/// A plan or a patch as bytes, held in the engine while they cross to or
/// from the JVM a part at a time: a plan can be bigger than one JVM array.
pub(crate) struct Saved(pub(crate) Vec<u8>);

impl Saved {
    /// Room for `size` bytes, to be written a part at a time.
    pub(crate) fn sized(size: usize) -> Saved {
        Saved(vec![0; size])
    }

    /// The `count` bytes from `from`, if they are all there.
    pub(crate) fn part(&self, from: usize, count: usize) -> Result<&[u8]> {
        match from.checked_add(count).and_then(|to| self.0.get(from..to)) {
            Some(part) => Ok(part),
            None => refused(format!(
                "bytes {from} to {} are not all within the {} there are",
                from.saturating_add(count),
                self.0.len()
            )),
        }
    }

    pub(crate) fn part_mut(&mut self, from: usize, count: usize) -> Result<&mut [u8]> {
        let size = self.0.len();
        match from
            .checked_add(count)
            .and_then(|to| self.0.get_mut(from..to))
        {
            Some(part) => Ok(part),
            None => refused(format!(
                "bytes {from} to {} are not all within the {size} there are",
                from.saturating_add(count)
            )),
        }
    }
}
