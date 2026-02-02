use std::collections::{HashMap, HashSet};
use std::fmt::Debug;
use std::hash::{Hash, Hasher};
use std::mem;
use std::path::Path;
use std::sync::Arc;
use std::time::Duration;
use std::time::Instant;

use crate::config::ValidationStrategy;
use crate::error::LatteError;
use crate::scripting::cass_error::{CassError, CassErrorKind};
use crate::scripting::context::{handle_retry_error, Context};
use crate::scripting::dynamodb::context::DynamoContext;
use crate::scripting::dynamodb::DynamoError;
use crate::stats::latency::LatencyDistributionRecorder;
use crate::stats::session::SessionStats;
use parking_lot::Mutex;
use rand::distributions::{Distribution, WeightedIndex};
use rand::rngs::SmallRng;
use rand::{Rng, SeedableRng};
use rune::alloc::clone::TryClone;
use rune::compile::meta::Kind;
use rune::compile::{CompileVisitor, MetaError, MetaRef};
use rune::runtime::{AnyObj, Args, RuntimeContext, Shared, VmError, VmResult};
use rune::termcolor::{ColorChoice, StandardStream};
use rune::{vm_try, Any, Diagnostics, Source, Sources, ToValue, Unit, Value, Vm};
use serde::{Deserialize, Serialize};

/// Wraps a reference to Session that can be converted to a Rune `Value`
/// and passed as one of `Args` arguments to a function.
struct SessionRef<'a> {
    context: &'a Context,
}

impl SessionRef<'_> {
    pub fn new(context: &Context) -> SessionRef<'_> {
        SessionRef { context }
    }
}

/// Converts a `SessionRef` to a Rune `Value` for passing to Rune functions.
///
/// # Safety Concerns
///
/// This implementation uses `unsafe { AnyObj::from_ref() }` which bypasses Rust's borrow checker.
/// This is technically unsound because the compiler cannot verify that the referenced `Context`
/// outlives the `Value` produced.
///
/// ## Why This Is Safe In Practice
///
/// The invariants that make this safe are enforced at the call sites:
/// 1. `SessionRef` is only created and used within single function calls in `Workload` methods
///    (`call_run`, `call_load`, `call_prepare`, `call_schema`, `call_erase`)
/// 2. The Rune VM executes synchronously and the `Value` is consumed within the same scope
/// 3. The `Context` is owned by `Workload` and outlives all Rune function calls
/// 4. No `Value` containing a reference escapes the function scope
///
/// The caller MUST ensure that:
/// - The `Value` is not stored beyond the Rune function call
/// - The `Context` reference remains valid for the entire duration of the VM execution
impl ToValue for SessionRef<'_> {
    fn to_value(self) -> VmResult<Value> {
        // SAFETY: The caller guarantees that `self.context` outlives the returned `Value`.
        // See the Safety Concerns section above for invariants.
        let obj = unsafe { AnyObj::from_ref(self.context) };
        VmResult::Ok(Value::from(vm_try!(Shared::new(obj))))
    }
}

/// Wraps a mutable reference to Session that can be converted to a Rune `Value` and passed
/// as one of `Args` arguments to a function.
struct ContextRefMut<'a> {
    context: &'a mut Context,
}

impl ContextRefMut<'_> {
    pub fn new(context: &mut Context) -> ContextRefMut<'_> {
        ContextRefMut { context }
    }
}

/// Converts a mutable `ContextRefMut` to a Rune `Value` for passing to Rune functions.
///
/// # Safety
///
/// See `impl ToValue for SessionRef` for the full safety analysis. The same invariants apply:
/// the caller must ensure the `Context` outlives the `Value` and no `Value` escapes the call scope.
impl ToValue for ContextRefMut<'_> {
    fn to_value(self) -> VmResult<Value> {
        // SAFETY: The caller guarantees that `self.context` outlives the returned `Value`.
        // See `impl ToValue for SessionRef` for invariants.
        let obj = unsafe { AnyObj::from_mut(self.context) };
        VmResult::Ok(Value::from(vm_try!(Shared::new(obj))))
    }
}

