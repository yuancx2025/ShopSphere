# Sprint 7 · AWS and final evaluation

[Roadmap](../README.md) · [Previous sprint](06-traffic-protection.md) · [Target résumé](../resume/target-resume.md)

**Time** · 2 weeks. **Depends on** · The benchmark harness and stable application profiles. The full report incorporates the earlier feature benchmarks.

## What to learn

- Explain an EC2 deployment from network entry to application, private data services, logs, deployment identity and secrets.
- Understand image digests, GitHub Actions OIDC, staging smoke tests, database migration compatibility, rollback and deployment verification.
- Explain how generator location, instance size, CPU credits, JVM sizing and active services affect measured cloud capacity.
- Estimate infrastructure cost from resource-hours and storage, and verify teardown against the resource inventory.

## What to implement

- **Deployment profile.** Use an EC2 staging host and Docker Compose. Start with Nginx, the React build, Spring Boot, MySQL and Redis if the profile fits. Define explicitly how search and messaging are enabled for the full local demo and which features are active in staging. Serve the staging app over TLS when externally exposed. Restrict inbound access and keep database, Redis and management endpoints private. Supply dedicated runtime secrets instead of the development Compose defaults.
- **Deploy and roll back.** Extend GitHub Actions to build and deploy a pinned image from an approved staging environment. Use a narrowly scoped AWS role through OIDC for deployment and restrict its trust policy to the repository and environment. Verify health and a few core user flows. Keep a previous image reference and test rollback with migrations compatible with both versions, or document a tested recovery procedure for incompatible migrations.
- **Cloud benchmark.** Run one staging smoke test first and put the load generator on a separate host for capacity measurements. Use fake payments or limit real Stripe Sandbox work to a contract check. Record provisioned resources, network path, CPU credit state, service topology and instance uptime. Use separate labels for local and cloud results.
- **Final report.** Commit a short runbook and summary with commands, raw artifact pointers, the experiment matrix, and the highest passing capacity point plus the first failed step if reached. Include legacy/paginated, cache off/on, search comparison, broker recovery and burst results, with distinct comparison boundaries. Retain a simple diagram of MySQL, Redis, search, the outbox and the consumer path.
- **Teardown.** Export benchmark results and logs needed for the report. Tear down ephemeral instances and volumes, release any Elastic IPs, and check remaining snapshots, logs and staging resources.

## Target and evidence

| Target | How to prove it |
| --- | --- |
| Repeatable staging deploy and rollback | Record two successful deployment smoke runs and one tested rollback. Time from deploy start to verified health and from rollback start to restored smoke checks. |
| Final passing-capacity result | On a fixed shopping mix and fixed topology, step the load and re-run the highest passing point for three measured windows. Report achieved successful RPS, P95 by route, unexpected failures, expected outcomes and drops. List the highest tested passing point if testing stops before saturation. |
| Independently reproducible conclusions | Record input seed, app commits/flags, CPU/RAM, container versions, timing window and trial spread. Attach the checkout and event invariants to their corresponding scenarios. |
| Staging costs within a $5–$25 planning allocation | Compare resource-hours and actual billed usage after the run. Configure a budget alert and use teardown to limit resource lifetime. |

## AWS cost example

Illustrative Linux On-Demand pricing for **US East (N. Virginia)**, checked November 8, 2026. Assumptions are one public IPv4 address at $0.005/hour and one 30 GB gp3 volume at $0.08/GB-month, with the volume retained only for the modeled period. The monthly example uses 730 compute hours. The 12-hour base estimates exclude a separate load-generator host, outbound data transfer, CPU credit charges, snapshots, logs, taxes and optional services.

| Staging profile | EC2 compute | Approx. 12-hour base | Approx. monthly base |
| --- | --- | --- | --- |
| `t3.medium` · 4 GiB | $0.0418/hr | $0.60 | $36.56 |
| `t3.large` · 8 GiB | $0.0835/hr | $1.10 | $67.01 |

The full local topology can need more memory than either example. Treat these as staging configurations and validate instance sizing during the smoke run. Sprints 0–6 and the local part of Sprint 7 can run without AWS charges. A budget alert can arrive after charges accrue. Resource cleanup is what limits recurring costs.

[EC2 T3 pricing](https://aws.amazon.com/ec2/instance-types/t3/) · [EBS pricing](https://aws.amazon.com/ebs/pricing/) · [Public IPv4 pricing](https://aws.amazon.com/vpc/pricing/) · [AWS Budgets](https://docs.aws.amazon.com/cost-management/latest/userguide/bcm-lite-view-budget.html)

## Implementation PRs

| PR | Scope | Merge gate |
| --- | --- | --- |
| 14 · `infra/aws-deployment` | Staging Compose configuration, deployment workflow, smoke and rollback runbook | Staging can be deployed and rolled back from documented steps. Access to data and management services stays internal. |
| 15 · `perf/final-benchmark-report` | Final benchmark matrix, measured capacity, architecture notes, cost and reproduction instructions | Every final claim traces to a named run, and local results are labeled separately from cloud results. |

## Résumé wording

**Cloud capacity version.** “Deployed ShopSphere to AWS EC2 through GitHub Actions and Docker, sustaining [R] successful requests/sec at [P] ms catalog P95 and [E]% unexpected failures in a [T]-minute staging load test.”

**Cloud delivery version.** “Automated ShopSphere staging deployment to AWS EC2 with GitHub Actions OIDC, pinned Docker images, smoke checks, and a tested rollback.”

If the best load test ran locally, use a separate sentence for local performance or use the cloud delivery version. Keep staging topology and the measured endpoint attached to the capacity claim.

[GitHub Actions OIDC on AWS](https://docs.github.com/en/actions/how-tos/secure-your-work/security-harden-deployments/oidc-in-aws)
