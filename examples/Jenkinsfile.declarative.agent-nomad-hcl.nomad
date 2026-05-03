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
      resources {
        cpu    = 500
        memory = 512
      }
    }

    task "shell" {
      driver = "docker"
      config {
        image = "busybox:1.36"
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