/// Wraps a reference to DynamoContext that can be converted to a Rune `Value`
/// and passed as one of `Args` arguments to a function.
struct DynamoSessionRef<'a> {
    context: &'a DynamoContext,
}

impl DynamoSessionRef<'_> {
    pub fn new(context: &DynamoContext) -> DynamoSessionRef<'_> {
        DynamoSessionRef { context }
    }
}

/// Converts a `DynamoSessionRef` to a Rune `Value` for passing to Rune functions.
///
/// # Safety
///
/// See `impl ToValue for SessionRef` for the full safety analysis. The same invariants apply:
/// the caller must ensure the `DynamoContext` outlives the `Value` and no `Value` escapes the call scope.
impl ToValue for DynamoSessionRef<'_> {
    fn to_value(self) -> VmResult<Value> {
        // SAFETY: The caller guarantees that `self.context` outlives the returned `Value`.
        // See `impl ToValue for SessionRef` for invariants.
        let obj = unsafe { AnyObj::from_ref(self.context) };
        VmResult::Ok(Value::from(vm_try!(Shared::new(obj))))
    }
}

/// Wraps a mutable reference to DynamoContext that can be converted to a Rune `Value`
/// and passed as one of `Args` arguments to a function.
struct DynamoContextRefMut<'a> {
    context: &'a mut DynamoContext,
}

impl DynamoContextRefMut<'_> {
    pub fn new(context: &mut DynamoContext) -> DynamoContextRefMut<'_> {
        DynamoContextRefMut { context }
    }
}

/// Converts a mutable `DynamoContextRefMut` to a Rune `Value` for passing to Rune functions.
///
/// # Safety
///
/// See `impl ToValue for SessionRef` for the full safety analysis. The same invariants apply:
/// the caller must ensure the `DynamoContext` outlives the `Value` and no `Value` escapes the call scope.
impl ToValue for DynamoContextRefMut<'_> {
    fn to_value(self) -> VmResult<Value> {
        // SAFETY: The caller guarantees that `self.context` outlives the returned `Value`.
        // See `impl ToValue for SessionRef` for invariants.
        let obj = unsafe { AnyObj::from_mut(self.context) };
        VmResult::Ok(Value::from(vm_try!(Shared::new(obj))))
    }
}

/// Stores the name and hash together.
/// Name is used for message formatting, hash is used for fast function lookup.
#[derive(Debug, Clone, Eq, PartialEq, Serialize, Deserialize)]
pub struct FnRef {
    pub name: String,
    pub hash: rune::Hash,
}

impl Hash for FnRef {
    fn hash<H: Hasher>(&self, state: &mut H) {
        self.hash.hash(state);
    }
}

impl FnRef {
    pub fn new(name: &str) -> FnRef {
        FnRef {
            name: name.to_string(),
            hash: rune::Hash::type_hash([name]),
        }
    }
}

pub const SCHEMA_FN: &str = "schema";
pub const PREPARE_FN: &str = "prepare";
pub const ERASE_FN: &str = "erase";
pub const LOAD_FN: &str = "load";

/// Compiled workload program
#[derive(Clone)]
pub struct Program {
    sources: Arc<Sources>,
    context: Arc<RuntimeContext>,
    unit: Arc<Unit>,
    meta: ProgramMetadata,
}

