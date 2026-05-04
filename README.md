# HashiCorp Nomad Plugin for Jenkins

This plugin provisions ephemeral Jenkins agents as Nomad jobs and supports a Jenkins pipeline experience similar to the Kubernetes plugin model:

- Jenkins schedules an agent from a configured Nomad cloud/template.
- The plugin submits a Nomad job over the Nomad HTTP API.
- The primary `jnlp` task connects back to Jenkins as an inbound agent.
- Optional sidecar tasks can be defined in the Jenkins UI or directly in the pipeline.
- `nomadContainer(...)` can execute steps inside a sidecar task using the Nomad exec API.

## Features

- Jenkins cloud integration through `NomadCloud`
- Ephemeral agent provisioning with `NomadAgent` / `NomadLauncher`
- Nomad job rendering through `NomadJobBuilder`
- Jenkins Credentials support for Nomad ACL tokens
- Optional workload identity mode hints in task environment
- Connection test button in Jenkins global configuration
- Optional `Skip TLS Verify` support for Nomad HTTPS endpoints
- Multi-container task groups with shared workspace volume
- Pipeline steps:
	- `nomadTemplate(...)`
	- `nomadContainer('name')`
- Agent cleanup on termination via Nomad stop/purge

## Current behavior

The plugin currently works best with Docker-based Nomad tasks and Jenkins inbound agents.

- The Nomad task group always contains a `jnlp` task.
- Additional sidecar tasks can be added from the UI or from pipeline DSL.
- The workspace defaults to `/tmp/jenkins-agent` and is mounted into all tasks.
- Tasks are rendered with `User: 0` to preserve shared-workspace compatibility without relying on a `root` username entry in the image passwd file.
- `nomadContainer(...)` uses the Nomad WebSocket exec API and does **not** require a local `nomad` CLI on Jenkins.

## Jenkins configuration

Add the plugin HPI to Jenkins, then configure a cloud under:

- `Manage Jenkins` → `Clouds` → `Add a new cloud` → `Nomad`

### Cloud fields

- **Nomad address**: Example `http://127.0.0.1:4646` or `https://nomad.example.com`
- **Region**: Defaults to `global`
- **Namespace**: Defaults to `default`
- **Jenkins URL**: Override Jenkins root URL if auto-detection is wrong for agents
- **Use WebSocket**: Recommended for inbound agents
- **Auth mode**:
	- `ACL_TOKEN`
	- `WORKLOAD_IDENTITY`
- **ACL token credential**: Jenkins Secret Text credential used as `X-Nomad-Token`
- **Workload identity audience**: Passed as environment hints when workload identity mode is selected
- **Skip TLS Verify**: Disables certificate and hostname verification for Nomad API calls

### Agent template fields

Each Nomad cloud can have one or more agent templates.

- **Label**: Jenkins label used by `node('label')`
- **Image**: Docker image for the `jnlp` task
- **Datacenters**: Comma-separated Nomad datacenters
- **Namespace**: Optional per-template namespace override
- **Command / Args**: Optional overrides for the `jnlp` task
- **Workspace Dir**: Shared workspace path, default `/tmp/jenkins-agent`
- **CPU / Memory**: Resource settings for the `jnlp` task
- **Containers**: Optional sidecar task definitions
- **Sidecar TTY**: Set `ttyEnabled` for sidecars that require interactive/TTY behavior; for kaniko debug images use `entrypoint: '/busybox/cat'` (not `command`) so Docker entrypoint is correctly overridden

## Authentication and ACLs

### ACL token mode

When `ACL_TOKEN` is selected, the plugin sends the token from Jenkins credentials in the `X-Nomad-Token` header.

### Workload identity mode

When `WORKLOAD_IDENTITY` is selected, the plugin currently injects the following environment variables into the `jnlp` task:

- `NOMAD_WORKLOAD_IDENTITY=true`
- `NOMAD_WORKLOAD_IDENTITY_AUDIENCE=<configured value>`

This is currently a hinting/integration scaffold, not a full Jenkins-to-Nomad identity exchange implementation.

### ACL capabilities needed

The exact policy depends on your environment, but the plugin generally needs:

- **Connectivity test**: ability to query Nomad leader/status
- **Job submit/stop**: permissions to create and stop the generated agent jobs
- **Allocation inspection**: read access for agent allocation lookup
- **Container exec**: `namespace:alloc-exec`
- **If using `raw_exec` tasks**: `namespace:alloc-node-exec`

## Pipeline usage

Both step symbols are available in Jenkins Pipeline DSL:

- `nomadTemplate(...)`
- `nomadContainer('name')`

### Single-container agent

This is the simplest case. The build runs in the `jnlp` image configured in the Jenkins UI template.

```groovy
node('nomad') {
	sh 'uname -a'
}
```

