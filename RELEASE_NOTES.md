# v0.1.3 – Runtime Stability and Pipeline Isolation

Stability release focused on first-run reliability, pipeline scope isolation, and sidecar workspace consistency for real-world Nomad + Kaniko workflows.

## Highlights

- Added per-scope effective label handling (`NOMAD_TEMPLATE_LABEL`) to prevent cross-build agent/sidecar reuse
- Declarative pipeline-level Nomad agent now resolves and uses generated effective labels automatically
- Improved durable-task execution path in `nomadContainer(...)` for sidecar command startup reliability
- Added workspace/working-directory normalization for sidecar exec commands
- Ensured `jnlp` arguments include `-workDir <workspaceDir>` when not explicitly provided
- Updated task volume mapping to use `${NOMAD_ALLOC_DIR}:<workspaceDir>` for stronger shared-workspace behavior across tasks
- Added configurable durable script wait tuning for first-run image pull latency:
  - `io.jenkins.plugins.nomad.durableScriptWaitSeconds` (default `180`)
- Retained existing task startup timeout tuning:
  - `io.jenkins.plugins.nomad.taskStartTimeoutSeconds` (default `600`)
- Updated examples and README to the final recommended isolation pattern (`node(env.NOMAD_TEMPLATE_LABEL)`)

## Why this release

- Addresses "first run fails, second run passes" behavior commonly seen when sidecar images are pulled on demand
- Prevents accidental reuse of stale agents that do not include expected scoped sidecars
- Improves sidecar compatibility for Kaniko and similar tool images in mixed Scripted/Declarative pipelines

## Source

- Repository: <https://github.com/dcanadillas/jenkins-hashicorp-nomad-plugin>
- Tag: `v0.1.3`
# v0.1.2 – Sidecar Entrypoint Support

Compatibility release focused on sidecar startup behavior for images with fixed entrypoints (such as Kaniko).

## Highlights

- Added `entrypoint` to `NomadContainerTemplate` (Pipeline DSL and Jenkins UI)
- Sidecar Docker config now renders `entrypoint: ["..."]` when provided
- `NomadCloud` template copy/merge now preserves sidecar `entrypoint`
- Updated Kaniko guidance: use `entrypoint: '/busybox/cat'` with `ttyEnabled: true` (instead of `command`)
- Added/updated tests for entrypoint + TTY rendering behavior

## Why this release

- Fixes errors like `unknown command "/busybox/cat" for "executor"` in Kaniko debug images
- Enables Kubernetes-style sidecar keepalive patterns by overriding Docker entrypoint explicitly

## Source

- Repository: <https://github.com/dcanadillas/jenkins-hashicorp-nomad-plugin>
- Tag: `v0.1.2`

# v0.1.1 – Sidecar Runtime Compatibility

Stability and compatibility release focused on sidecar task behavior for real-world pipelines (including Kaniko-style workflows).

## Highlights

- Sidecar task user now renders as UID `0` (instead of `root`) to avoid image failures where `/etc/passwd` has no `root` entry
- Added optional sidecar TTY support via `ttyEnabled` in `NomadContainerTemplate`
- When `ttyEnabled` is set, Nomad Docker task config renders `tty: true` and `interactive: true`
- Cloud/template copy logic now preserves `ttyEnabled`
- Updated docs and tests to reflect runtime behavior and template isolation rules

## Why this release

- Improves compatibility with hardened/minimal container images
- Enables Kubernetes-like interactive sidecar patterns used by tools such as Kaniko wrappers

## Source

- Repository: <https://github.com/dcanadillas/jenkins-hashicorp-nomad-plugin>
- Tag: `v0.1.1`

# v0.1.0 – Initial Release

First public release of the Jenkins plugin for provisioning ephemeral Jenkins agents on HashiCorp Nomad, with support for sidecar container execution in Pipelines.

## Highlights

- Provision ephemeral Jenkins agents as Nomad jobs
- Native Jenkins Cloud integration (`NomadCloud`)
- Pipeline support with:
  - `nomadTemplate(...)`
  - `nomadContainer('name')`
- Declarative pipeline agent support:
  - `agent { nomad { ... } }`
- Sidecar task execution via Nomad WebSocket exec API
- Optional container definitions from `jobHcl`
- Jenkins credentials support for Nomad ACL tokens
- Optional `Skip TLS Verify` for Nomad HTTPS endpoints
- Agent cleanup via Nomad stop/purge

## Pipeline UX

Supports both Scripted and Declarative pipelines, including:

- stage-level sidecar execution
- global pipeline-level Nomad agent patterns
- container-per-stage workflows

## Compatibility

- Jenkins baseline: `2.479.x`
- Java: `17+`
- Nomad API integration over HTTP/HTTPS

## Installation

1. Download plugin artifact: `hashicorp-nomad-plugin.hpi`
2. In Jenkins: **Manage Jenkins → Plugins → Advanced**
3. Upload the `.hpi` file
4. Restart Jenkins

## Known Notes

- Optimized for Docker-based Nomad task drivers
- `nomadContainer(...)` requires target sidecar task to be started/running
- Ensure Nomad ACL policy includes allocation exec permissions where needed

## Source

- Repository: <https://github.com/dcanadillas/jenkins-hashicorp-nomad-plugin>
- Tag: `v0.1.0`