impl Program {
    /// Performs some basic sanity checks of the workload script source and prepares it
    /// for fast execution. Does not create VM yet.
    ///
    /// # Parameters
    /// - `script`: source code in Rune language
    /// - `params`: parameter values that will be exposed to the script by the `params!` macro
    pub fn new(source: Source, params: HashMap<String, String>) -> Result<Program, LatteError> {
        let mut context = rune::Context::with_default_modules().unwrap();
        crate::scripting::install(&mut context, params);

        let mut options = rune::Options::default();
        options.debug_info(true);

        let mut diagnostics = Diagnostics::new();
        let mut sources = Self::load_sources(source)?;
        let mut meta = ProgramMetadata::new();
        let unit = rune::prepare(&mut sources)
            .with_context(&context)
            .with_diagnostics(&mut diagnostics)
            .with_visitor(&mut meta)?
            .build();

        if !diagnostics.is_empty() {
            let mut writer = StandardStream::stderr(ColorChoice::Always);
            diagnostics.emit(&mut writer, &sources)?;
        }
        let unit = unit?;

        Ok(Program {
            sources: Arc::new(sources),
            context: Arc::new(context.runtime().unwrap()),
            unit: Arc::new(unit),
            meta,
        })
    }

    fn load_sources(source: Source) -> Result<Sources, LatteError> {
        let mut sources = Sources::new();
        if let Some(path) = source.path() {
            if let Some(parent) = path.parent() {
                Self::try_insert_lib_source(parent, &mut sources)?
            }
        }
        sources.insert(source)?;
        Ok(sources)
    }

    // Tries to add `lib.rn` to `sources` if it exists in the same directory as the main source.
    fn try_insert_lib_source(parent: &Path, sources: &mut Sources) -> Result<(), LatteError> {
        let lib_src = parent.join("lib.rn");
        if lib_src.is_file() {
            sources.insert(
                Source::from_path(&lib_src)
                    .map_err(|e| LatteError::ScriptRead(lib_src.clone(), e))?,
            )?;
        }
        Ok(())
    }

    /// Makes a deep copy of context and unit.
    /// Calling this method instead of `clone` ensures that Rune runtime structures
    /// are separate and can be moved to different CPU cores efficiently without accidental
    /// sharing of Arc references.
    fn unshare(&self) -> Program {
        Program {
            meta: self.meta.clone(),
            sources: self.sources.clone(),
            context: Arc::new(self.context.as_ref().try_clone().unwrap()),
            unit: Arc::new(self.unit.as_ref().try_clone().unwrap()),
        }
    }

    /// Initializes a fresh virtual machine needed to execute this program.
    /// This is extremely lightweight.
    fn vm(&self) -> Vm {
        Vm::new(self.context.clone(), self.unit.clone())
    }

    /// Checks if Rune function call result is an error and if so, converts it into [`LatteError`].
    /// Cassandra errors are returned as [`LatteError::Cassandra`].
    /// All other errors are returned as [`LatteError::FunctionResult`].
    /// If result is not an `Err`, it is returned as-is.
    ///
    /// This is needed because execution of the function could actually run till completion just
    /// fine, but the function could return an error value, and in this case we should not
    /// ignore it.
    fn convert_error(&self, function_name: &str, result: Value) -> Result<Value, LatteError> {
        match result {
            Value::Result(result) => match result.take().unwrap() {
                Ok(value) => Ok(value),
                Err(Value::Any(e)) => {
                    if e.borrow_ref().unwrap().type_hash() == CassError::type_hash() {
                        let e = e.take_downcast::<CassError>().unwrap();
                        return Err(LatteError::Cassandra(Box::new(e)));
                    }

                    if e.borrow_ref().unwrap().type_hash() == DynamoError::type_hash() {
                        let e = e.take_downcast::<DynamoError>().unwrap();
                        return Err(LatteError::FunctionResult(
                            function_name.to_string(),
                            format!("{}", e),
                        ));
                    }

                    let e = Value::Any(e);
                    let msg = self.vm().with(|| format!("{e:?}"));
                    Err(LatteError::FunctionResult(function_name.to_string(), msg))
                }
                Err(other) => Err(LatteError::FunctionResult(
                    function_name.to_string(),
                    format!("{other:?}"),
                )),
            },
            other => Ok(other),
        }
    }

