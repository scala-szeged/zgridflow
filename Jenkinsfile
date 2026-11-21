pipeline {
  agent any
  options {
    disableConcurrentBuilds()
    timeout(time: 30, unit: 'MINUTES')
    buildDiscarder(logRotator(numToKeepStr: '10'))
    skipDefaultCheckout(true)
  }
  triggers { pollSCM('H/2 * * * *') }
  environment {
    PATH = "/opt/homebrew/bin:/usr/local/bin:${env.HOME}/.sdkman/candidates/sbt/current/bin:${env.PATH}"
  }
  stages {
    stage('Checkout') {
      steps {
        checkout scm
        script {
          env.SOURCE_SHA = sh(script: 'git rev-parse HEAD', returnStdout: true).trim()
          env.IMAGE_TAG = env.SOURCE_SHA.take(12)
          def fromPolling = !currentBuild.getBuildCauses('hudson.triggers.SCMTrigger$SCMTriggerCause').isEmpty()
          def imageUpdate = sh(script: 'git log -1 --pretty=%s', returnStdout: true).contains('[skip ci]')
          env.SKIP_BUILD = fromPolling && imageUpdate ? 'true' : 'false'
        }
      }
    }
    stage('POC pipeline') {
      when { expression { env.SKIP_BUILD != 'true' } }
      stages {
        stage('Tests and Helm validation') { steps { sh './ci/validate.sh' } }
        stage('Build images and load into kind') { steps { sh './ci/build-images.sh "$IMAGE_TAG"' } }
        stage('Commit desired state') { steps { sh './ci/publish-desired-state.sh "$IMAGE_TAG"' } }
        stage('Wait for Argo CD') {
          steps {
            sh '''
              kubectl --context kind-zgridflow -n argocd annotate application zgridflow-root argocd.argoproj.io/refresh=hard --overwrite
              kubectl --context kind-zgridflow -n argocd annotate application poc-local argocd.argoproj.io/refresh=hard --overwrite
              kubectl --context kind-zgridflow -n argocd wait application/poc-local --for="jsonpath={.status.sync.revision}=$(git rev-parse HEAD)" --timeout=180s
              kubectl --context kind-zgridflow -n argocd wait application/poc-local --for=jsonpath='{.status.health.status}'=Healthy --timeout=300s
              kubectl --context kind-zgridflow -n zgridflow-local rollout status deployment/api --timeout=180s
              kubectl --context kind-zgridflow -n zgridflow-local rollout status deployment/gateway --timeout=180s
              ./scripts/poc-smoke-test.sh
            '''
          }
        }
      }
    }
  }
}
