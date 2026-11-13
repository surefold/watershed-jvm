//! Plans: a graph prepared to run for many sets of inputs at once, on the
//! CPU or an NVIDIA GPU, and written to a file for another process to run.
//!
//! EXPERIMENTAL, as it is in the engine. This is the safe part; `ffi.rs`
//! turns addresses into the slices these take.

use std::fs::File;
use std::io::{BufReader, BufWriter};
use std::sync::{Mutex, MutexGuard};

use watershed_gpu::Scenarios;

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
    #[cfg(feature = "cuda")]
    inner: Mutex<watershed_gpu::Gpu>,
}

impl Gpu {
    #[cfg(feature = "cuda")]
    pub(crate) fn new() -> Result<Gpu> {
        watershed_gpu::Gpu::new()
            .map(|gpu| Gpu {
                inner: Mutex::new(gpu),
            })
            .or_else(refused)
    }

    #[cfg(not(feature = "cuda"))]
    pub(crate) fn new() -> Result<Gpu> {
        refused("this build of the library has no GPU support: build it with the cuda feature")
    }

    /// The bytes of the GPU's memory that are free.
    pub(crate) fn free_memory(&self) -> Result<i64> {
        #[cfg(feature = "cuda")]
        {
            let gpu = self.inner.lock().unwrap_or_else(|e| e.into_inner());
            gpu.free_memory().map(|n| n as i64).or_else(refused)
        }
        #[cfg(not(feature = "cuda"))]
        refused("no GPU support")
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
        #[cfg(feature = "cuda")]
        {
            let plan = plan.lock();
            Plan::scenarios(&plan, inputs, out)?;
            let allowed = usize::try_from(allowed).map_err(|_| "the memory allowed is negative")?;
            let gpu = self.inner.lock().unwrap_or_else(|e| e.into_inner());
            let (results, _) = gpu.run_within(&plan, inputs, allowed).or_else(refused)?;
            out.copy_from_slice(&results);
            Ok(())
        }
        #[cfg(not(feature = "cuda"))]
        {
            let _ = (plan, inputs, out, allowed);
            refused("no GPU support")
        }
    }
}