    /// Executes given async function with args.
    /// If execution fails, emits diagnostic messages, e.g. stacktrace to standard error stream.
    /// Also signals an error if the function execution succeeds, but the function returns
    /// an error value.
    pub async fn async_call(
        &self,
        fun: &FnRef,
        args: impl Args + Send,
    ) -> Result<Value, LatteError> {
        let handle_err = |e: VmError| {
            let mut out = StandardStream::stderr(ColorChoice::Auto);
            let _ = e.emit(&mut out, &self.sources);
            LatteError::ScriptExecError(fun.name.to_string(), e)
        };
        let execution = self.vm().send_execute(fun.hash, args).map_err(handle_err)?;
        let result = execution
            .async_complete()
            .await
            .into_result()
            .map_err(handle_err)?;
        self.convert_error(fun.name.as_str(), result)
    }

    pub fn has_prepare(&self) -> bool {
        self.has_function(&FnRef::new(PREPARE_FN))
    }

    pub fn has_schema(&self) -> bool {
        self.has_function(&FnRef::new(SCHEMA_FN))
    }

    pub fn has_erase(&self) -> bool {
        self.has_function(&FnRef::new(ERASE_FN))
    }

    pub fn has_load(&self) -> bool {
        self.has_function(&FnRef::new(LOAD_FN))
    }

    pub fn has_function(&self, function: &FnRef) -> bool {
        self.meta.functions.contains(function)
    }

    /// Calls the script's `init` function.
    /// Called once at the beginning of the benchmark.
    /// Typically used to prepare statements.
    pub async fn prepare(&mut self, context: &mut Context) -> Result<(), LatteError> {
        let context = ContextRefMut::new(context);
        self.async_call(&FnRef::new(PREPARE_FN), (context,)).await?;
        Ok(())
    }

    /// Calls the script's `schema` function.
    /// Typically used to create database schema.
    pub async fn schema(&mut self, context: &mut Context) -> Result<(), LatteError> {
        let context = ContextRefMut::new(context);
        self.async_call(&FnRef::new(SCHEMA_FN), (context,)).await?;
        Ok(())
    }

    /// Calls the script's `erase` function.
    /// Typically used to remove the data from the database before running the benchmark.
    pub async fn erase(&mut self, context: &mut Context) -> Result<(), LatteError> {
        let context = ContextRefMut::new(context);
        self.async_call(&FnRef::new(ERASE_FN), (context,)).await?;
        Ok(())
    }

    // ==================== DynamoDB Variants ====================

    /// Calls the script's `prepare` function with a DynamoDB context.
    pub async fn prepare_dynamodb(
        &mut self,
        context: &mut DynamoContext,
    ) -> Result<(), LatteError> {
        let context = DynamoContextRefMut::new(context);
        self.async_call(&FnRef::new(PREPARE_FN), (context,)).await?;
        Ok(())
    }

    /// Calls the script's `schema` function with a DynamoDB context.
    pub async fn schema_dynamodb(&mut self, context: &mut DynamoContext) -> Result<(), LatteError> {
        let context = DynamoContextRefMut::new(context);
        self.async_call(&FnRef::new(SCHEMA_FN), (context,)).await?;
        Ok(())
    }

    /// Calls the script's `erase` function with a DynamoDB context.
    pub async fn erase_dynamodb(&mut self, context: &mut DynamoContext) -> Result<(), LatteError> {
        let context = DynamoContextRefMut::new(context);
        self.async_call(&FnRef::new(ERASE_FN), (context,)).await?;
        Ok(())
    }
}

#[derive(Clone)]
struct ProgramMetadata {
    functions: HashSet<FnRef>,
}

impl ProgramMetadata {
    pub fn new() -> Self {
        Self {
            functions: HashSet::new(),
        }
    }
}

impl CompileVisitor for ProgramMetadata {
    fn register_meta(&mut self, meta: MetaRef<'_>) -> Result<(), MetaError> {
        if let Kind::Function { .. } = meta.kind {
            let name = meta.item.last().unwrap().to_string();
            self.functions.insert(FnRef::new(name.as_str()));
        }
        Ok(())
    }
}

