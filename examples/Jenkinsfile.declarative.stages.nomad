pipeline {
  agent none

  stages {
    stage('Build (Maven)') {
      steps {
        nomadTemplate(
          label: 'nomad',
          containers: [
            [$class: 'NomadContainerTemplate', name: 'maven', image: 'maven:3.9-eclipse-temurin-17', command: 'sleep', args: 'infinity']
          ]
        ) {
          node('nomad') {
            nomadContainer('maven') {
              sh 'echo "No checkout required"; mvn -v || true'
            }
          }
        }
      }
    }

    stage('Lint (Python)') {
      steps {
        nomadTemplate(
          label: 'nomad',
          containers: [
            [$class: 'NomadContainerTemplate', name: 'python', image: 'python:3.12', command: 'sleep', args: 'infinity']
          ]
        ) {
          node('nomad') {
            nomadContainer('python') {
              sh 'python --version'
            }
          }
        }
      }
    }
  }
}
