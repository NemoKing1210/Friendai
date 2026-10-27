# TODO

Work items to implement later, newest at the bottom. Delete an item when it lands.

## Open

- [ ] **Fix water pathfinding.** The companion puts waypoints on blocks under water even when it could simply swim straight forward. Where to look: `pathing/MoveGenerator` (swim move) and its adapter `task/nav/LevelCellView` (how fluids and collision are reported), then `task/nav/PathExecutor`.