/// Tracks statistics of the Rune function invoked by the workload
#[derive(Clone, Debug)]
pub struct FnStats {
    pub function: FnRef,
    pub call_count: u64,
    pub error_count: u64,
    pub call_latency: LatencyDistributionRecorder,
}

impl FnStats {
    pub fn new(function: FnRef) -> FnStats {
        FnStats {
            function,
            call_count: 0,
            error_count: 0,
            call_latency: LatencyDistributionRecorder::default(),
        }
    }

    pub fn reset(&mut self) {
        self.call_count = 0;
        self.error_count = 0;
        self.call_latency.clear();
    }

    pub fn operation_completed(&mut self, duration: Duration) {
        self.call_count += 1;
        self.call_latency.record(duration)
    }

    pub fn operation_failed(&mut self, duration: Duration) {
        self.call_count += 1;
        self.error_count += 1;
        self.call_latency.record(duration);
    }
}

/// Statistics of operations (function calls) and Cassandra requests.
pub struct WorkloadStats {
    pub start_time: Instant,
    pub end_time: Instant,
    pub function_stats: Vec<FnStats>,
    pub session_stats: SessionStats,
}

/// Mutable part of Workload
pub struct FnStatsCollector {
    start_time: Instant,
    fn_stats: Vec<FnStats>,
}

impl FnStatsCollector {
    pub fn new(functions: impl IntoIterator<Item = FnRef>) -> FnStatsCollector {
        let mut fn_stats = Vec::new();
        for f in functions {
            fn_stats.push(FnStats::new(f));
        }
        FnStatsCollector {
            start_time: Instant::now(),
            fn_stats,
        }
    }

    pub fn functions(&self) -> impl Iterator<Item = FnRef> + '_ {
        self.fn_stats.iter().map(|f| f.function.clone())
    }

    /// Records the duration of a successful operation
    pub fn operation_completed(&mut self, function: &FnRef, duration: Duration) {
        self.fn_stats_mut(function).operation_completed(duration);
    }

    /// Records the duration of a failed operation
    pub fn operation_failed(&mut self, function: &FnRef, duration: Duration) {
        self.fn_stats_mut(function).operation_failed(duration);
    }

    /// Finds the stats for given function.
    /// The function must exist! Otherwise, it will panic.
    fn fn_stats_mut(&mut self, function: &FnRef) -> &mut FnStats {
        self.fn_stats
            .iter_mut()
            .find(|f| f.function.hash == function.hash)
            .unwrap()
    }

    /// Clears any collected stats and sets the start time
    pub fn reset(&mut self, start_time: Instant) {
        self.fn_stats.iter_mut().for_each(FnStats::reset);
        self.start_time = start_time;
    }

    /// Returns the collected stats and resets this object
    pub fn take(&mut self, end_time: Instant) -> FnStatsCollector {
        let mut state = FnStatsCollector::new(self.functions());
        state.start_time = end_time;
        mem::swap(self, &mut state);
        state
    }
}

pub struct Workload {
    context: Context,
    program: Program,
    router: FunctionRouter,
    state: Mutex<FnStatsCollector>,
}

impl Workload {
    pub fn new(context: Context, program: Program, functions: &[(FnRef, f64)]) -> Workload {
        let state = FnStatsCollector::new(functions.iter().map(|x| x.0.clone()));
        Workload {
            context,
            program,
            router: FunctionRouter::new(functions),
            state: Mutex::new(state),
        }
    }

    pub fn clone(&self) -> Result<Self, LatteError> {
        Ok(Workload {
            context: self.context.clone()?,
            // make a deep copy to avoid congestion on Arc ref counts used heavily by Rune
            program: self.program.unshare(),
            router: self.router.clone(),
            state: Mutex::new(FnStatsCollector::new(self.state.lock().functions())),
        })
    }

