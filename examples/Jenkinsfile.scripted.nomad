nomadTemplate(
  label: 'nomad',
  containers: [
    [$class: 'NomadContainerTemplate', name: 'shell', image: 'busybox:1.36', command: 'sleep', args: 'infinity']
  ]
) {
  node(env.NOMAD_TEMPLATE_LABEL) {
    nomadContainer('shell') {
      sh 'echo "ACTIVE=$NOMAD_ACTIVE_CONTAINER"; uname -a; echo hello from sidecar'
    }
  }
}
