pipeline {
  agent { label 'nomad' }

  stages {
    stage('Build (Maven)') {
      steps {
        nomadContainer('maven') {
          sh 'echo "No checkout required"; mvn -v || true'
        }
      }
    }

    stage('Lint (Python)') {
      steps {
        nomadContainer('python') {
          sh 'python --version'
        }
      }
    }

    stage('Smoke (Shell)') {
      steps {
        nomadContainer('shell') {
          sh 'echo "ACTIVE=$NOMAD_ACTIVE_CONTAINER"; uname -a'
        }
      }
    }
  }
}