    /// Executes a single cycle of a workload.
    /// This should be idempotent –
    /// the generated action should be a function of the iteration number.
    /// Returns the cycle number and the end time of the query.
    pub async fn run(
        &self,
        cycle: i64,
        scheduled_time: Instant,
    ) -> Result<(i64, Instant), LatteError> {
        // Fast path: skip RNG creation when there's only one function
        let function = if self.router.is_single_function() {
            self.router.get_single()
        } else {
            let mut rng = SmallRng::seed_from_u64(cycle as u64);
            self.router.select(&mut rng)
        };
        let mut current_retries_counter = 0;
        let mut end_time = Instant::now();
        let mut is_ok = false;
        let retry_number = self.context.retry_number;
        while current_retries_counter < retry_number {
            let current_err: CassError;
            // NOTE: Create a separate scope inside of the loop
            //       to be able to run additional retry-related async context functions.
            {
                let context = SessionRef::new(&self.context);
                // Note: Currently we measure duration from scheduled_time to end_time,
                // which includes scheduling delay. Measuring from start_time would give
                // pure execution time, but this is not currently tracked as a separate metric.
                let result = self.program.async_call(function, (context, cycle)).await;
                end_time = Instant::now();
                let mut state = self.state.lock();
                let duration = end_time - scheduled_time;

                match result {
                    Ok(_) => {
                        state.operation_completed(function, duration);
                        is_ok = true;
                        break;
                    }
                    Err(LatteError::Cassandra(boxed_err))
                        if matches!(boxed_err.0, CassErrorKind::Overloaded(_, _)) =>
                    {
                        // don't stop on overload errors;
                        // they are being counted by the context stats anyways
                        state.operation_failed(function, duration);
                        return Ok((cycle, end_time));
                    }
                    // NOTE: "CustomError" gets generated by the "signal_failure" context function
                    //       which may be called anytime in a rune function.
                    //       May be used for data validation and other needs which require re-run
                    //       of a rune function.
                    Err(LatteError::Cassandra(boxed_err))
                        if matches!(&boxed_err.0, CassErrorKind::CustomError(_)) =>
                    {
                        state.operation_failed(function, duration);
                        match &self.context.validation_strategy {
                            ValidationStrategy::Retry => {
                                current_err = *boxed_err;
                            }
                            ValidationStrategy::FailFast => {
                                return Err(LatteError::Cassandra(boxed_err))
                            }
                            ValidationStrategy::Ignore => {
                                current_err = *boxed_err;
                                is_ok = true;
                            }
                        }
                    }
                    Err(e) => {
                        state.operation_failed(function, duration);
                        return Err(e);
                    }
                }
            }
            handle_retry_error(&self.context, current_retries_counter, current_err).await;
            if is_ok {
                // ValidationStrategy::Ignore
                break;
            }
            current_retries_counter += 1; // ValidationStrategy::Retry
        }
        if is_ok {
            return Ok((cycle, end_time));
        }
        Err(CassError::query_retries_exceeded(self.context.retry_number).into())
    }

    /// Returns the reference to the contained context.
    /// Allows to e.g. access context stats.
    pub fn context(&self) -> &Context {
        &self.context
    }

    /// Sets the workload start time and resets the counters.
    /// Needed for producing `WorkloadStats` with
    /// recorded start and end times of measurement.
    pub fn reset(&self, start_time: Instant) {
        self.state.lock().reset(start_time);
        self.context.reset();
    }

    /// Returns statistics of the operations invoked by this workload so far.
    /// Resets the internal statistic counters.
    pub fn take_stats(&self, end_time: Instant) -> WorkloadStats {
        let state = self.state.lock().take(end_time);
        let result = WorkloadStats {
            start_time: state.start_time,
            end_time,
            function_stats: state.fn_stats.clone(),
            session_stats: self.context().take_session_stats(),
        };
        result
    }
}

