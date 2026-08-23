---
status: accepted
---

# Treat the workbench prototype as the shape contract

For the role-workbench refactor, `/private/tmp/zimu-scan/zimu-frontend-prototype.html` is the required visual and interaction shape rather than loose inspiration. The production UI must preserve its information architecture, density, hierarchy, navigation, card interactions, and empty/error/loading placements, while every displayed fact and enabled action must come from a real product contract. Missing backend capability keeps its place in the layout with an honest unavailable or no-summary state; it must not be filled with invented data or removed merely to make implementation easier.

Delivery is phased, but each phase must be visually compared with the same prototype viewport before it can be called complete. A functional route test, typecheck, or build cannot substitute for that comparison.

## Considered options

- Pixel-copy the prototype including sample data and unsupported buttons: rejected because it would make a real business screen lie.
- Preserve only the workflow and restyle it with the existing generic Ant Design shell: rejected because it repeats the current failure and does not restore the approved product shape.
- Complete the full refactor in one release: rejected in favour of independently reviewable visual phases.
