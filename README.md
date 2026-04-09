# CoreShift 


# Action-Oriented Execution Core

Deterministic execution system built on strict layering and action-driven state transitions.

---

## Architecture

Defined layering model:

- low_level → OS primitives (syscalls, spawn, reactor)
- mid_level → transport / IPC routing
- core → action loop + execution engine
- modules → isolated behavior units

System contract:
- Core is the only side-effect executor  
- Modules emit actions only  
- Mid-level is stateless and policy-free 

---

## Model

### Protocol
All state transitions are expressed through a single protocol:

- `Action` → intent
- `Event` → external signal
- `Effect` → resolved side-effect

Execution flow:

Request → Action → Dispatcher → Actions → Effects → Executor → Event → repeat

---

## Core Properties

### Determinism
- No module performs side effects  
- All effects centralized in executor  
- State transitions explicit and replayable  

### Isolation
- Modules do not communicate directly  
- No shared mutation outside `ExecutionState`  

### Composability
- Behavior added via modules  
- No cross-layer leakage  

---

## Modules

Core modules:

- Admission → job gating and limits  
- Lifecycle → state transitions  
- Process → signal translation  
- IO → fd readiness + stream handling  
- Result → output collection + retention  
- Timeout → deadline + kill policy  

Each module:
- consumes `Action` or `Event`
- emits new `Action` only  

---

## Execution Engine

### Dispatcher
Routes actions to relevant modules.

### Effect Resolution
Maps semantic actions into concrete effects:
- process spawn  
- signal delivery  
- fd registration  
- logging  

### Executor
Applies all side effects:
- spawn processes  
- manage epoll reactor  
- deliver signals  
- emit events  

No other layer is allowed to perform side effects.

---

## Invariants

- Single writer of side effects (core executor)  
- No policy in mid-level  
- No direct syscall usage above low_level  
- Modules are pure transformation units  
- All external interaction flows through Action → Effect boundary 1  

---

## Current Status

- Core action loop implemented  
- Process spawning (posix_spawn + fork fallback)  
- Epoll-based reactor integrated  
- IO registration and handling in place  
- Timeout and lifecycle enforcement active  

Stability: experimental

---

## Progress Log

### v1
- Established full layered architecture  
- Implemented dispatcher + execution loop  
- Integrated reactor and process model  
- Enforced module isolation and action protocol  

---

## Current Focus

- Strengthen IO execution path (PerformIo handling)  
- Tighten effect resolution boundaries  
- Validate determinism under concurrent load  
- Formalize high-level API constraints  

---

## Known Constraints

- IO execution path partially deferred  
- Property-based backend selection (Android API)  
- FD lifecycle depends on strict ownership discipline  
- No persistent job storage beyond in-memory state  

---

## Design Notes

- Action protocol replaces direct function orchestration  
- Reactor-driven execution avoids blocking semantics  
- Fork fallback required for isolation edge cases  
- Timeout handled as policy module, not executor logic  

---

## Scope

This repository tracks architectural progress and implementation of the execution core.  
No stability guarantees. Interfaces are subject to change.