/// DynamoDB-specific workload executor.
/// Mirrors the `Workload` struct but works with `DynamoContext` instead of `Context`.
pub struct DynamoWorkload {
    context: DynamoContext,
    program: Program,
    router: FunctionRouter,
    state: Mutex<FnStatsCollector>,
}

impl DynamoWorkload {
    pub fn new(
        context: DynamoContext,
        program: Program,
        functions: &[(FnRef, f64)],
    ) -> DynamoWorkload {
        let state = FnStatsCollector::new(functions.iter().map(|x| x.0.clone()));
        DynamoWorkload {
            context,
            program,
            router: FunctionRouter::new(functions),
            state: Mutex::new(state),
        }
    }

    pub fn clone(&self) -> Result<Self, LatteError> {
        Ok(DynamoWorkload {
            context: self.context.clone_for_thread().map_err(|e| {
                LatteError::Configuration(format!("Failed to clone DynamoDB context: {}", e))
            })?,
            // make a deep copy to avoid congestion on Arc ref counts used heavily by Rune
            program: self.program.unshare(),
            router: self.router.clone(),
            state: Mutex::new(FnStatsCollector::new(self.state.lock().functions())),
        })
    }

    /// Executes a single cycle of a workload.
    /// This should be idempotent –
    /// the generated action should be a function of the iteration number.
    /// Returns the cycle number and the end time of the query.
    pub async fn run(
        &self,
        cycle: i64,
        scheduled_time: Instant,
    ) -> Result<(i64, Instant), LatteError> {
        // Fast path: skip RNG creation when there's only one function
        let function = if self.router.is_single_function() {
            self.router.get_single()
        } else {
            let mut rng = SmallRng::seed_from_u64(cycle as u64);
            self.router.select(&mut rng)
        };
        let context = DynamoSessionRef::new(&self.context);
        let result = self.program.async_call(function, (context, cycle)).await;
        let end_time = Instant::now();
        let mut state = self.state.lock();
        let duration = end_time - scheduled_time;

        match result {
            Ok(_) => {
                state.operation_completed(function, duration);
                Ok((cycle, end_time))
            }
            Err(e) => {
                state.operation_failed(function, duration);
                Err(e)
            }
        }
    }

    /// Returns the reference to the contained context.
    pub fn context(&self) -> &DynamoContext {
        &self.context
    }

    /// Sets the workload start time and resets the counters.
    pub fn reset(&self, start_time: Instant) {
        self.state.lock().reset(start_time);
        self.context.reset();
    }

    /// Returns statistics of the operations invoked by this workload so far.
    /// Resets the internal statistic counters.
    pub fn take_stats(&self, end_time: Instant) -> WorkloadStats {
        let state = self.state.lock().take(end_time);
        let result = WorkloadStats {
            start_time: state.start_time,
            end_time,
            function_stats: state.fn_stats.clone(),
            session_stats: self.context().take_session_stats(),
        };
        result
    }
}

#[derive(Clone, Debug)]
struct FunctionRouter {
    selector: WeightedIndex<f64>,
    functions: Vec<FnRef>,
}

impl FunctionRouter {
    pub fn new(functions: &[(FnRef, f64)]) -> Self {
        let (functions, weights): (Vec<_>, Vec<_>) = functions.iter().cloned().unzip();
        let selector = WeightedIndex::new(weights).unwrap();
        FunctionRouter {
            selector,
            functions,
        }
    }

    /// Returns true if there's only one function, allowing callers to skip RNG creation.
    #[inline]
    pub fn is_single_function(&self) -> bool {
        self.functions.len() == 1
    }

    /// Get the single function (only valid when is_single_function() returns true).
    #[inline]
    pub fn get_single(&self) -> &FnRef {
        &self.functions[0]
    }

    #[inline]
    pub fn select(&self, rng: &mut impl Rng) -> &FnRef {
        &self.functions[self.selector.sample(rng)]
    }
}
