# Cross-mod planning

A cross-mod plan is one typed machine graph with resource-specific edges, not two blueprints placed nearby.

Candidate selection compares:

- recipe validity in the active runtime;
- owned material cost;
- footprint and obstacle impact;
- expected throughput and buffers;
- kinetic stress or FE production/consumption;
- configuration and recovery complexity;
- safety policy.

The first MVP bridge will move a real item from a verified Create output through an inventory buffer into a verified Mekanism input. The buffer is explicit so the planner can detect full output, missing extraction, wrong side configuration and rate mismatch. Fluids, chemicals and heat cannot cross an item edge. Execution requires fresh recipe, space, resource, compatibility, energy and sequence validation.