### Sidecar container from pipeline

Use `nomadTemplate(...)` to define sidecars for the scope of the pipeline block.

```groovy
nomadTemplate(
	label: 'nomad',
	containers: [
		[$class: 'NomadContainerTemplate', name: 'shell', image: 'busybox:1.36', command: 'sleep', args: 'infinity']
	]
) {
	node('nomad') {
		nomadContainer('shell') {
			sh 'echo "ACTIVE=$NOMAD_ACTIVE_CONTAINER"; uname -a; echo hello from sidecar'
		}
	}
}

// If your BusyBox build does not support `sleep infinity`, use:
// command: 'tail', args: '-f /dev/null'
```

### Declarative Pipeline DSL

You can also use the same steps in a Declarative Jenkinsfile:

```groovy
pipeline {
	agent none

	stages {
		stage('Nomad Sidecar') {
			steps {
				nomadTemplate(
					label: 'nomad',
					containers: [
						[$class: 'NomadContainerTemplate', name: 'shell', image: 'busybox:1.36', command: 'sleep', args: 'infinity']
					]
				) {
					node('nomad') {
						nomadContainer('shell') {
							sh 'echo "ACTIVE=$NOMAD_ACTIVE_CONTAINER"; uname -a; echo hello from sidecar'
						}
					}
				}
			}
		}
	}
}
```

Reference examples are available in:

- `examples/Jenkinsfile.scripted.nomad`
- `examples/Jenkinsfile.declarative.nomad`
- `examples/Jenkinsfile.declarative.stages.nomad`
- `examples/Jenkinsfile.declarative.global-agent.nomad`

### Declarative with top-level stages (container per stage)

If you want separate top-level Declarative stages with one container per stage, wrap each stage body in `nomadTemplate(...) { node('nomad') { ... } }`.

See: `examples/Jenkinsfile.declarative.stages.nomad`.

### Declarative with one global agent (UI-defined sidecars)

If you define sidecars in the Jenkins Nomad agent template UI, you can use a global Declarative agent and call `nomadContainer('name')` directly per stage without repeating `nomadTemplate(...)`.

See: `examples/Jenkinsfile.declarative.global-agent.nomad`.

### Declarative pipeline-level Nomad agent (podTemplate-like)

You can define the Nomad template at pipeline agent level:

```groovy
pipeline {
	agent {
		nomad {
			label 'nomad'
			containers([
				[$class: 'NomadContainerTemplate', name: 'maven',  image: 'maven:3.9-eclipse-temurin-17', command: 'sleep', args: 'infinity'],
				[$class: 'NomadContainerTemplate', name: 'python', image: 'python:3.12',                    command: 'sleep', args: 'infinity'],
				[$class: 'NomadContainerTemplate', name: 'shell',  image: 'busybox:1.36',                   command: 'sleep', args: 'infinity']
			])
		}
	}

	stages {
		stage('Build') {
			steps {
				nomadContainer('maven') {
					sh 'echo "No checkout required"; mvn -v || true'
				}
			}
		}
		stage('Lint') {
			steps {
				nomadContainer('python') {
					sh 'python --version'
				}
			}
		}
		stage('Smoke') {
			steps {
				nomadContainer('shell') {
					sh 'echo "ACTIVE=$NOMAD_ACTIVE_CONTAINER"; uname -a'
				}
			}
		}
	}
}
```

See: `examples/Jenkinsfile.declarative.agent-nomad.nomad`.

You can also define sidecar tasks via Nomad HCL and let the plugin derive containers from `task` blocks:

```groovy
pipeline {
	agent {
		nomad {
			label 'nomad'
			jobHcl '''
job "jenkins-agent" {
	group "agent" {
		task "maven" {
			driver = "docker"
			config {
				image = "maven:3.9-eclipse-temurin-17"
				command = "sleep"
				args = ["infinity"]
			}
			resources {
				cpu    = 500
				memory = 512
			}
		}
		task "python" {
			driver = "docker"
			config {
				image = "python:3.12"
				command = "sleep"
				args = ["infinity"]
			}
		}
	}
}
			'''
		}
	}

	stages {
		stage('Build') {
			steps {
				nomadContainer('maven') {
					sh 'mvn -v || true'
				}
			}
		}
		stage('Lint') {
			steps {
				nomadContainer('python') {
					sh 'python --version'
				}
			}
		}
	}
}
```

See: `examples/Jenkinsfile.declarative.agent-nomad-hcl.nomad`.

### Pipeline-level `nomadTemplate` (Kubernetes `podTemplate` style)

If you want one `nomadTemplate(...)` for the whole flow (similar to `podTemplate { node { ... } }`), use Scripted Pipeline, or use Declarative with a `script { ... }` wrapper.

