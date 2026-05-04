pipeline {
  agent none

  stages {
    stage('Nomad Pipeline Template') {
      steps {
        script {
          nomadTemplate(
            label: 'nomad',
            containers: [
              [$class: 'NomadContainerTemplate', name: 'maven',  image: 'maven:3.9-eclipse-temurin-17', command: 'sleep', args: 'infinity'],
              [$class: 'NomadContainerTemplate', name: 'python', image: 'python:3.12',                    command: 'sleep', args: 'infinity'],
              [$class: 'NomadContainerTemplate', name: 'shell',  image: 'busybox:1.36',                   command: 'sleep', args: 'infinity']
            ]
          ) {
            node(env.NOMAD_TEMPLATE_LABEL) {
              stage('Build') {
                nomadContainer('maven') {
                  sh 'echo "No checkout required"; mvn -v || true'
                }
              }

              stage('Lint') {
                nomadContainer('python') {
                  sh 'python --version'
                }
              }

              stage('Smoke') {
                nomadContainer('shell') {
                  sh 'echo "ACTIVE=$NOMAD_ACTIVE_CONTAINER"; uname -a'
                }
              }
            }
          }
        }
      }
    }
  }
}
