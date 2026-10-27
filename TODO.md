# TODO

Work items to implement later, newest at the bottom. Pick one by its number ("do task 2").

**Numbers are stable.** Never renumber an existing item, not even when one above it is finished: a number said out loud in a session must keep pointing at the same work. A finished item is ticked `[x]` and moved to the **Done** section with its number intact; sub-items keep theirs (`2.3`).

## Open

- [ ] **1. Fix water pathfinding.** The companion puts waypoints on blocks under water even when it could simply swim straight forward. Where to look: `pathing/MoveGenerator` (swim move) and its adapter `task/nav/LevelCellView` (how fluids and collision are reported), then `task/nav/PathExecutor`.

- [ ] **2. Rework building.** Today the companion only replays blueprint cells in a fixed order; it should understand the building and place what vanilla refuses to put in mid-air. Large item: write a spec and a plan first (see AGENTS.md, "Working from TODO.md").
  - [ ] **2.1. Structure understanding.** Recognise the parts of a building — floor, walls, roof, entrance/doorway, interior — and expose them as actions and prompt facts instead of one opaque `build_structure`.
  - [ ] **2.2. Site clearing.** A first-class "clear this area" action (vegetation, snow, water, stray blocks, respecting the config break/place blacklists and place protection). Today only `BuildTask`'s `Site`/`toClear()` and `light_area` touch this.
  - [ ] **2.3. Placement preconditions.** `BuildTask.needsSupport` is a string check for `torch`, `door` and `ladder` only. Derive the real requirement from the block state (support shape / whether it can survive where it stands; look the 26.3 names up) and order blocks by that dependency, so a wall torch never runs before the wall it hangs on, whichever layer the wall is in.
  - [ ] **2.4. No face to click.** A wall torch needs a wall; a stair or slab placed in mid-air needs a neighbour to click. Whenever `PlaceSpot`/`CompanionHands` has no reachable face, fall back to a temporary support block. `Scaffold.support` already does this but is wired into one failure path only.
  - [ ] **2.5. Roofs.** Stairs and slabs on gable/pyramid roofs: correct facing/half/shape, temporary supports under them, and removal of the scaffold afterwards (`ScaffoldCleanupTask`).
  - [ ] **2.6. Tests.** Unit tests for ordering and the support derivation in `build/`, GameTests for a torch on a wall built in the same pass, a stair roof on a temporary block, and clear-area.