See:

- `examples/Jenkinsfile.scripted.pipeline-template.nomad`
- `examples/Jenkinsfile.declarative.pipeline-template.nomad`

Note: true Declarative root-level template syntax (for example a custom `agent { nomad ... }`) requires a dedicated Declarative agent implementation in the plugin.

### Important label rule

Keep the `nomadTemplate(label: ...)` value aligned with the label used in `node('...')` and with the label available from the Jenkins UI template.

Good:

```groovy
nomadTemplate(label: 'nomad', containers: [ ... ]) {
	node('nomad') {
		nomadContainer('shell') {
			sh 'echo test'
		}
	}
}
```

Avoid mixing labels such as `nomadTemplate(label: 'nomad-tools')` with `node('nomad')` unless you intentionally understand how the backing UI template and pipeline scope are being matched.

### Sidecars defined in UI

You can also define sidecars directly in the Jenkins cloud template configuration. Those containers are always part of the provisioned agent job for that template.

### Pipeline vs UI sidecars

- **UI containers**: static, always tied to the agent template
- **Pipeline containers**: dynamic, scoped to the `nomadTemplate { ... }` block
- If both define a container with the same name, the pipeline definition is intended to override that task by name for the running build scope

## How `nomadContainer(...)` works

`nomadContainer('shell')` does not create a second Jenkins agent. Instead it:

- keeps Jenkins connected through the `jnlp` task
- finds the active Nomad allocation for the current agent job
- uses the Nomad exec API to run the requested shell command inside the named sidecar task
- creates the requested workspace directory path before `cd` when needed
- streams stdout/stderr back into the Jenkins build log

## Debugging

When troubleshooting, look at the Jenkins build log for:

- `Rendered Nomad job payload:`

That payload should show the submitted Nomad job and is the fastest way to confirm whether sidecar tasks were rendered correctly.

### Things to verify

- The `Tasks` array includes both `jnlp` and your sidecar, such as `maven`
- The image names are correct and pullable by the Nomad client
- The build uses matching labels between `nomadTemplate(...)` and `node(...)`
- The Nomad ACL token has exec permissions when using `nomadContainer(...)`
- The Nomad allocation is in a running state before the first sidecar command executes

## Build and test

```bash
cd /Users/david/SynologyDrive/SysAdmin/jenkins-nomad-plugin
export JAVA_HOME="/opt/homebrew/Cellar/openjdk/25.0.2/libexec/openjdk.jdk/Contents/Home"
export PATH="$JAVA_HOME/bin:$PATH"
/usr/bin/env mvn -B -ntp test
/usr/bin/env mvn -B -ntp clean package
```

To launch a local development Jenkins:

```bash
cd /Users/david/SynologyDrive/SysAdmin/jenkins-nomad-plugin
export JAVA_HOME="/opt/homebrew/Cellar/openjdk/25.0.2/libexec/openjdk.jdk/Contents/Home"
export PATH="$JAVA_HOME/bin:$PATH"
/usr/bin/env mvn -B -ntp hpi:run
```

## Project layout

- `src/main/java/io/jenkins/plugins/nomad/NomadCloud.java`: Jenkins cloud entrypoint
- `src/main/java/io/jenkins/plugins/nomad/NomadAgentTemplate.java`: agent template model
- `src/main/java/io/jenkins/plugins/nomad/NomadContainerTemplate.java`: sidecar task model
- `src/main/java/io/jenkins/plugins/nomad/NomadAgent.java`: ephemeral node model
- `src/main/java/io/jenkins/plugins/nomad/NomadLauncher.java`: Nomad submission hook
- `src/main/java/io/jenkins/plugins/nomad/NomadJobBuilder.java`: Nomad job JSON rendering
- `src/main/java/io/jenkins/plugins/nomad/NomadTemplateStep.java`: pipeline template scope step
- `src/main/java/io/jenkins/plugins/nomad/ContainerStep.java`: pipeline sidecar execution step
- `src/main/java/io/jenkins/plugins/nomad/NomadContainerExecDecorator.java`: exec bridge into sidecar tasks via Nomad API

## Known limitations

- The plugin is still evolving and has not yet reached feature parity with the Jenkins Kubernetes plugin.
- The current multi-container implementation is oriented around shell execution in sidecar tasks, not full per-container Jenkins remoting.
- Workload identity support is currently partial.
- Docker is the primary tested driver path today.

## Development notes

- Prefer Jenkins inbound agents over SSH-based agent models
- Prefer Jenkins WebSocket inbound connections where possible
- Keep generated Nomad jobs ephemeral and uniquely named per agent
- Keep changes focused on Nomad job submission, allocation lifecycle, and container execution semantics

## License

This project is licensed under the MIT License.

See `LICENSE` for details.
